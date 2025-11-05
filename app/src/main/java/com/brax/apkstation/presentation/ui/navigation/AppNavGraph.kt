package com.brax.apkstation.presentation.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.brax.apkstation.app.android.MainViewModel
import com.brax.apkstation.presentation.ui.appinfo.AppInfoScreen
import com.brax.apkstation.presentation.ui.categoryapps.CategoryAppsScreen
import com.brax.apkstation.presentation.ui.imageviewer.ImageViewerScreen
import com.brax.apkstation.presentation.ui.lending.StoreLendingScreen
import com.brax.apkstation.presentation.ui.permission.PermissionScreen
import com.brax.apkstation.presentation.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.first

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    mainViewModel: MainViewModel = hiltViewModel(),
    initialAppUuid: String? = null,
    openLendingScreen: Boolean = false
) {
    var startDestination by remember { mutableStateOf<Any?>(null) }
    val navigationActions = remember(navController) {
        AppNavigationActions(navController)
    }

    // Wait for the actual DataStore value before showing any screen
    LaunchedEffect(Unit) {
        val shouldShowPermissionScreen = mainViewModel.shouldShowPermissionScreen.first()
        startDestination = if (shouldShowPermissionScreen) PermissionScreen else StoreLendingScreen
    }
    
    // Handle notification navigation after start destination is set
    LaunchedEffect(startDestination, initialAppUuid) {
        if (startDestination != null && initialAppUuid != null) {
            // Navigate to specific app info screen from notification
            navigationActions.navigateToAppInfo(initialAppUuid)
        }
    }

    // Show loading while determining start destination
    if (startDestination == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        NavHost(
            navController = navController,
            startDestination = startDestination!!,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None }
        ) {
            composable<PermissionScreen> {
                PermissionScreen(navigationActions)
            }

            composable<StoreLendingScreen> {
                StoreLendingScreen(navigationActions)
            }

            composable<AppInfoScreen> { backStackEntry ->
                val appInfoScreen = backStackEntry.toRoute<AppInfoScreen>()
                AppInfoScreen(
                    uuid = appInfoScreen.uuid,
                    onNavigateBack = navigationActions::navigateBack,
                    onImageClick = { images, index ->
                        navigationActions.navigateToImageViewer(images, index)
                    }
                )
            }

            composable<ImageViewerScreen> { backStackEntry ->
                val imageViewerScreen = backStackEntry.toRoute<ImageViewerScreen>()
                val images = imageViewerScreen.images.split(",").filter { it.isNotBlank() }
                ImageViewerScreen(
                    images = images,
                    initialIndex = imageViewerScreen.initialIndex,
                    onNavigateBack = navigationActions::navigateBack
                )
            }

            composable<com.brax.apkstation.presentation.ui.navigation.SettingsScreen> {
                SettingsScreen(
                    onNavigateBack = navigationActions::navigateBack
                )
            }

            composable<com.brax.apkstation.presentation.ui.navigation.CategoryAppsScreen> {
                CategoryAppsScreen(
                    navigationActions = navigationActions
                )
            }
        }
    }
}
