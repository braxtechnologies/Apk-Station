package com.brax.apkstation.data.workers

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.utils.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * UpdateWorker - Periodic worker that checks for app updates every 24 hours
 * 
 * This worker:
 * 1. Gets all apps installed on the device
 * 2. Filters for apps that were installed via Apk Station (from database)
 * 3. Sends a batch request to /updates endpoint with all installed apps
 * 4. Updates the database with apps that have updates available
 * 5. Shows a notification if updates are found
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val storeDao: StoreDao,
    private val apkRepository: ApkRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "periodic_update_check"
        private const val ONE_TIME_WORK_NAME = "one_time_update_check"
        
        /**
         * Schedule periodic update checks (every 24 hours)
         */
        fun schedulePeriodicUpdateCheck(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1, // Allow execution within 1 hour window
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    workRequest
                )
        }
        
        /**
         * Run update check immediately (for testing purposes)
         */
        fun runUpdateCheckNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val workRequest = androidx.work.OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )
        }
        
        /**
         * Cancel scheduled update checks
         */
        fun cancelUpdateCheck(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
    
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Get all apps from Apk Station database
                val dbApps = storeDao.getAllApplicationsNoFlow()
                
                if (dbApps.isEmpty()) {
                    // No apps from Apk Station, nothing to check
                    return@withContext Result.success()
                }
                
                // Step 2: Get all installed apps from device and build version map
                val installedApps = getInstalledAppVersions(dbApps.map { it.packageName })
                
                if (installedApps.isEmpty()) {
                    // None of the Apk Station apps are installed anymore
                    return@withContext Result.success()
                }
                
                // Step 3: Call /updates endpoint with batch request
                when (val result = apkRepository.checkForUpdates(installedApps)) {
                    is com.brax.apkstation.utils.Result.Success -> {
                        val updatesMap = result.data
                        
                        // Step 4: Update database for each app
                        var updatesFound = 0
                        
                        for ((packageName, updateInfo) in updatesMap) {
                            try {
                                // Update is available for this app
                                storeDao.updateApplicationVersionInfo(
                                    packageName = packageName,
                                    latestVersionCode = updateInfo.versionCode,
                                    hasUpdate = true
                                )
                                updatesFound++
                            } catch (e: Exception) {
                                android.util.Log.e(TAG, "Error updating $packageName in database", e)
                            }
                        }
                        
                        // Step 5: Mark apps without updates as up-to-date
                        for (packageName in installedApps.keys) {
                            if (!updatesMap.containsKey(packageName)) {
                                try {
                                    // No update for this app, mark as up-to-date
                                    val installedVersion = installedApps[packageName] ?: 0
                                    storeDao.updateApplicationVersionInfo(
                                        packageName = packageName,
                                        latestVersionCode = installedVersion,
                                        hasUpdate = false
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e(TAG, "Error updating $packageName in database", e)
                                }
                            }
                        }
                        
                        // Step 6: Show notification if updates found
                        if (updatesFound > 0) {
                            showUpdateNotification()
                        }
                        
                        Result.success()
                    }
                    
                    is com.brax.apkstation.utils.Result.Error -> {
                        android.util.Log.e(TAG, "Update check failed: ${result.message}")
                        Result.retry()
                    }
                    
                    else -> Result.retry()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Update worker failed", e)
                Result.retry()
            }
        }
    }
    
    /**
     * Get version codes of installed apps from device
     * Only returns apps that are actually installed on the device
     * 
     * @param packageNames List of package names to check
     * @return Map of packageName to versionCode for installed apps
     */
    private fun getInstalledAppVersions(packageNames: List<String>): Map<String, Int> {
        val installedApps = mutableMapOf<String, Int>()
        
        for (packageName in packageNames) {
            try {
                val packageInfo: PackageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(packageName, 0)
                }
                
                val versionCode = packageInfo.longVersionCode.toInt()
                installedApps[packageName] = versionCode
            } catch (e: PackageManager.NameNotFoundException) {
                // App not installed, skip it (this is expected behavior)
                android.util.Log.d(TAG, "Package not found: $packageName", e)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error getting version for $packageName", e)
            }
        }
        
        return installedApps
    }
    
    /**
     * Show notification for available updates
     * Queries the database for apps with hasUpdate=true and shows notification
     */
    private suspend fun showUpdateNotification() {
        try {
            val appsWithUpdates = storeDao.getApplicationsWithUpdatesNoFlow()
            
            if (appsWithUpdates.isNotEmpty()) {
                NotificationHelper.showUpdateNotification(context, appsWithUpdates)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error showing update notification", e)
        }
    }
}
