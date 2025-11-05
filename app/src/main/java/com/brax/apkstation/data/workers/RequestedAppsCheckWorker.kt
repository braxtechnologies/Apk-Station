package com.brax.apkstation.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Result as ApiResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker that periodically checks for apps in REQUESTED state
 * and attempts to download them if they become available
 */
@HiltWorker
class RequestedAppsCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apkRepository: ApkRepository,
    private val workManager: androidx.work.WorkManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "RequestedAppsCheck"
        const val WORK_NAME = "requested_apps_check"
        
        /**
         * Schedule periodic checks for requested apps (every 5 minutes)
         */
        fun schedulePeriodicCheck(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<RequestedAppsCheckWorker>(
                repeatInterval = 5,
                repeatIntervalTimeUnit = java.util.concurrent.TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    5,
                    java.util.concurrent.TimeUnit.MINUTES
                )
                .build()
            
            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
            
            Log.i(TAG, "Scheduled periodic requested apps check (every 5 minutes)")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting requested apps check...")

        return withContext(Dispatchers.IO) {
            try {
                // Get all apps in REQUESTED state from database
                val requestedApps = apkRepository.getRequestedApps()
                
                if (requestedApps.isEmpty()) {
                    Log.d(TAG, "No requested apps found")
                    return@withContext Result.success()
                }

                Log.d(TAG, "Found ${requestedApps.size} requested app(s): ${requestedApps.map { it.packageName }}")

                var successCount = 0
                var stillPendingCount = 0
                var failureCount = 0
                var unavailableCount = 0

                // For each requested app, try to download it
                for (app in requestedApps) {
                    Log.d(TAG, "Checking availability for ${app.packageName} (retry ${app.retryCount}/3)...")
                    
                    // Check if app has exceeded retry limit
                    if (app.retryCount >= 3) {
                        Log.w(TAG, "${app.packageName}: Exceeded retry limit (${app.retryCount} attempts), marking as UNAVAILABLE")
                        apkRepository.updateAppStatus(app.packageName, AppStatus.UNAVAILABLE)
                        unavailableCount++
                        continue
                    }
                    
                    try {
                        // Use UUID if available, otherwise use package name
                        val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                        val packageName = if (uuid == null) app.packageName else null

                        // First, get app details to check if versions are now available
                        val detailsResult = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)
                        
                        if (detailsResult is ApiResult.Success) {
                            val apkDetails = detailsResult.data

                            // Check if versions are now available
                            if (apkDetails.versions.isEmpty()) {
                                Log.d(TAG, "${app.packageName}: Still no versions available, will try again later")
                                // Increment retry count
                                apkRepository.incrementRetryCount(app.packageName)
                                stillPendingCount++
                                continue
                            }

                            Log.d(TAG, "${app.packageName}: Versions now available! Attempting download...")
                            
                            // Reset retry count since versions are now available
                            apkRepository.resetRetryCount(app.packageName)

                            // Get the latest version
                            val latestVersion = apkDetails.versions.first()

                            // Try to get download URL
                            val downloadResult = apkRepository.getDownloadUrl(
                                uuid = uuid,
                                packageName = packageName,
                                versionCode = latestVersion.versionCode
                            )
                            
                            if (downloadResult is ApiResult.Success) {
                                val downloadResponse = downloadResult.data

                                when {
                                    downloadResponse.type == "download" || 
                                    (downloadResponse.type == null && downloadResponse.url.isNotEmpty()) -> {
                                        // Success! Create download entry and start download
                                        Log.i(TAG, "${app.packageName}: Download URL obtained, starting download")

                                        val download = Download.fromApkDetails(
                                            apkDetails,
                                            isInstalled = false,
                                            isUpdate = false
                                        )

                                        // Update download URL
                                        val updatedDownload = download.copy(
                                            url = downloadResponse.url,
                                            md5 = downloadResponse.md5
                                        )

                                        // Save to database and update status to DOWNLOADING
                                        apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)
                                        apkRepository.saveDownloadToDb(updatedDownload)

                                        // Enqueue download worker
                                        val workRequest = androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
                                            .setInputData(
                                                androidx.work.workDataOf(
                                                    DownloadWorker.KEY_PACKAGE_NAME to app.packageName
                                                )
                                            )
                                            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                            .build()

                                                workManager.enqueueUniqueWork(
                                                    "download_${app.packageName}",
                                                    androidx.work.ExistingWorkPolicy.KEEP,
                                                    workRequest
                                                )

                                                successCount++
                                            }

                                            downloadResponse.type == "request" -> {
                                                Log.d(TAG, "${app.packageName}: Still being prepared on server")
                                                apkRepository.incrementRetryCount(app.packageName)
                                                stillPendingCount++
                                            }

                                            else -> {
                                                Log.w(TAG, "${app.packageName}: Unexpected download response")
                                                apkRepository.incrementRetryCount(app.packageName)
                                                stillPendingCount++
                                            }
                                        }
                            } else if (downloadResult is ApiResult.Error) {
                                // Check if it's a timeout - keep as REQUESTED and increment retry
                                val isTimeout = downloadResult.message.contains("timeout", ignoreCase = true)
                                if (isTimeout) {
                                    Log.d(TAG, "${app.packageName}: Timeout, will retry later")
                                    apkRepository.incrementRetryCount(app.packageName)
                                    stillPendingCount++
                                } else {
                                    Log.e(TAG, "${app.packageName}: Error getting download URL: ${downloadResult.message}")
                                    apkRepository.incrementRetryCount(app.packageName)
                                    failureCount++
                                }
                            } else {
                                // ApiResult.Loading or other
                                stillPendingCount++
                            }
                        } else if (detailsResult is ApiResult.Error) {
                            Log.e(TAG, "${app.packageName}: Error getting details: ${detailsResult.message}")
                            // Check if it's 404 - app might have been removed, mark as UNAVAILABLE
                            if (detailsResult.message.contains("404", ignoreCase = true) ||
                                detailsResult.message.contains("not found", ignoreCase = true)
                            ) {
                                Log.w(TAG, "${app.packageName}: App not found (404), marking as UNAVAILABLE")
                                apkRepository.updateAppStatus(app.packageName, AppStatus.UNAVAILABLE)
                                unavailableCount++
                            } else {
                                // Other errors, increment retry
                                apkRepository.incrementRetryCount(app.packageName)
                                stillPendingCount++
                            }
                        } else {
                            // ApiResult.Loading or other
                            stillPendingCount++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "${app.packageName}: Exception during check", e)
                        apkRepository.incrementRetryCount(app.packageName)
                        failureCount++
                    }
                }

                Log.i(TAG, "Check completed: $successCount started, $stillPendingCount still pending, $failureCount failed, $unavailableCount unavailable")
                
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking requested apps", e)
                // Retry on failure
                Result.retry()
            }
        }
    }
}
