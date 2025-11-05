package com.brax.apkstation.presentation.ui.navigation

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
object PermissionScreen

@Serializable
object StoreLendingScreen

@Serializable
data class AppInfoScreen(val uuid: String)

@Serializable
data class ImageViewerScreen(
    val images: String, // Comma-separated image URLs
    val initialIndex: Int = 0
)

@Serializable
object SettingsScreen

@Serializable
data class CategoryAppsScreen(
    val categoryKey: String,
    val categoryName: String
)

/**
 * Models the navigation actions in the app.
 */
class AppNavigationActions(private val navController: NavController) {
    
    fun navigateToStoreLendingFromPermissionScreen() = navController.navigate(StoreLendingScreen) {
        popUpTo(PermissionScreen) { inclusive = true }
    }
    
    fun navigateToAppInfo(uuid: String) = navController.navigate(AppInfoScreen(uuid))
    
    fun navigateToImageViewer(images: List<String>, initialIndex: Int = 0) {
        val imagesString = images.joinToString(",")
        navController.navigate(ImageViewerScreen(imagesString, initialIndex))
    }
    
    fun navigateToSettings() = navController.navigate(SettingsScreen)
    
    fun navigateToCategoryApps(categoryKey: String, categoryName: String) = 
        navController.navigate(CategoryAppsScreen(categoryKey, categoryName))
    
    fun navigateBack() = navController.navigateUp()

    // Future use: Generic navigation to any destination
    // fun navigateTo(destination: Any) = navController.navigate(destination)
}
