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
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.StoreDatabase
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
 */
@AndroidEntryPoint
class InstallStatusReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var database: StoreDatabase
    
    private val TAG = "InstallStatusReceiver"
    private val CHANNEL_ID = "install_channel"
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val downloadSessionId = intent.getLongExtra(EXTRA_DOWNLOAD_SESSION_ID, -1L)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        
        Log.i(TAG, "Installation status for $packageName: status=$status, installSessionId=$sessionId, downloadSessionId=$downloadSessionId, message=$message")
        
        scope.launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        handleSuccess(context, packageName, downloadSessionId)
                    }
                    
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        handlePendingUserAction(context, intent)
                    }
                    
                    else -> {
                        handleFailure(context, packageName, status, message)
                    }
                }
            } finally {
                // Clean up scope after handling
                scope.cancel()
            }
        }
    }
    
    private suspend fun handleSuccess(context: Context, packageName: String, downloadSessionId: Long) {
        Log.i(TAG, "Installation successful for $packageName with downloadSessionId: $downloadSessionId")
        
        // Get download info to check if it was an update
        val storeDao = database.storeDao()
        val download = storeDao.getDownload(packageName)
        val appName = download?.displayName ?: packageName
        val wasUpdate = download?.isUpdate == true
        
        Log.i(TAG, "Installation type for $packageName: wasUpdate=$wasUpdate, appName=$appName")
        
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
        Log.i(TAG, "Sending broadcast: $ACTION_INSTALLATION_STATUS_CHANGED for $packageName with sessionId: $downloadSessionId")
        context.sendBroadcast(broadcastIntent)
        Log.i(TAG, "Broadcast sent successfully")
        
        // Show success notification with app name and appropriate message
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
            packageName.hashCode()
        )
    }
    
    private fun handlePendingUserAction(context: Context, originalIntent: Intent) {
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
        status: Int,
        message: String?
    ) {
        Log.e(TAG, "Installation failed for $packageName: status=$status, message=$message")
        
        // Update database
        val storeDao = database.storeDao()
        storeDao.updateDownloadStatus(packageName, DownloadStatus.FAILED)
        
        // Get error message
        val error = getErrorString(context, status, message)
        
        // Broadcast installation failure for UI updates
        val broadcastIntent = Intent(ACTION_INSTALLATION_STATUS_CHANGED).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
            putExtra(EXTRA_INSTALL_SUCCESS, false)
            putExtra(EXTRA_ERROR_MESSAGE, error)
        }
        Log.i(TAG, "Sending failure broadcast: $ACTION_INSTALLATION_STATUS_CHANGED for $packageName")
        context.sendBroadcast(broadcastIntent)
        
        // Show error notification only if it wasn't cancelled by user
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            showNotification(
                context,
                "Installation Failed",
                error,
                packageName.hashCode()
            )
        } else {
            Log.i(TAG, "User cancelled installation - no notification shown")
        }
    }
    
    private fun getErrorString(context: Context, status: Int, message: String?): String {
        return when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled by user"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by system"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "App conflicts with existing installation"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "App is not compatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK file"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space"
            else -> message ?: "Installation failed (code: $status)"
        }
    }
    
    private fun showNotification(
        context: Context,
        title: String,
        message: String,
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

        // Build and show notification
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
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
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_ERROR_MESSAGE = "error_message"
    }
}
