package com.brax.apkstation.app.android

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.util.Log.DEBUG
import android.util.Log.INFO
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.brax.apkstation.BuildConfig
import com.brax.apkstation.data.event.EventFlow
import com.brax.apkstation.data.helper.AppStatusHelper
import com.brax.apkstation.data.helper.DownloadHelper
import com.brax.apkstation.data.helper.UpdateHelper
import com.brax.apkstation.data.receiver.PackageManagerReceiver
import com.brax.apkstation.utils.CommonUtils
import com.brax.apkstation.utils.CommonUtils.TAG
import com.brax.apkstation.utils.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import javax.inject.Inject

/**
 * Application class for ApkStation
 * Manages global state and event flow
 */
@HiltAndroidApp
class StoreApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    @Inject
    lateinit var eventFlow: EventFlow
    
    @Inject
    lateinit var downloadHelper: DownloadHelper
    
    @Inject
    lateinit var updateHelper: UpdateHelper
    
    @Inject
    lateinit var appStatusHelper: AppStatusHelper

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) DEBUG else INFO)
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        /**
         * Main application scope for coroutines
         */
        var scope: CoroutineScope = MainScope()
            private set

        /**
         * Set of packages currently enqueued for installation
         */
        val enqueuedInstalls: MutableSet<String> = mutableSetOf()

        /**
         * Global event flow for app-wide events
         */
        lateinit var events: EventFlow
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize global event flow
        events = eventFlow
        
        // Create notification channels
        NotificationHelper.createNotificationChannels(this)
        
        // Initialize download helper to observe and trigger downloads
        downloadHelper.init()
        
        // Initialize update helper to observe events and clean up invalid updates
        updateHelper.init()
        
        // Schedule periodic update checks
        updateHelper.scheduleAutomatedCheck()
        
        // Initialize app status helper to sync DB with installations/uninstallations
        appStatusHelper.init()

        //Register broadcast receiver for package install/uninstall
        ContextCompat.registerReceiver(
            this,
            object : PackageManagerReceiver() {},
            getFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        CommonUtils.cleanupInstallationSessions(applicationContext)

        Log.i(TAG, "ApkStation initialized with event system and download management")
    }

    private fun getFilter(): IntentFilter {
        val filter = IntentFilter()
        filter.addDataScheme("package")
        @Suppress("DEPRECATION")
        filter.addAction(Intent.ACTION_PACKAGE_INSTALL)
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        return filter
    }
}
