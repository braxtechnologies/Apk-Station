package com.brax.apkstation.utils

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brax.apkstation.R
import com.brax.apkstation.app.host.MainActivity
import com.brax.apkstation.data.room.entity.DBApplication

object NotificationHelper {
    
    private const val TAG = "NotificationHelper"
    
    // Notification channels
    private const val CHANNEL_APP_UPDATES = "app_updates_channel"
    private const val CHANNEL_INSTALL_STATUS = "install_status_channel"
    
    // Notification IDs
    private const val NOTIFICATION_ID_APP_UPDATES = 1001
    
    // Intent extras
    const val EXTRA_OPEN_APP_INFO = "extra_open_app_info"
    const val EXTRA_APP_UUID = "extra_app_uuid"
    const val EXTRA_OPEN_LENDING_SCREEN = "extra_open_lending_screen"
    
    /**
     * Create notification channels (required for Android O and above)
     */
    fun createNotificationChannels(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // App Updates Channel
        val updatesChannel = NotificationChannel(
            CHANNEL_APP_UPDATES,
            "App Updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about available app updates"
            enableLights(true)
            enableVibration(true)
        }
        
        // Install Status Channel
        val installStatusChannel = NotificationChannel(
            CHANNEL_INSTALL_STATUS,
            "Installation Status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications about app installation status"
            enableLights(false)
            enableVibration(false)
        }

        notificationManager.createNotificationChannel(updatesChannel)
        notificationManager.createNotificationChannel(installStatusChannel)
    }
    
    /**
     * Show notification for available app updates
     * 
     * @param context Application context
     * @param appsWithUpdates List of apps that have updates available
     */
    fun showUpdateNotification(context: Context, appsWithUpdates: List<DBApplication>) {
        if (appsWithUpdates.isEmpty()) {
            Log.w(TAG, "appsWithUpdates is empty, returning without showing notification")
            return
        }
        
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                Log.e(TAG, "❌ POST_NOTIFICATIONS permission NOT granted! Notification will not appear.")
                Log.e(TAG, "Please grant notification permission in Settings -> Apps -> Apk Station -> Notifications")
                return
            }
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Check if notifications are enabled for the app
        if (!notificationManager.areNotificationsEnabled()) {
            Log.e(TAG, "❌ Notifications are disabled for Apk Station in system settings!")
            Log.e(TAG, "Please enable notifications in Settings -> Apps -> Apk Station -> Notifications")
            return
        }
        
        val (title, text, intent) = if (appsWithUpdates.size == 1) {
            // Single app update
            val app = appsWithUpdates.first()
            val title = "${app.name} has an update available"
            val text = "Tap to view details and update"
            
            // Intent to open AppInfoScreen for this specific app
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_APP_INFO, true)
                putExtra(EXTRA_APP_UUID, app.uuid)
            }
            
            Triple(title, text, intent)
        } else {
            // Multiple apps update
            val title = "${appsWithUpdates.size} apps have pending updates"
            val text = "Tap to view and update apps"
            
            // Intent to open StoreLendingScreen (main screen with app list)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_LENDING_SCREEN, true)
            }
            
            Triple(title, text, intent)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification
        val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true) // Auto-dismiss when tapped
            .setContentIntent(pendingIntent)
            .apply {
                // For multiple apps, show the list in expanded view
                if (appsWithUpdates.size > 1) {
                    val inboxStyle = NotificationCompat.InboxStyle()
                        .setBigContentTitle("$title:")
                    
                    appsWithUpdates.take(5).forEach { app ->
                        inboxStyle.addLine(app.name)
                    }
                    
                    if (appsWithUpdates.size > 5) {
                        inboxStyle.addLine("and ${appsWithUpdates.size - 5} more...")
                    }
                    
                    setStyle(inboxStyle)
                }
            }
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_APP_UPDATES, notification)
    }
    
    /**
     * Cancel the update notification
     */
    fun cancelUpdateNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_APP_UPDATES)
    }
    
    /**
     * Create a notification for successful installation
     */
    fun createInstallSuccessNotification(
        context: Context,
        appName: String
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, CHANNEL_INSTALL_STATUS)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle("Installation Successful")
            .setContentText("$appName has been installed")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
