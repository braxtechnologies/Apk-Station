package com.brax.apkstation.presentation.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.di.NetworkModule
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferencesRepository: AppPreferencesRepository,
    private val apkRepository: ApkRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _debugMessage = MutableSharedFlow<String>()
    val debugMessage = _debugMessage.asSharedFlow()

    init {
        loadSettings()
        loadCurrentApiUrl()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            checkNotificationPermission()
            loadFavoritesEnabled()
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
                } catch (e: PackageManager.NameNotFoundException) {
                    _debugMessage.emit("❌ Signal not installed. Install it first or use 'Add Signal to DB' button.")
                    return@launch
                }
                
                // Use old version code to trigger update
                val fakeOldVersion = 100000
                
                when (val result = apkRepository.checkForUpdates(mapOf(signalPackageName to fakeOldVersion))) {
                    is Result.Success -> {
                        val updatesMap = result.data
                        
                        if (updatesMap.containsKey(signalPackageName)) {
                            val updateInfo = updatesMap[signalPackageName]!!
                            
                            // Ensure Signal is in database
                            var signalApp = apkRepository.getAppByPackageName(signalPackageName)
                            if (signalApp == null) {
                                val searchResult = apkRepository.searchApks("signal")
                                if (searchResult is Result.Success) {
                                    val signalPreview = searchResult.data.firstOrNull { it.packageName == signalPackageName }
                                    if (signalPreview != null) {
                                        // Use UUID if available, otherwise package name
                                        val uuid = signalPreview.uuid.takeIf { it.isNotEmpty() }
                                        val packageName = if (uuid == null) signalPreview.packageName else null
                                        
                                        val detailsResult = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)
                                        if (detailsResult is Result.Success) {
                                            apkRepository.saveApkDetailsToDb(detailsResult.data, AppStatus.INSTALLED)
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

    /**
     * DEBUG: Run update worker immediately
     */
    fun runUpdateWorker() {
        viewModelScope.launch {
            try {
                com.brax.apkstation.data.workers.UpdateWorker.runUpdateCheckNow(context)
                _debugMessage.emit("✅ Update worker started! Check Logcat for 'UpdateWorker'")
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error: ${e.message}")
            }
        }
    }

    /**
     * DEBUG: Clear SRV cache and re-resolve API URL on next request
     */
    fun refreshApiUrl() {
        viewModelScope.launch {
            try {
                // Clear both the interceptor cache and SRV resolver cache
                NetworkModule.DynamicBaseUrlHolder.clearCache()
                
                // Trigger a new resolution to update the UI
                val newApiUrl = SrvResolver.resolveApiUrl()
                _uiState.value = _uiState.value.copy(currentApiUrl = newApiUrl)
                
                _debugMessage.emit("✅ Cache cleared! API URL will be re-resolved on next request: $newApiUrl")
            } catch (e: Exception) {
                _debugMessage.emit("❌ Error refreshing API URL: ${e.message}")
            }
        }
    }
}

data class SettingsUiState(
    val notificationsEnabled: Boolean = false,
    val shouldRequestNotificationPermission: Boolean = false,
    val shouldOpenNotificationSettings: Boolean = false,
    val favoritesEnabled: Boolean = false,
    val currentApiUrl: String = "Resolving..."
)

