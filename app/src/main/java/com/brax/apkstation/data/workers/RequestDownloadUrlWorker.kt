package com.brax.apkstation.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Result
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker that requests the download URL from the API
 * This can take 2-3 minutes if the APK is not cached locally
 * Runs in the background so the user can navigate away
 */
@HiltWorker
class RequestDownloadUrlWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val apkRepository: ApkRepository,
    private val storeDao: StoreDao,
    private val workManager: WorkManager
) : CoroutineWorker(context, workerParams) {

    private val TAG = "RequestDownloadUrlWorker"

    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE_NAME) ?: return Result.failure()
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        val uuid = inputData.getString(KEY_UUID)
        val versionCode = inputData.getInt(KEY_VERSION_CODE, -1).takeIf { it != -1 }

        Log.i(TAG, "Requesting download URL for $packageName (session: $sessionId)")

        return withContext(Dispatchers.IO) {
            try {
                // Check if download still exists (not cancelled)
                val download = storeDao.getDownload(packageName)
                if (download == null) {
                    Log.i(TAG, "Download cancelled before request started for $packageName")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Download cancelled")
                    )
                }

                // Request download URL from API (this can take 2-3 minutes)
                Log.i(TAG, "Calling /download API for $packageName (may take up to 3 minutes)")
                
                return@withContext when (val result = apkRepository.getDownloadUrl(
                    uuid = uuid,
                    packageName = if (uuid == null) packageName else null,
                    versionCode = versionCode
                )) {
                    is com.brax.apkstation.utils.Result.Success -> {
                        val downloadResponse = result.data
                        
                        Log.i(TAG, "Received download response for $packageName: type=${downloadResponse.type}, hasUrl=${downloadResponse.url.isNotEmpty()}, md5=${downloadResponse.md5 ?: "NULL"}")

                        when {
                            // URL is ready - proceed with download
                            downloadResponse.type == "download" || 
                            (downloadResponse.type == null && downloadResponse.url.isNotEmpty()) -> {
                                
                                // Update download entry with URL
                                apkRepository.updateDownloadUrl(
                                    packageName,
                                    downloadResponse.url,
                                    downloadResponse.md5
                                )
                                
                                Log.i(TAG, "Download URL ready for $packageName (md5: ${downloadResponse.md5 ?: "not provided"}), enqueueing DownloadWorker")
                                
                                // Enqueue the actual download worker
                                val downloadWorkRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                                    .setInputData(
                                        workDataOf(
                                            DownloadWorker.KEY_PACKAGE_NAME to packageName,
                                            DownloadWorker.KEY_SESSION_ID to sessionId
                                        )
                                    )
                                    .build()
                                
                                workManager.enqueueUniqueWork(
                                    "download_${packageName}_session_$sessionId",
                                    androidx.work.ExistingWorkPolicy.REPLACE,
                                    downloadWorkRequest
                                )
                                
                                Result.success()
                            }
                            
                            // App needs to be fetched from external source
                            downloadResponse.type == "request" -> {
                                Log.i(TAG, "App $packageName needs to be fetched from external source - marking as REQUESTED")
                                
                                // Mark as REQUESTED
                                try {
                                    apkRepository.updateAppStatus(packageName, AppStatus.REQUESTED)
                                    Log.i(TAG, "Successfully marked $packageName as REQUESTED")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to update app status for $packageName", e)
                                }
                                
                                // Delete download entry
                                try {
                                    apkRepository.deleteDownload(packageName)
                                    Log.i(TAG, "Deleted download entry for $packageName")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to delete download for $packageName", e)
                                }
                                
                                Result.success(
                                    workDataOf(KEY_REQUESTED to true)
                                )
                            }
                            
                            else -> {
                                Log.e(TAG, "Invalid download response for $packageName")
                                apkRepository.deleteDownload(packageName)
                                Result.failure(
                                    workDataOf(KEY_ERROR to "Invalid download response")
                                )
                            }
                        }
                    }
                    
                    is com.brax.apkstation.utils.Result.Error -> {
                        Log.e(TAG, "Failed to get download URL for $packageName: ${result.message}")
                        
                        // Check if it's a timeout (message can say "timeout" or "timed out")
                        val isTimeout = result.message.contains("timeout", ignoreCase = true) || 
                                       result.message.contains("timed out", ignoreCase = true)
                        Log.d(TAG, "Timeout check for $packageName: message='${result.message}', isTimeout=$isTimeout")
                        
                        if (isTimeout) {
                            // Mark as REQUESTED for later retry
                            Log.i(TAG, "Timeout detected for $packageName - marking as REQUESTED")
                            try {
                                apkRepository.updateAppStatus(packageName, AppStatus.REQUESTED)
                                Log.i(TAG, "Successfully marked $packageName as REQUESTED after timeout")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to update app status for $packageName after timeout", e)
                            }
                            
                            try {
                                apkRepository.deleteDownload(packageName)
                                Log.i(TAG, "Deleted download entry for $packageName after timeout")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to delete download for $packageName after timeout", e)
                            }
                            
                            Result.success(
                                workDataOf(KEY_REQUESTED to true)
                            )
                        } else {
                            Log.e(TAG, "Non-timeout error for $packageName: ${result.message}")
                            apkRepository.deleteDownload(packageName)
                            Result.failure(
                                workDataOf(KEY_ERROR to result.message)
                            )
                        }
                    }
                    
                    else -> {
                        Log.e(TAG, "Unexpected result type for $packageName")
                        apkRepository.deleteDownload(packageName)
                        Result.failure(
                            workDataOf(KEY_ERROR to "Unexpected error")
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception while requesting download URL for $packageName", e)
                
                // Check if it's a timeout
                val isTimeout = e is java.net.SocketTimeoutException
                
                if (isTimeout) {
                    // Mark as REQUESTED for later retry
                    Log.i(TAG, "SocketTimeoutException for $packageName - marking as REQUESTED")
                    try {
                        apkRepository.updateAppStatus(packageName, AppStatus.REQUESTED)
                        Log.i(TAG, "Successfully marked $packageName as REQUESTED after exception")
                    } catch (updateError: Exception) {
                        Log.e(TAG, "Failed to update app status for $packageName after exception", updateError)
                    }
                    
                    try {
                        apkRepository.deleteDownload(packageName)
                        Log.i(TAG, "Deleted download entry for $packageName after exception")
                    } catch (deleteError: Exception) {
                        Log.e(TAG, "Failed to delete download for $packageName after exception", deleteError)
                    }
                    
                    Result.success(
                        workDataOf(KEY_REQUESTED to true)
                    )
                } else {
                    Log.e(TAG, "Non-timeout exception for $packageName: ${e.message}")
                    try {
                        apkRepository.deleteDownload(packageName)
                    } catch (deleteError: Exception) {
                        Log.e(TAG, "Failed to delete download for $packageName after non-timeout exception", deleteError)
                    }
                    Result.failure(
                        workDataOf(KEY_ERROR to (e.message ?: "Unknown error"))
                    )
                }
            }
        }
    }

    companion object {
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_UUID = "uuid"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_ERROR = "error"
        const val KEY_REQUESTED = "requested"
    }
}

