package com.brax.apkstation.presentation.ui.appinfo

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.network.dto.ApkDetailsDto
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Constants
import com.brax.apkstation.utils.Result
import com.brax.apkstation.utils.formatFileSize
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppInfoViewModel @Inject constructor(
    private val apkRepository: ApkRepository,
    private val downloadHelper: com.brax.apkstation.data.helper.DownloadHelper,
    appPreferencesRepository: AppPreferencesRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppInfoUiState())
    val uiState = _uiState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            AppInfoUiState()
        )
    
    val favoritesEnabled: StateFlow<Boolean> = appPreferencesRepository
        .getPreference(Constants.ENABLE_FAVORITES_KEY, false)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            false
        )

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Cache the full APK details to avoid redundant API calls
    private var cachedApkDetails: ApkDetailsDto? = null
    
    init {
        // Observe installation events for immediate feedback
        observeInstallationEvents()
        
        // Observe download state
        observeDownloadState()
        
        // Observe database changes
        observeDatabaseChanges()
    }
    
    /**
     * Observe installation events for immediate UI feedback and errors
     */
    private fun observeInstallationEvents() {
        viewModelScope.launch {
            com.brax.apkstation.app.android.StoreApplication.events.installerEvent.collect { event ->
                val currentPackageName = _uiState.value.appDetails?.packageName ?: return@collect
                
                if (event.packageName == currentPackageName) {
                    when (event) {
                        is com.brax.apkstation.data.event.InstallerEvent.Installing -> {
                            updateAppStatus(AppStatus.INSTALLING)
                        }
                        is com.brax.apkstation.data.event.InstallerEvent.Failed -> {
                            handleInstallationFailed(event.packageName, event.error)
                        }
                        // Installed/Uninstalled handled by database observation
                        else -> {}
                    }
                }
            }
        }
    }
    
    /**
     * Observe database changes for this app
     * Room automatically notifies when AppStatusHelper updates the DB
     */
    private fun observeDatabaseChanges() {
        viewModelScope.launch {
            apkRepository.getAllApplications().collect { dbApps ->
                val currentPackageName = _uiState.value.appDetails?.packageName ?: return@collect
                val dbApp = dbApps.find { it.packageName == currentPackageName }
                
                // Get current installation info
                val installedVersionInfo = getInstalledVersionInfo(currentPackageName)
                
                if (dbApp != null) {
                    // App in database - use DB status and hasUpdate
                    val status = getActualAppStatus(currentPackageName, dbApp.hasUpdate)
                    
                    _uiState.update { state ->
                        state.appDetails?.let { app ->
                            state.copy(
                                appDetails = app.copy(
                                    status = status,
                                    installedVersion = installedVersionInfo?.first,
                                    installedVersionCode = installedVersionInfo?.second,
                                    hasUpdate = dbApp.hasUpdate,
                                    latestVersionCode = dbApp.latestVersionCode
                                )
                            )
                        } ?: state
                    }
                } else {
                    // App was removed from database (uninstalled non-favorite)
                    // Check actual status from PackageManager
                    val status = getActualAppStatus(currentPackageName, false)
                    
                    _uiState.update { state ->
                        state.appDetails?.let { app ->
                            state.copy(
                                appDetails = app.copy(
                                    status = status,
                                    installedVersion = installedVersionInfo?.first,
                                    installedVersionCode = installedVersionInfo?.second,
                                    hasUpdate = false
                                )
                            )
                        } ?: state
                    }
                }
            }
        }
    }
    
    /**
     * Observe download state from DownloadHelper
     */
    private fun observeDownloadState() {
        viewModelScope.launch {
            downloadHelper.downloadsList.collect { downloads ->
                val currentPackageName = _uiState.value.appDetails?.packageName ?: return@collect
                val download = downloads.find { it.packageName == currentPackageName }
                
                download?.let {
                    val status = when (it.status) {
                        DownloadStatus.QUEUED,
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.VERIFYING -> AppStatus.DOWNLOADING
                        DownloadStatus.INSTALLING -> {
                            val hasUpdate = _uiState.value.appDetails?.hasUpdate ?: false
                            if (hasUpdate) AppStatus.UPDATING else AppStatus.INSTALLING
                        }
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED -> {
                            // Handled by dedicated error handling
                            return@let
                        }
                        else -> return@let
                    }
                    
                    updateAppStatus(status)
                }
            }
        }
    }
    
    /**
     * Update app status in UI
     */
    private fun updateAppStatus(newStatus: AppStatus) {
        _uiState.update { state ->
            state.appDetails?.let { app ->
                state.copy(appDetails = app.copy(status = newStatus))
            } ?: state
        }
    }

    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        
        // Unregister network callback
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore - callback may not be registered
            }
        }
    }

    /**
     * Load app details from repository using UUID or package name
     * 
     * @param uuid The unique identifier (UUID) of the application, or package name if UUID not available
     * @param packageName Optional package name to use if uuid is null/empty
     */
    fun loadAppDetails(uuid: String?, packageName: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // Determine if the identifier is a UUID or package name
                // UUIDs follow the pattern: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
                val isUuid = uuid?.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == true
                
                val actualUuid = if (isUuid) uuid else null
                val actualPackageName = if (isUuid) packageName else uuid
                
                Log.d("AppInfoViewModel", "loadAppDetails - identifier: $uuid, isUuid: $isUuid, actualUuid: $actualUuid, actualPackage: $actualPackageName")
                
                // Fetch details from API using UUID (preferred) or package name
                when (val result = apkRepository.getApkDetails(uuid = actualUuid, packageName = actualPackageName)) {
                    is Result.Success -> {
                        val details = result.data

                        // Cache the full details for later use (e.g., download)
                        cachedApkDetails = details

                        // Get version and file size from the latest version (from API)
                        val latestVersion = details.versions.firstOrNull()
                        val version = latestVersion?.version ?: details.version ?: "Unknown"
                        val versionCode = latestVersion?.versionCode ?: details.versionCode
                        val fileSize = latestVersion?.fileSize ?: details.fileSize

                        // Get installed version info (if app is installed)
                        val installedVersionInfo = getInstalledVersionInfo(details.packageName)
                        val installedVersionCode = installedVersionInfo?.second

                        // Calculate hasUpdate based on fresh data from API vs actual device
                        val hasUpdate = if (installedVersionCode != null && versionCode != null) {
                            installedVersionCode < versionCode
                        } else {
                            false
                        }

                        // Update database with latest version info if app is installed
                        if (installedVersionCode != null && versionCode != null) {
                            apkRepository.updateVersionInfo(
                                packageName = details.packageName,
                                latestVersionCode = versionCode,
                                hasUpdate = hasUpdate
                            )
                        }

                        // Get actual app status (check downloads and installation)
                        val actualStatus = getActualAppStatus(details.packageName, hasUpdate)

                        val appDetailsData = AppDetailsData(
                            uuid = details.uuid,
                            packageName = details.packageName,
                            name = details.name,
                            version = version,
                            versionCode = versionCode,
                            icon = details.icon,
                            author = details.author,
                            rating = details.rating?.takeIf { it.isNotBlank() }?.toFloatOrNull()?.toString(),
                            size = formatFileSize(fileSize),
                            contentRating = details.contentRating,
                            description = stripHtmlTags(details.description),
                            images = details.images,
                            status = actualStatus,
                            hasUpdate = hasUpdate,
                            latestVersionCode = versionCode,
                            installedVersion = installedVersionInfo?.first,
                            installedVersionCode = installedVersionCode
                        )
                        
                        _uiState.update { it.copy(appDetails = appDetailsData) }
                        
                        // Load favorite status
                        val isFavorite = apkRepository.isFavorite(details.packageName)
                        _uiState.update { it.copy(isFavorite = isFavorite) }
                    }

                    is Result.Error -> {
                        handleError(uuid, result)
                    }

                    is Result.Loading -> {
                        // Already handled by isLoading
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Failed to load app details") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Search apps in the database
     */
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }

        if (query.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }

        viewModelScope.launch {
            try {
                val apps = apkRepository.searchAppsInDb(query)
                _uiState.update { it.copy(searchResults = apps.map { app -> app.toAppDetailsData() }) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Search failed") }
            }
        }
    }

    /**
     * Expand search view
     */
    fun expandSearch() {
        _uiState.update {
            it.copy(
                isSearchExpanded = true,
                searchQuery = "",
                searchResults = emptyList()
            )
        }
    }

    /**
     * Collapse search view
     */
    fun collapseSearch() {
        _uiState.update {
            it.copy(
                isSearchExpanded = false,
                searchQuery = "",
                searchResults = emptyList()
            )
        }
    }

    /**
     * Download and install app from Lunr API
     */
    fun installApp() {
        viewModelScope.launch {
            _uiState.value.appDetails?.let { app ->
                if (!_uiState.value.isConnected) {
                    _uiState.update { it.copy(errorMessage = "Network connection unavailable") }
                    return@launch
                }

                try {
                    val apkDetails = getApkDetailsForDownload(app)

                    // Create download entry and save to database
                    createAndSaveDownload(app, apkDetails)

                    // Enqueue download via DownloadHelper - it handles everything
                    val download = Download.fromApkDetails(apkDetails, false, app.hasUpdate)
                    downloadHelper.enqueueDownload(download)
                    
                    Log.i("AppInfoViewModel", "Enqueued download for ${app.packageName} via DownloadHelper")
                } catch (e: Exception) {
                    // Handle errors during setup
                    Log.e("AppInfoViewModel", "Failed to start download for ${app.packageName}", e)
                    
                    _uiState.update { state ->
                        state.appDetails?.let { details ->
                            state.copy(appDetails = details.copy(status = AppStatus.NOT_INSTALLED))
                        } ?: state
                    }
                    
                    val errorMsg = "Failed to start download: ${e.message}"
                    _uiState.update { it.copy(errorMessage = errorMsg) }
                }
            }
        }
    }

    private fun handleError(packageName: String?, result: Result.Error) {
        if (packageName == null) {
            Log.e("AppInfoViewModel", "Failed to load app details: ${result.message}")

            _uiState.update { it.copy(errorMessage = result.message) }
        } else {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val appInfo = packageInfo.applicationInfo

            if (appInfo == null) {
                _uiState.update { it.copy(errorMessage = result.message) }
            } else {
                val appName = packageManager.getApplicationLabel(appInfo).toString()

                val appDetailsData = AppDetailsData(
                    uuid = null,
                    packageName = appInfo.packageName,
                    name = appName,
                    version = packageInfo.versionName,
                    versionCode = packageInfo.longVersionCode.toInt(),
                    icon = null,
                    iconDrawable = appInfo.loadIcon(packageManager),
                    author = null,
                    rating = null,
                    size = null,
                    contentRating = null,
                    description = appInfo.loadDescription(packageManager)?.toString(),
                    images = null,
                    status = AppStatus.INSTALLED,
                    hasUpdate = false,
                    latestVersionCode = packageInfo.longVersionCode.toInt(),
                    installedVersion = packageInfo.versionName,
                    installedVersionCode = packageInfo.longVersionCode.toInt()
                )

                _uiState.update { it.copy(appDetails = appDetailsData) }
            }
        }
    }


    /**
     * Get APK details from cache or fetch from API
     * Uses UUID if available, otherwise falls back to package name
     */
    private suspend fun getApkDetailsForDownload(app: AppDetailsData): ApkDetailsDto {
        _uiState.update { state ->
            state.copy(appDetails = app.copy(status = AppStatus.DOWNLOADING))
        }

        return cachedApkDetails ?: run {
            // Use UUID if not empty/null, otherwise use package name
            val uuid = app.uuid?.takeIf { it.isNotEmpty() }
            val packageName = if (uuid == null) app.packageName else null
            
            when (val result = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)) {
                is Result.Success -> {
                    cachedApkDetails = result.data
                    result.data
                }

                is Result.Error -> {
                    _uiState.update { state ->
                        state.copy(appDetails = app.copy(status = AppStatus.NOT_INSTALLED))
                    }
                    _uiState.update { it.copy(errorMessage = "Failed to get download URL: ${result.message}") }
                    throw Exception(result.message)
                }

                else -> {
                    _uiState.update { state ->
                        state.copy(appDetails = app.copy(status = AppStatus.NOT_INSTALLED))
                    }
                    throw Exception("Failed to fetch APK details")
                }
            }
        }
    }


    /**
     * Create download entity and save to database
     * If versions is empty (app not cached), we create a minimal download entry
     * and the actual version info will be updated from the download response
     */
    private suspend fun createAndSaveDownload(
        app: AppDetailsData,
        apkDetails: ApkDetailsDto
    ) {
        val download = if (apkDetails.versions.isNotEmpty()) {
            // Normal case: versions available
            Download.fromApkDetails(apkDetails, false, app.hasUpdate)
        } else {
            // App not cached yet - create minimal download entry
            // Version info will be updated from download response
            Download(
                packageName = apkDetails.packageName,
                url = null, // Will be set from /download response
                version = "Unknown", // Will be updated from response
                versionCode = 0, // Will be updated from response
                isInstalled = false,
                isUpdate = app.hasUpdate,
                displayName = apkDetails.name,
                icon = apkDetails.icon,
                status = DownloadStatus.QUEUED,
                progress = 0,
                fileSize = 0L, // Unknown until download starts
                speed = 0L,
                timeRemaining = 0L,
                totalFiles = 1,
                fileType = "application/vnd.android.package-archive",
                downloadedFiles = 0,
                apkLocation = "",
                md5 = null
            )
        }

        apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)
        apkRepository.saveDownloadToDb(download)
    }


    /**
     * Handle installation complete event from EventFlow
     * Just refresh UI - AppStatusHelper already updated the database
     */
    fun handleInstallationComplete(packageName: String) {
        viewModelScope.launch {
            val app = _uiState.value.appDetails ?: return@launch

            // Remember if this was an update
            val wasUpdate = app.hasUpdate || app.status == AppStatus.UPDATING

            // Refresh app details from database (AppStatusHelper has updated it)
            val updatedApp = apkRepository.getAppByPackageName(packageName)

            // Get the newly installed version
            val installedVersionInfo = getInstalledVersionInfo(packageName)
            
            // Calculate actual status
            val finalStatus = getActualAppStatus(packageName, updatedApp?.hasUpdate ?: false)

            _uiState.update { state ->
                state.copy(
                    appDetails = app.copy(
                        status = finalStatus,
                        installedVersion = installedVersionInfo?.first,
                        installedVersionCode = installedVersionInfo?.second,
                        hasUpdate = updatedApp?.hasUpdate ?: false,
                        latestVersionCode = updatedApp?.latestVersionCode ?: app.versionCode
                    ),
                    errorMessage = if (wasUpdate) "Updated successfully!" else "Installation complete!"
                )
            }
        }
    }

    /**
     * Handle installation failure from EventFlow
     * Just refresh UI state, no database updates needed
     */
    fun handleInstallationFailed(packageName: String, errorMessage: String?) {
        viewModelScope.launch {
            val app = _uiState.value.appDetails ?: return@launch

            // Check if app is still installed (for update failures)
            val installedVersionInfo = getInstalledVersionInfo(packageName)
            val isStillInstalled = installedVersionInfo != null

            // Refresh app details from database
            val updatedApp = apkRepository.getAppByPackageName(packageName)
            val finalStatus = getActualAppStatus(packageName, updatedApp?.hasUpdate ?: false)
            
                _uiState.update { state ->
                    state.copy(
                        appDetails = app.copy(
                            status = finalStatus,
                            installedVersion = installedVersionInfo?.first,
                        installedVersionCode = installedVersionInfo?.second,
                        hasUpdate = updatedApp?.hasUpdate ?: false
                    )
                )
            }

            // Show error message if provided
            val message = errorMessage ?: "Installation failed"
            _uiState.update { it.copy(errorMessage = message) }
        }
    }

    /**
     * Create an intent to uninstall the app
     * Returns the uninstall intent that should be started by the UI
     * The UI will automatically refresh when returning from the uninstall dialog
     */
    fun createUninstallIntent(): android.content.Intent? {
        val app = _uiState.value.appDetails ?: return null

        return android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
            data = "package:${app.packageName}".toUri()
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Cancel download
     */
    fun cancelDownload() {
        viewModelScope.launch {
            _uiState.value.appDetails?.let { app ->
                try {
                    // Cancel via DownloadHelper - it handles everything
                    downloadHelper.cancel(app.packageName)
                    
                    _uiState.update { it.copy(errorMessage = "Download cancelled") }
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = "Failed to cancel download: ${e.message}") }
                }
            }
        }
    }

    /**
     * Start network connectivity monitoring
     */
    fun startNetworkMonitoring(context: Context) {
        connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (!_uiState.value.isConnected) {
                    _uiState.update {
                        it.copy(
                            isConnected = true,
                            errorMessage = "Network connection established"
                        )
                    }
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (_uiState.value.isConnected) {
                    _uiState.update {
                        it.copy(
                            isConnected = false,
                            errorMessage = "Network connection unavailable"
                        )
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                super.onCapabilitiesChanged(network, networkCapabilities)
                val hasInternetCapability =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternetCapability != _uiState.value.isConnected) {
                    _uiState.update {
                        it.copy(
                            isConnected = hasInternetCapability,
                            errorMessage = if (hasInternetCapability) "Network connection established"
                            else "Network connection unavailable"
                        )
                    }
                }
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback?.let {
            connectivityManager?.registerNetworkCallback(networkRequest, it)
        }
    }

    /**
     * Stop network connectivity monitoring
     */
    fun stopNetworkMonitoring() {
        networkCallback?.let {
            connectivityManager?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        connectivityManager = null
    }
    
    /**
     * Toggle favorite status for the current app
     */
    fun toggleFavorite() {
        viewModelScope.launch {
            _uiState.value.appDetails?.let { app ->
                val currentFavoriteStatus = _uiState.value.isFavorite
                val newFavoriteStatus = !currentFavoriteStatus
                
                try {
                    // Update in repository
                    apkRepository.toggleFavorite(app.packageName, newFavoriteStatus)
                    
                    // Update UI state
                    _uiState.update { it.copy(isFavorite = newFavoriteStatus) }
                    
                    // Show feedback
                    val message = if (newFavoriteStatus) {
                        "Added to favorites"
                    } else {
                        "Removed from favorites"
                    }
                    _uiState.update { it.copy(errorMessage = message) }
                } catch (e: Exception) {
                    Log.e("AppInfoViewModel", "Failed to toggle favorite", e)
                    _uiState.update { it.copy(errorMessage = "Failed to update favorite") }
                }
            }
        }
    }

    /**
     * Get installed version info from PackageManager
     * @return Pair of (versionName, versionCode) or null if not installed
     */
    private fun getInstalledVersionInfo(packageName: String): Pair<String, Int>? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }

            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = packageInfo.longVersionCode.toInt()

            Pair(versionName, versionCode)
        } catch (_: PackageManager.NameNotFoundException) {
            null // App not installed
        }
    }

    /**
     * Get actual app status by checking:
     * 1. If there's an active download
     * 2. If app is actually installed on device (via PackageManager)
     * 3. If app has special status in database (UNAVAILABLE)
     * 4. If an update is available
     */
    private suspend fun getActualAppStatus(
        packageName: String,
        hasUpdate: Boolean = false
    ): AppStatus {
        // Check if app is actually installed first
        val isInstalled = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        // Check for active downloads first (even if app is installed - could be an update)
        val download = apkRepository.getDownload(packageName)
        if (download != null) {
            return when (download.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.DOWNLOADED,
                DownloadStatus.VERIFYING -> AppStatus.DOWNLOADING

                DownloadStatus.INSTALLING -> if (hasUpdate) AppStatus.UPDATING else AppStatus.INSTALLING
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED -> {
                    // Failed/cancelled downloads should be cleaned up
                    apkRepository.deleteDownload(packageName)
                    // Return appropriate status based on installation state
                    if (isInstalled) {
                        if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                }

                else -> {
                    // Unknown status - clean up and return based on installation
                    if (isInstalled) {
                        if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                    } else {
                        AppStatus.NOT_INSTALLED
                    }
                }
            }
        }

        // Check database for special statuses (UNAVAILABLE)
        val dbApp = apkRepository.getAppByPackageName(packageName)
        when (dbApp?.status) {
            AppStatus.UNAVAILABLE -> return AppStatus.UNAVAILABLE
            else -> {
                // No special status - check installation status
                if (isInstalled) {
                    // Return UPDATE_AVAILABLE if update exists, otherwise INSTALLED
                    return if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                }

                // Not installed and no active download
                return AppStatus.NOT_INSTALLED
            }
        }
    }

    /**
     * Check if app is actually installed on the device
     */
    private fun checkIfInstalled(packageName: String): AppStatus {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            AppStatus.INSTALLED
        } catch (e: PackageManager.NameNotFoundException) {
            AppStatus.NOT_INSTALLED
        }
    }
}

/**
 * Strip HTML tags from description
 */
private fun stripHtmlTags(html: String?): String {
    if (html.isNullOrBlank()) return ""

    return html
        .replace("<div>", "")
        .replace("</div>", "")
        .replace("<h2>", "\n\n")
        .replace("</h2>", "\n")
        .replace("<h3>", "\n\n")
        .replace("</h3>", "\n")
        .replace("<p>", "\n")
        .replace("</p>", "")
        .replace("<ul>", "\n")
        .replace("</ul>", "")
        .replace("<li>", "\n• ")
        .replace("</li>", "")
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace(Regex("<a href=\"([^\"]+)\">([^<]+)</a>"), "$2 ($1)")
        .replace(Regex("<[^>]+>"), "") // Remove any remaining tags
        .replace(Regex("&nbsp;"), " ")
        .replace(Regex("&amp;"), "&")
        .replace(Regex("&lt;"), "<")
        .replace(Regex("&gt;"), ">")
        .replace(Regex("&quot;"), "\"")
        .replace(Regex("\n{3,}"), "\n\n") // Max 2 consecutive newlines
        .trim()
}

/**
 * Extension to convert DBApplication to AppDetailsData
 */
private fun com.brax.apkstation.data.room.entity.DBApplication.toAppDetailsData() = AppDetailsData(
    uuid = uuid ?: packageName, // Use UUID if available, fallback to packageName for legacy data
    packageName = packageName,
    name = name,
    version = version,
    versionCode = versionCode,
    icon = icon,
    author = author,
    rating = rating,
    size = size,
    contentRating = contentRating,
    description = description,
    images = images,
    status = status,
    hasUpdate = hasUpdate,
    latestVersionCode = latestVersionCode
)

/**
 * UI State for AppInfoScreen following MVI pattern
 */
@Immutable
data class AppInfoUiState(
    val appDetails: AppDetailsData? = null,
    val isLoading: Boolean = false,
    val isSearchExpanded: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<AppDetailsData> = emptyList(),
    val isConnected: Boolean = true,
    val isFavorite: Boolean = false,
    val errorMessage: String? = null
)

