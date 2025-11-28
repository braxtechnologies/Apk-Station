package com.brax.apkstation.data.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.data.installer.base.InstallerBase
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.StoreDatabase
import com.brax.apkstation.utils.CommonUtils.TAG
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Receiver for handling installation status callbacks from PackageInstaller
 * Integrates with event system for reactive updates
 */
@AndroidEntryPoint
class InstallStatusReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var database: StoreDatabase

    private val CHANNEL_ID = "install_channel"
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: packageName
        val versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1L)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val downloadSessionId = intent.getLongExtra(EXTRA_DOWNLOAD_SESSION_ID, -1L)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        
        Log.i(TAG, "$packageName ($versionCode) sessionId=$sessionId, status=$status, message=$message")
        
        scope.launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        handleSuccess(context, packageName, displayName, downloadSessionId)
                    }
                    
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        handlePendingUserAction(context, intent)
                    }
                    
                    else -> {
                        handleFailure(context, packageName, displayName, status, message)
                    }
                }
            } finally {
                // Clean up scope after handling
                scope.cancel()
            }
        }
    }
    
    private suspend fun handleSuccess(
        context: Context, 
        packageName: String,
        displayName: String,
        downloadSessionId: Long
    ) {
        Log.i(TAG, "✅ Installation successful for $packageName")
        
        // Remove from enqueued installs
        StoreApplication.enqueuedInstalls.remove(packageName)
        
        // Get download info to check if it was an update
        val storeDao = database.storeDao()
        val download = storeDao.getDownload(packageName)
        val appName = download?.displayName ?: displayName
        val wasUpdate = download?.isUpdate == true
        
        // Notify installation success
        InstallerBase.notifyInstallation(context, appName, packageName)
        
        // Send event
        StoreApplication.events.send(InstallerEvent.Installed(packageName))
        
        // Update database
        storeDao.deleteDownload(packageName)
        
        // Delete downloaded files
        try {
            val downloadDir = File(context.filesDir, "downloads/$packageName")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
                Log.i(TAG, "Cleaned up downloads for $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean up downloads for $packageName", e)
        }
        
        // Broadcast installation success for UI updates with sessionId
        val broadcastIntent = Intent(ACTION_INSTALLATION_STATUS_CHANGED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_INSTALL_SUCCESS, true)
            putExtra(EXTRA_DOWNLOAD_SESSION_ID, downloadSessionId)
        }
        context.sendBroadcast(broadcastIntent)
        
        // Show success notification
        val title = if (wasUpdate) "Update Complete" else "Installation Complete"
        val message = if (wasUpdate) {
            "$appName updated successfully"
        } else {
            "$appName installed successfully"
        }
        
        showNotification(
            context,
            title,
            message,
            false,
            packageName.hashCode()
        )
    }
    
    private fun handlePendingUserAction(context: Context, originalIntent: Intent) {
        Log.i(TAG, "⏸️ User action required")
        
        // User action required, show the confirmation dialog
        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            originalIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            originalIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }
        
        confirmIntent?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }
    
    private suspend fun handleFailure(
        context: Context,
        packageName: String,
        displayName: String,
        status: Int,
        message: String?
    ) {
        Log.e(TAG, "❌ Installation failed for $packageName: status=$status, message=$message")
        
        // Remove from enqueued installs
        StoreApplication.enqueuedInstalls.remove(packageName)
        
        // Get error message
        val error = InstallerBase.getErrorString(context, status)
        
        // Send event
        StoreApplication.events.send(
            InstallerEvent.Failed(
                packageName,
                error,
                message
            )
        )
        
        // Update database
        val storeDao = database.storeDao()
        val download = storeDao.getDownload(packageName)
        
        // If user cancelled and file still exists, set back to COMPLETED so they can retry
        // For other failures, check if file exists before marking as FAILED
        if (download != null && download.apkLocation.isNotEmpty()) {
            val file = File(download.apkLocation)
            if (file.exists()) {
                // File still exists - set back to COMPLETED so user can retry installation
                Log.i(TAG, "File exists, setting status to COMPLETED for retry")
                storeDao.updateDownloadStatus(packageName, DownloadStatus.COMPLETED)
            } else {
                // File doesn't exist - mark as failed
                storeDao.updateDownloadStatus(packageName, DownloadStatus.FAILED)
            }
        } else {
            // No download entry or location - mark as failed
            storeDao.updateDownloadStatus(packageName, DownloadStatus.FAILED)
        }
        
        // Broadcast installation failure for UI updates
        val broadcastIntent = Intent(ACTION_INSTALLATION_STATUS_CHANGED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_INSTALL_SUCCESS, false)
            putExtra(EXTRA_ERROR_MESSAGE, error)
        }
        context.sendBroadcast(broadcastIntent)
        
        // Show error notification only if it wasn't cancelled by user
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            showNotification(
                context,
                "Installation Failed",
                "$displayName: $error",
                true,
                packageName.hashCode()
            )
        } else {
            Log.i(TAG, "User cancelled installation - no notification shown")
        }
    }
    
    private fun showNotification(
        context: Context,
        title: String,
        message: String,
        isError: Boolean = false,
        notificationId: Int
    ) {
        val notificationManager = context.getSystemService<NotificationManager>()
            ?: return
        
        // Create notification channel (Android 8.0+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Installation",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications for app installation status"
        }
        notificationManager.createNotificationChannel(channel)

        val smallIcon = if (isError) {
            android.R.drawable.stat_notify_error
        } else {
            com.brax.apkstation.R.drawable.ic_check_circle
        }

        // Build and show notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        notificationManager.notify(notificationId, notification)
    }
    
    companion object {
        const val ACTION_INSTALL_STATUS = "com.brax.apkstation.INSTALL_STATUS"
        const val ACTION_INSTALLATION_STATUS_CHANGED = "com.brax.apkstation.INSTALLATION_STATUS_CHANGED"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DOWNLOAD_SESSION_ID = "download_session_id"
        const val EXTRA_VERSION_CODE = "version_code"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }
}
