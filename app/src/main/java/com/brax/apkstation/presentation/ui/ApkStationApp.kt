package com.brax.apkstation.presentation.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.brax.apkstation.presentation.theme.ApkStationTheme
import com.brax.apkstation.presentation.ui.navigation.AppNavGraph

@Composable
fun ApkStationApp(
    initialAppUuid: String? = null,
    openLendingScreen: Boolean = false
) {
    val navController = rememberNavController()

    ApkStationTheme {
        AppNavGraph(
            navController = navController,
            initialAppUuid = initialAppUuid,
            openLendingScreen = openLendingScreen
        )
    }
}
