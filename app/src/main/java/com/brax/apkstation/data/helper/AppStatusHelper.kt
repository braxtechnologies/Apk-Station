package com.brax.apkstation.data.helper

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.CommonUtils.TAG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central helper for managing app installation status
 * Listens to InstallerEvents and updates database + cache accordingly
 */
@Singleton
class AppStatusHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val storeDao: StoreDao
) {

    fun init() {
        observeInstallerEvents()
    }

    /**
     * Observe installer events and update database
     */
    private fun observeInstallerEvents() {
        StoreApplication.events.installerEvent.onEach { event ->
            when (event) {
                is InstallerEvent.Installed -> handleAppInstalled(event.packageName)
                is InstallerEvent.Uninstalled -> handleAppUninstalled(event.packageName)
                else -> {} // Other events handled elsewhere
            }
        }.launchIn(StoreApplication.scope)
    }

    /**
     * Handle app installation - update database with installed version info and status
     */
    private fun handleAppInstalled(packageName: String) {
        StoreApplication.scope.launch {
            try {
                Log.i(TAG, "Handling installation for $packageName")
                
                // Get installed version from PackageManager
                val installedVersionCode = try {
                    context.packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Package $packageName not found after installation")
                    return@launch
                }
                
                // Get app from database
                val app = storeDao.findApplicationByPackageName(packageName)
                
                if (app != null) {
                    // Check if we still have an update available
                    val stillHasUpdate = (app.latestVersionCode ?: 0) > installedVersionCode
                    
                    // Determine status
                    val newStatus = if (stillHasUpdate) {
                        com.brax.apkstation.presentation.ui.lending.AppStatus.UPDATE_AVAILABLE
                    } else {
                        com.brax.apkstation.presentation.ui.lending.AppStatus.INSTALLED
                    }
                    
                    // Update database with version info and status in one query
                    storeDao.updateApplicationInstallStatus(
                        packageName = packageName,
                        status = newStatus,
                        latestVersionCode = app.latestVersionCode ?: 0,
                        hasUpdate = stillHasUpdate
                    )
                    
                    Log.i(TAG, "Updated $packageName in DB: status=$newStatus, installedVersion=$installedVersionCode, latestVersion=${app.latestVersionCode}, hasUpdate=$stillHasUpdate")
                } else {
                    Log.d(TAG, "App $packageName not in database, skipping update")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling installation for $packageName", e)
            }
        }
    }

    /**
     * Handle app uninstallation
     * - If app is favorited: Keep in DB with NOT_INSTALLED status
     * - If not favorited: Remove from DB completely
     */
    private fun handleAppUninstalled(packageName: String) {
        StoreApplication.scope.launch {
            try {
                Log.i(TAG, "Handling uninstallation for $packageName")
                
                // Get current app data
                val app = storeDao.findApplicationByPackageName(packageName)
                
                if (app != null) {
                    if (app.isFavorite) {
                        // Keep favorited apps in database with NOT_INSTALLED status
                        storeDao.updateApplicationInstallStatus(
                            packageName = packageName,
                            status = AppStatus.NOT_INSTALLED,
                            latestVersionCode = app.latestVersionCode ?: 0,
                            hasUpdate = false
                        )
                        Log.i(TAG, "Updated favorited app $packageName to NOT_INSTALLED")
                    } else {
                        // Remove non-favorited apps from database
                        storeDao.deleteApplication(packageName)
                        Log.i(TAG, "Removed non-favorited app $packageName from database")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling uninstallation for $packageName", e)
            }
        }
    }
}
