package com.brax.apkstation.app.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.brax.apkstation.app.android.MainViewModel
import com.brax.apkstation.presentation.ui.ApkStationApp
import com.brax.apkstation.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before calling super.onCreate()
        val splashScreen = installSplashScreen()

        // Keep the splash screen visible until ViewModel is ready
        splashScreen.setKeepOnScreenCondition {
            !mainViewModel.isReady.value
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract notification intent extras
        val openAppInfo =
            intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_APP_INFO, false) ?: false
        val appUuid = intent?.getStringExtra(NotificationHelper.EXTRA_APP_UUID)
        val openLendingScreen =
            intent?.getBooleanExtra(NotificationHelper.EXTRA_OPEN_LENDING_SCREEN, false) ?: false

        setContent {
            ApkStationApp(
                initialAppUuid = if (openAppInfo) appUuid else null,
                openLendingScreen = openLendingScreen
            )
        }
    }
}
