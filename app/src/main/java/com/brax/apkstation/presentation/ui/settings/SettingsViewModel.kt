package com.brax.apkstation.presentation.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.network.dto.ApkVersionDto
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.workers.UpdateWorker
import com.brax.apkstation.di.NetworkModule
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Constants
import com.brax.apkstation.utils.NotificationHelper
import com.brax.apkstation.utils.Result
import com.brax.apkstation.utils.SrvResolver
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@Suppress("MaxLineLength", "TooManyFunctions")
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val apkRepository: ApkRepository,
    private val downloadHelper: com.brax.apkstation.data.helper.DownloadHelper,
    private val updateHelper: com.brax.apkstation.data.helper.UpdateHelper,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _debugMessage = MutableSharedFlow<String>()
    val debugMessage = _debugMessage.asSharedFlow()

    init {
        loadSettings()
        loadCurrentApiUrl()
        loadCacheSize()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            checkNotificationPermission()
            loadFavoritesEnabled()
            loadAutoUpdateSettings()
        }
    }
    
    private fun loadCacheSize() {
        viewModelScope.launch {
            val cacheSize = calculateCacheSize()
            _uiState.value = _uiState.value.copy(
                cacheSizeBytes = cacheSize,
                cacheSizeFormatted = formatFileSize(cacheSize)
            )
        }
    }
    
    private fun calculateCacheSize(): Long {
        val downloadDir = java.io.File(context.filesDir, "downloads")
        return if (downloadDir.exists()) {
            downloadDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else {
            0L
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.2f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun loadCurrentApiUrl() {
        viewModelScope.launch {
            // Try to get the cached URL from the interceptor first (fast)
            val cachedUrl = NetworkModule.DynamicBaseUrlHolder.getCurrentCachedUrl()
            
            if (cachedUrl != null) {
                // Already resolved and cached in the interceptor
                _uiState.value = _uiState.value.copy(currentApiUrl = cachedUrl)
            } else {
                // Not yet resolved (no API calls made yet), resolve now in background
                val apiUrl = SrvResolver.resolveApiUrl()
                _uiState.value = _uiState.value.copy(currentApiUrl = apiUrl)
            }
        }
    }

    private fun loadFavoritesEnabled() {
        viewModelScope.launch {
            appPreferencesRepository.getPreference(Constants.ENABLE_FAVORITES_KEY, false)
                .collect { enabled ->
                    _uiState.value = _uiState.value.copy(favoritesEnabled = enabled)
                }
        }
    }

    fun checkNotificationPermission() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // For Android < 13, check if notifications are enabled
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.areNotificationsEnabled()
        }
        
        _uiState.value = _uiState.value.copy(notificationsEnabled = hasPermission)
    }

    fun onNotificationToggleChanged(enabled: Boolean) {
        // Update UI state immediately for responsiveness
        _uiState.value = _uiState.value.copy(
            notificationsEnabled = enabled,
            shouldRequestNotificationPermission = enabled,
            shouldOpenNotificationSettings = !enabled
        )
    }

    fun onPermissionRequestHandled() {
        _uiState.value = _uiState.value.copy(
            shouldRequestNotificationPermission = false,
            shouldOpenNotificationSettings = false
        )
    }

    fun onFavoritesToggleChanged(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.savePreference(Constants.ENABLE_FAVORITES_KEY, enabled)
            _uiState.value = _uiState.value.copy(favoritesEnabled = enabled)
        }
    }
    
    // ========== AUTO-UPDATE SETTINGS ==========
    
    private fun loadAutoUpdateSettings() {
        viewModelScope.launch {
            // Load all auto-update settings
            appPreferencesRepository.getPreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_AUTO_UPDATE_CHECK,
                true
            ).collect { enabled ->
                _uiState.value = _uiState.value.copy(autoUpdateCheckEnabled = enabled)
            }
        }
        
        viewModelScope.launch {
            appPreferencesRepository.getPreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_UPDATE_ONLY_WIFI,
                true
            ).collect { wifiOnly ->
                _uiState.value = _uiState.value.copy(updateOnlyWifi = wifiOnly)
            }
        }
        
        viewModelScope.launch {
            appPreferencesRepository.getPreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_UPDATE_BATTERY_NOT_LOW,
                true
            ).collect { batteryNotLow ->
                _uiState.value = _uiState.value.copy(updateBatteryNotLow = batteryNotLow)
            }
        }
        
        viewModelScope.launch {
            appPreferencesRepository.getPreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_UPDATE_CHECK_INTERVAL,
                24L
            ).collect { interval ->
                _uiState.value = _uiState.value.copy(updateCheckIntervalHours = interval)
            }
        }
    }
    
    fun onAutoUpdateCheckToggled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.savePreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_AUTO_UPDATE_CHECK,
                enabled
            )
            _uiState.value = _uiState.value.copy(autoUpdateCheckEnabled = enabled)
            updateHelper.updateAutomatedCheck()
        }
    }
    
    fun onUpdateOnlyWifiToggled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.savePreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_UPDATE_ONLY_WIFI,
                enabled
            )
            _uiState.value = _uiState.value.copy(updateOnlyWifi = enabled)
            // Reschedule with new constraints if auto-update is enabled
            if (_uiState.value.autoUpdateCheckEnabled) {
                updateHelper.scheduleAutomatedCheck()
            }
        }
    }
    
    fun onUpdateBatteryNotLowToggled(enabled: Boolean) {
        viewModelScope.launch {
            appPreferencesRepository.savePreference(
                com.brax.apkstation.data.helper.UpdateHelper.PREFERENCE_UPDATE_BATTERY_NOT_LOW,
                enabled
            )
            _uiState.value = _uiState.value.copy(updateBatteryNotLow = enabled)
            // Reschedule with new constraints if auto-update is enabled
            if (_uiState.value.autoUpdateCheckEnabled) {
                updateHelper.scheduleAutomatedCheck()
            }
        }
    }

    // ========== DEBUG FUNCTIONS ==========

    /**
     * DEBUG: Add Signal app to database with old version code for testing
     */
    fun addSignalWithOldVersion() {
        viewModelScope.launch {
            try {
                val signalPackageName = "org.thoughtcrime.securesms"
                
                // Check if Signal is already in database
                val existingApp = apkRepository.getAppByPackageName(signalPackageName)
                if (existingApp != null) {
                    _debugMessage.emit("Signal already exists in database. Updating with old version...")
                }
                
                // Search for Signal in Lunr API
                when (val searchResult = apkRepository.searchApks("signal")) {
                    is Result.Success -> {
                        val signalPreview = searchResult.data.firstOrNull { it.packageName == signalPackageName }
                        
                        if (signalPreview != null) {
                            // Get full details (use UUID if available, otherwise package name)
                            val uuid = signalPreview.uuid.takeIf { it.isNotEmpty() }
                            val packageName = if (uuid == null) signalPreview.packageName else null
                            
                            when (val detailsResult = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)) {
                                is Result.Success -> {
                                    val apkDetails = detailsResult.data
                                    
                                    // Convert to DBApplication with old version
                                    val dbApp = apkRepository.apkDetailsToDatabaseModel(
                                        apkDetails,
                                        AppStatus.INSTALLED
                                    )
                                    
                                    // Set old version code to trigger update detection
                                    val oldVersionCode = 100000
                                    
                                    // Save to database
                                    apkRepository.saveAppToDatabase(detailsResult.data, AppStatus.INSTALLED)
                                    
                                    // Update with old version info
                                    apkRepository.updateVersionInfo(
                                        packageName = signalPackageName,
                                        latestVersionCode = dbApp.versionCode ?: oldVersionCode,
                                        hasUpdate = true
                                    )
                                    
                                    _debugMessage.emit("✅ Signal added to database with version $oldVersionCode (latest: ${dbApp.versionCode})")
                                }
                                is Result.Error -> {
                                    _debugMessage.emit("❌ Failed to get Signal details: ${detailsResult.message}")
                                }
                                else -> {
                                    _debugMessage.emit("❌ Unknown error getting Signal details")
                                }
                            }
                        } else {
                            _debugMessage.emit("❌ Signal not found in Lunr store")
                        }
                    }
                    is Result.Error -> {
                        _debugMessage.emit("❌ Search failed: ${searchResult.message}")
                    }
                    else -> {
                        _debugMessage.emit("❌ Unknown error during search")
                    }
                }
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error: ${e.message}")
            }
        }
    }

    /**
     * DEBUG: Test update notification by checking Signal with old version
     */
    fun testUpdateNotification() {
        viewModelScope.launch {
            try {
                val signalPackageName = "org.thoughtcrime.securesms"
                
                // Check if Signal is installed on device
                val installedVersionCode = try {
                    val packageInfo = context.packageManager.getPackageInfo(signalPackageName, 0)
                    packageInfo.longVersionCode.toInt()
                } catch (_: PackageManager.NameNotFoundException) {
                    _debugMessage.emit("❌ Signal not installed. Install it first or use 'Add Signal to DB' button.")
                    return@launch
                }
                
                // Use old version code to trigger update
                val fakeOldVersion = 100000
                
                when (val result = apkRepository.checkForUpdates(mapOf(signalPackageName to fakeOldVersion))) {
                    is Result.Success -> {
                        handleSuccessfulResult(result, signalPackageName, installedVersionCode)
                    }
                    is Result.Error -> {
                        _debugMessage.emit("❌ API error: ${result.message}")
                    }
                    else -> {
                        _debugMessage.emit("❌ Unknown error")
                    }
                }
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error: ${e.message}")
            }
        }
    }

    @Suppress("NestedBlockDepth") // Complex debug function with nested version checking
    private suspend fun handleSuccessfulResult(
        result: Result.Success<Map<String, ApkVersionDto>>,
        signalPackageName: String,
        installedVersionCode: Int
    ) {
        val updatesMap = result.data

        if (updatesMap.containsKey(signalPackageName)) {
            val updateInfo = updatesMap[signalPackageName]!!

            // Ensure Signal is in database
            var signalApp = apkRepository.getAppByPackageName(signalPackageName)
            if (signalApp == null) {
                val searchResult = apkRepository.searchApks("signal")
                if (searchResult is Result.Success) {
                    val signalPreview =
                        searchResult.data.firstOrNull { it.packageName == signalPackageName }
                    if (signalPreview != null) {
                        // Use UUID if available, otherwise package name
                        val uuid = signalPreview.uuid.takeIf { it.isNotEmpty() }
                        val packageName = if (uuid == null) signalPreview.packageName else null

                        val detailsResult =
                            apkRepository.getApkDetails(uuid = uuid, packageName = packageName)
                        if (detailsResult is Result.Success) {
                            apkRepository.saveApkDetailsToDb(
                                detailsResult.data,
                                AppStatus.INSTALLED
                            )
                            signalApp = apkRepository.getAppByPackageName(signalPackageName)
                        }
                    }
                }
            }

            if (signalApp != null) {
                // Mark as having update
                apkRepository.updateVersionInfo(
                    packageName = signalPackageName,
                    latestVersionCode = updateInfo.versionCode,
                    hasUpdate = true
                )

                // Show notification
                val updatedApp = apkRepository.getAppByPackageName(signalPackageName)
                if (updatedApp != null) {
                    NotificationHelper.showUpdateNotification(context, listOf(updatedApp))
                    _debugMessage.emit("✅ Notification sent! Signal v$installedVersionCode → v${updateInfo.versionCode}")
                }
            } else {
                _debugMessage.emit("❌ Could not fetch Signal from API")
            }
        } else {
            _debugMessage.emit("⚠️ Signal is up to date (v$installedVersionCode)")
        }
    }

    /**
     * DEBUG: Run update worker immediately
     */
    fun runUpdateWorker() {
        viewModelScope.launch {
            try {
                UpdateWorker.runUpdateCheckNow(context)
                _debugMessage.emit("✅ Update worker started! Check Logcat for 'UpdateWorker'")
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error: ${e.message}")
            }
        }
    }

    /**
     * DEBUG: Clear SRV cache and re-resolve API URL
     */
    fun refreshApiUrl() {
        viewModelScope.launch {
            try {
                // Clear both the interceptor cache and SRV resolver cache
                NetworkModule.DynamicBaseUrlHolder.clearCache()
                
                // Trigger a new resolution to update the UI
                val newApiUrl = SrvResolver.resolveApiUrl()
                
                // Sync the resolved URL back to DynamicBaseUrlHolder to keep caches consistent
                NetworkModule.DynamicBaseUrlHolder.setResolvedUrl(newApiUrl)
                
                // Update UI with the resolved URL
                _uiState.value = _uiState.value.copy(currentApiUrl = newApiUrl)
                
                _debugMessage.emit("✅ API URL refreshed and resolved: $newApiUrl")
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error refreshing API URL: ${e.message}")
            }
        }
    }
    
    /**
     * Clear completed downloads (files that are successfully installed, failed, or cancelled)
     */
    fun clearCompletedDownloads() {
        viewModelScope.launch {
            try {
                downloadHelper.clearCompletedDownloads()
                _debugMessage.emit("Cleared completed downloads")
                loadCacheSize() // Refresh cache size
            } catch (e: Exception) {
                _debugMessage.emit("Failed to clear downloads: ${e.message}")
            }
        }
    }
    
    /**
     * Clear all downloads (including active ones)
     */
    fun clearAllCache() {
        viewModelScope.launch {
            try {
                downloadHelper.clearAllDownloads()
                _debugMessage.emit("Cleared all cached downloads")
                loadCacheSize() // Refresh cache size
            } catch (e: Exception) {
                _debugMessage.emit("Failed to clear cache: ${e.message}")
            }
        }
    }
}

data class SettingsUiState(
    val notificationsEnabled: Boolean = false,
    val shouldRequestNotificationPermission: Boolean = false,
    val shouldOpenNotificationSettings: Boolean = false,
    val favoritesEnabled: Boolean = false,
    val currentApiUrl: String = "Resolving...",
    val cacheSizeBytes: Long = 0L,
    val cacheSizeFormatted: String = "0 B",
    // Auto-update settings
    val autoUpdateCheckEnabled: Boolean = true,
    val updateOnlyWifi: Boolean = true,
    val updateBatteryNotLow: Boolean = true,
    val updateCheckIntervalHours: Long = 24L
)
