package com.brax.apkstation.data.helper

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.workers.UpdateWorker
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class to manage app update checking
 * Inspired by AuroraStore's UpdateHelper but adapted for ApkStation
 */
@Singleton
class UpdateHelper @Inject constructor(
    private val storeDao: StoreDao,
    private val appPreferencesRepository: AppPreferencesRepository,
    @param:ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "UpdateHelper"
        private const val UPDATE_WORKER = "UPDATE_WORKER"
        private const val EXPEDITED_UPDATE_WORKER = "EXPEDITED_UPDATE_WORKER"
        
        // Preferences keys (DataStore)
        val PREFERENCE_AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val PREFERENCE_UPDATE_CHECK_INTERVAL = longPreferencesKey("update_check_interval")
        val PREFERENCE_UPDATE_ONLY_WIFI = booleanPreferencesKey("update_only_wifi")
        val PREFERENCE_UPDATE_BATTERY_NOT_LOW = booleanPreferencesKey("update_battery_not_low")
        
        // Default values
        private const val DEFAULT_UPDATE_INTERVAL_HOURS = 24L
    }

    /**
     * Flow of apps with updates available
     * Observes the database and emits list of apps with hasUpdate = true
     */
    val appsWithUpdates = storeDao.getApplicationsWithUpdates()
        .stateIn(StoreApplication.scope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * Flow to check if update check is currently running
     */
    val isCheckingUpdates = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(EXPEDITED_UPDATE_WORKER)
        .map { list -> !list.all { it.state.isFinished } }
        .stateIn(StoreApplication.scope, SharingStarted.WhileSubscribed(), false)

    /**
     * Initialize update helper
     * - Cleans up invalid updates
     * - Starts observing events
     */
    fun init() {
        StoreApplication.scope.launch {
            deleteInvalidUpdates()
        }.invokeOnCompletion {
            observeEvents()
        }
    }

    /**
     * Observe installer events to clean up update flags when apps are installed/uninstalled
     */
    private fun observeEvents() {
        StoreApplication.events.installerEvent.onEach { event ->
            when (event) {
                is InstallerEvent.Installed -> {
                    // App was installed, remove update flag
                    clearUpdateFlag(event.packageName)
                }
                is InstallerEvent.Uninstalled -> {
                    // App was uninstalled, remove update flag
                    clearUpdateFlag(event.packageName)
                }
                else -> {}
            }
        }.launchIn(StoreApplication.scope)
    }

    /**
     * Check for updates now (expedited)
     * Uses a one-time expedited worker to check for updates immediately
     */
    fun checkUpdatesNow() {
        Log.i(TAG, "Triggering immediate update check")
        
        val work = OneTimeWorkRequestBuilder<UpdateWorker>()
            .addTag(EXPEDITED_UPDATE_WORKER)
            .setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(EXPEDITED_UPDATE_WORKER, ExistingWorkPolicy.KEEP, work)
    }

    /**
     * Schedule periodic update checks based on user preferences
     */
    fun scheduleAutomatedCheck() {
        StoreApplication.scope.launch {
            if (!isAutoUpdateCheckEnabled()) {
                Log.i(TAG, "Auto update check is disabled, skipping schedule")
                return@launch
            }

            Log.i(TAG, "Scheduling periodic app update checks")
            
            val updateCheckInterval = appPreferencesRepository.getPreference(
                PREFERENCE_UPDATE_CHECK_INTERVAL,
                DEFAULT_UPDATE_INTERVAL_HOURS
            ).firstOrNull() ?: DEFAULT_UPDATE_INTERVAL_HOURS

            val wifiOnly = isWifiOnlyEnabled()
            val batteryNotLow = isBatteryNotLowEnabled()
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )

            if (batteryNotLow) {
                constraints.setRequiresBatteryNotLow(true)
            }

            val workRequest = PeriodicWorkRequestBuilder<UpdateWorker>(
                repeatInterval = updateCheckInterval,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1, // Allow 1 hour flex window
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setConstraints(constraints.build())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UPDATE_WORKER,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
        }
    }

    /**
     * Cancel scheduled periodic update checks
     */
    fun cancelAutomatedCheck() {
        Log.i(TAG, "Cancelling periodic app update checks")
        WorkManager.getInstance(context).cancelUniqueWork(UPDATE_WORKER)
    }

    /**
     * Update the automated check settings (call after user changes preferences)
     */
    suspend fun updateAutomatedCheck() {
        Log.i(TAG, "Updating periodic app update check settings")
        
        if (isAutoUpdateCheckEnabled()) {
            scheduleAutomatedCheck()
        } else {
            cancelAutomatedCheck()
        }
    }

    /**
     * Clear update flag for a specific package
     */
    private suspend fun clearUpdateFlag(packageName: String) {
        try {
            val app = storeDao.findApplicationByPackageName(packageName)
            if (app?.hasUpdate == true) {
                Log.i(TAG, "Clearing update flag for $packageName")
                storeDao.updateApplicationHasUpdate(packageName, false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear update flag for $packageName", e)
        }
    }

    /**
     * Clear all update flags
     */
    suspend fun clearAllUpdates() {
        try {
            Log.i(TAG, "Clearing all update flags")
            storeDao.clearAllUpdateFlags()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all update flags", e)
        }
    }

    /**
     * Delete invalid updates (apps that are no longer installed or are already up to date)
     */
    private suspend fun deleteInvalidUpdates() {
        try {
            val appsWithUpdates = storeDao.getApplicationsWithUpdatesNoFlow()
            
            appsWithUpdates.forEach { app ->
                try {
                    val packageInfo = context.packageManager.getPackageInfo(app.packageName, 0)
                    val installedVersion = packageInfo.longVersionCode.toInt()
                    
                    // If installed version >= latest version, clear update flag
                    if (installedVersion >= (app.latestVersionCode ?: 0)) {
                        Log.i(TAG, "Clearing stale update flag for ${app.packageName}")
                        storeDao.updateApplicationHasUpdate(app.packageName, false)
                    }
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    // App not installed, clear update flag
                    Log.i(TAG, "Clearing update flag for uninstalled app ${app.packageName}")
                    storeDao.updateApplicationHasUpdate(app.packageName, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up invalid updates", e)
        }
    }

    /**
     * Check if auto update check is enabled in preferences
     */
    private suspend fun isAutoUpdateCheckEnabled(): Boolean {
        return appPreferencesRepository.getPreference(
            PREFERENCE_AUTO_UPDATE_CHECK,
            true // Enabled by default
        ).firstOrNull() ?: true
    }

    /**
     * Check if wifi-only restriction is enabled
     */
    private suspend fun isWifiOnlyEnabled(): Boolean {
        return appPreferencesRepository.getPreference(
            PREFERENCE_UPDATE_ONLY_WIFI,
            true // Wifi-only by default
        ).firstOrNull() ?: true
    }

    /**
     * Check if battery not low restriction is enabled
     */
    private suspend fun isBatteryNotLowEnabled(): Boolean {
        return appPreferencesRepository.getPreference(
            PREFERENCE_UPDATE_BATTERY_NOT_LOW,
            true // Battery restriction enabled by default
        ).firstOrNull() ?: true
    }
}

