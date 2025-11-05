package com.brax.apkstation.app.android

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.brax.apkstation.data.workers.RequestedAppsCheckWorker
import com.brax.apkstation.data.workers.UpdateWorker
import com.brax.apkstation.utils.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class StoreApplication : Application() {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()

        // Initialize WorkManager with custom configuration
        val workManagerConfiguration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setWorkerFactory(workerFactory)
            .build()

        WorkManager.Companion.initialize(this, workManagerConfiguration)
        
        // Create notification channels
        NotificationHelper.createNotificationChannels(this)
        
        // Schedule periodic update checks (every 24 hours)
        UpdateWorker.schedulePeriodicUpdateCheck(this)
        
        // Schedule periodic requested apps check (every 5 minutes)
        RequestedAppsCheckWorker.schedulePeriodicCheck(this)
    }
}
