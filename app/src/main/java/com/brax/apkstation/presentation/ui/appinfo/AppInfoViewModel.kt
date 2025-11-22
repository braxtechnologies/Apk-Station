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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.network.dto.ApkDetailsDto
import com.brax.apkstation.data.network.dto.DownloadResponseDto
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.data.workers.DownloadWorker
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker.Companion.KEY_PACKAGE_NAME
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker.Companion.KEY_SESSION_ID
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker.Companion.KEY_UUID
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker.Companion.KEY_VERSION_CODE
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.utils.Constants
import com.brax.apkstation.utils.Result
import com.brax.apkstation.utils.formatFileSize
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
    private val workManager: WorkManager,
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

    // Track the monitoring coroutine so we can cancel it
    private var monitoringJob: Job? = null

    // Track current download session ID to ignore phantom installations from old sessions
    // Map of packageName -> CURRENT sessionId (only the latest is valid)
    private val downloadSessions = mutableMapOf<String, Long>()
    private var sessionIdCounter = 0L

    // Track which sessions have been consumed (to prevent reuse)
    private val consumedSessions = mutableSetOf<Long>()

    // Track when we cancelled a download - ignore installations within X seconds after cancel
    private val cancelTimestamps = mutableMapOf<String, Long>()

    // Cache the full APK details to avoid redundant API calls
    private var cachedApkDetails: ApkDetailsDto? = null
    
    // Track active install job so we can handle cancellation properly
    private var installJob: Job? = null

    /**
     * Clean up resources when ViewModel is destroyed
     */
    override fun onCleared() {
        super.onCleared()
        
        // Cancel any active install job
        installJob?.cancel()
        
        // Note: We can't clean up downloads here because viewModelScope is already cancelled
        // The stale download cleanup in StoreLendingViewModel will handle this case
        Log.i("AppInfoViewModel", "ViewModel cleared - any in-progress downloads will be cleaned up by stale download cleanup")
        
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
        // Cancel any previous install job
        installJob?.cancel()
        
        // Start new install job and track it
        installJob = viewModelScope.launch {
            _uiState.value.appDetails?.let { app ->
                if (!_uiState.value.isConnected) {
                    _uiState.update { it.copy(errorMessage = "Network connection unavailable") }
                    return@launch
                }

                try {
                    val sessionInfo = prepareNewDownloadSession(app.packageName)
                    val apkDetails = getApkDetailsForDownload(app)

                    // Create download entry first (without URL)
                    createAndSaveDownload(app, apkDetails)

                    // Show message about potential wait time
                    Log.i("AppInfoViewModel", "Requesting download for ${app.packageName}. This may take up to 3 minutes if the app needs to be fetched from external source.")
                    
                    // Enqueue RequestDownloadUrlWorker to handle the potentially long-running request
                    // This runs in the background so the user can navigate away
                    val latestVersion = apkDetails.versions.firstOrNull()
                    val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                    
                    val requestWorkRequest = OneTimeWorkRequestBuilder<RequestDownloadUrlWorker>()
                        .setInputData(
                            workDataOf(
                                KEY_PACKAGE_NAME to app.packageName,
                                KEY_SESSION_ID to sessionInfo.newSessionId,
                                KEY_UUID to uuid,
                                KEY_VERSION_CODE to (latestVersion?.versionCode ?: -1)
                            )
                        )
                        .build()
                    
                    workManager.enqueueUniqueWork(
                        "request_download_${app.packageName}_session_${sessionInfo.newSessionId}",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        requestWorkRequest
                    )
                    
                    // Start monitoring the download process
                    startDownloadMonitoring(app.packageName)
                } catch (e: Exception) {
                    // Handle errors during setup
                    Log.e("AppInfoViewModel", "Failed to start download for ${app.packageName}", e)
                    
                    _uiState.update { state ->
                        state.appDetails?.let { details ->
                            state.copy(appDetails = details.copy(status = AppStatus.NOT_INSTALLED))
                        } ?: state
                    }
                    apkRepository.deleteDownload(app.packageName)
                    
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
     * Prepare a new download session by cleaning up old sessions
     */
    private suspend fun prepareNewDownloadSession(packageName: String): DownloadSessionInfo {
        cancelTimestamps.remove(packageName)

        val oldSessionId = downloadSessions[packageName]
        val newSessionId = ++sessionIdCounter
        downloadSessions[packageName] = newSessionId

        // Cancel previous monitoring job
        monitoringJob?.cancel()
        monitoringJob = null

        // Delete old download entry
        val existingDownload = apkRepository.getDownload(packageName)
        if (existingDownload != null) {
            apkRepository.deleteDownload(packageName)
        }

        // Cancel old WorkManager task
        if (oldSessionId != null) {
            val oldWorkName = "apkstation_download_session_$oldSessionId"
            workManager.cancelUniqueWork(oldWorkName)
        }

        delay(500) // Give WorkManager time to stop

        return DownloadSessionInfo(oldSessionId, newSessionId)
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
     * Fetch download URL from /download endpoint
     * Uses UUID if available, otherwise falls back to package name
     * Note: versionCode is optional - when versions array is empty (app not cached),
     * we call without versionCode and let the API fetch from external source
     */
    private suspend fun fetchDownloadUrl(
        app: AppDetailsData,
        apkDetails: ApkDetailsDto
    ): DownloadResponseDto {
        // Get latest version if available, null if versions array is empty
        val latestVersion = apkDetails.versions.firstOrNull()
        
        // If no versions available, we'll call /download without versionCode
        // This happens when app is not yet cached and needs to be fetched from external source
        if (latestVersion == null) {
            Log.d("AppInfoViewModel", "No versions available for ${app.packageName}, requesting from external source")
        }

        // Use UUID if not empty/null, otherwise use package name
        val uuid = app.uuid?.takeIf { it.isNotEmpty() }
        val packageName = if (uuid == null) app.packageName else null

        when (val result = apkRepository.getDownloadUrl(
            uuid = uuid,
            packageName = packageName,
            versionCode = latestVersion?.versionCode // null if no versions available
        )) {
            is Result.Success -> {
                return result.data
            }

            is Result.Error -> {
                throw Exception("Failed to get download URL: ${result.message}")
            }

            else -> {
                throw Exception("Failed to fetch download URL")
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
     * Enqueue download worker with session ID
     */
    private fun enqueueDownloadWorker(packageName: String, sessionId: Long) {
        val uniqueWorkName = "apkstation_download_session_$sessionId"

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_PACKAGE_NAME to packageName,
                    DownloadWorker.KEY_SESSION_ID to sessionId
                )
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Start monitoring download/install status
     */
    private fun startDownloadMonitoring(packageName: String) {
        monitoringJob = viewModelScope.launch {
            monitorDownloadStatus(packageName)
        }
    }

    /**
     * Monitor download status by polling the database
     */
    private suspend fun monitorDownloadStatus(packageName: String) {
        var lastStatus: AppStatus? = null
        var pollInterval = 500L

        while (true) {
            delay(pollInterval)

            val currentApp = _uiState.value.appDetails ?: break
            val download = apkRepository.getDownload(packageName)

            when {
                download == null -> {
                    if (handleDownloadDeleted(currentApp, lastStatus)) break
                    lastStatus =
                        if (currentApp.hasUpdate) AppStatus.UPDATING else AppStatus.INSTALLING
                    continue
                }

                download.status == DownloadStatus.DOWNLOADING ||
                        download.status == DownloadStatus.VERIFYING -> {
                    updateStatusIfChanged(
                        currentApp,
                        AppStatus.DOWNLOADING,
                        lastStatus
                    )?.let { lastStatus = it }
                }

                download.status == DownloadStatus.INSTALLING -> {
                    val newStatus =
                        if (currentApp.hasUpdate) AppStatus.UPDATING else AppStatus.INSTALLING
                    updateStatusIfChanged(currentApp, newStatus, lastStatus)?.let {
                        lastStatus = it
                        pollInterval = 250L
                    }
                }

                download.status == DownloadStatus.FAILED ||
                        download.status == DownloadStatus.CANCELLED -> {
                    handleDownloadFailedOrCancelled(currentApp, lastStatus)
                    break
                }
            }
        }
    }

    /**
     * Handle case when download entry is deleted (installation triggered or app marked as REQUESTED)
     */
    private suspend fun handleDownloadDeleted(
        currentApp: AppDetailsData,
        lastStatus: AppStatus?
    ): Boolean {
        if (currentApp.status == AppStatus.NOT_INSTALLED ||
            currentApp.status == AppStatus.UPDATE_AVAILABLE ||
            currentApp.status == AppStatus.INSTALLED
        ) {
            return true
        }

        // Check if app was marked as REQUESTED in the database
        val dbApp = apkRepository.getAppByPackageName(currentApp.packageName)
        if (dbApp?.status == AppStatus.REQUESTED) {
            Log.i("AppInfoViewModel", "App ${currentApp.packageName} marked as REQUESTED - stopping monitoring")
            _uiState.update { state ->
                state.copy(
                    appDetails = currentApp.copy(status = AppStatus.REQUESTED),
                    errorMessage = "App is being prepared from external source. It will be available in a few minutes."
                )
            }
            return true
        }

        val targetStatus = if (currentApp.hasUpdate) AppStatus.UPDATING else AppStatus.INSTALLING

        if (lastStatus != targetStatus) {
            _uiState.update { state ->
                state.copy(appDetails = currentApp.copy(status = targetStatus))
            }
        }

        delay(1000)

        val checkDownload = apkRepository.getDownload(currentApp.packageName)
        if (checkDownload?.status == DownloadStatus.FAILED) {
            _uiState.update { state ->
                state.copy(
                    appDetails = currentApp.copy(status = AppStatus.NOT_INSTALLED),
                    errorMessage = "Installation failed"
                )
            }
            return true
        }

        return false
    }

    /**
     * Update status if it changed
     */
    private fun updateStatusIfChanged(
        currentApp: AppDetailsData,
        newStatus: AppStatus,
        lastStatus: AppStatus?
    ): AppStatus? {
        return if (newStatus != lastStatus) {
            _uiState.update { state ->
                state.copy(appDetails = currentApp.copy(status = newStatus))
            }
            newStatus
        } else {
            null
        }
    }

    /**
     * Handle download failed or cancelled
     */
    private suspend fun handleDownloadFailedOrCancelled(
        currentApp: AppDetailsData,
        lastStatus: AppStatus?
    ) {
        val installedVersionInfo = getInstalledVersionInfo(currentApp.packageName)
        val isStillInstalled = installedVersionInfo != null

        if (isStillInstalled) {
            val installedVersionCode = installedVersionInfo?.second
            val latestVersionCode = currentApp.versionCode
            val stillHasUpdate = if (installedVersionCode != null && latestVersionCode != null) {
                installedVersionCode < latestVersionCode
            } else {
                false
            }

            val finalStatus =
                if (stillHasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
            _uiState.update { state ->
                state.copy(
                    appDetails = currentApp.copy(
                        status = finalStatus,
                        installedVersion = installedVersionInfo?.first,
                        installedVersionCode = installedVersionCode,
                        hasUpdate = stillHasUpdate,
                        latestVersionCode = latestVersionCode
                    )
                )
            }
        } else {
            _uiState.update { state ->
                state.copy(appDetails = currentApp.copy(status = AppStatus.NOT_INSTALLED))
            }
        }

        if (lastStatus != null) {
            _uiState.update { it.copy(errorMessage = "Installation failed or cancelled") }
        }
    }

    /**
     * Data class to hold session information
     */
    private data class DownloadSessionInfo(
        val oldSessionId: Long?,
        val newSessionId: Long
    )

    /**
     * Handle installation complete event from broadcast receiver
     * Called when InstallStatusReceiver confirms successful installation
     */
    fun handleInstallationComplete(packageName: String, sessionId: Long? = null) {
        viewModelScope.launch {
            val currentSessionId = downloadSessions[packageName]

            val app = _uiState.value.appDetails
            if (app?.packageName != packageName) {
                return@launch
            }

            // NUCLEAR OPTION: Check if we recently cancelled - ignore ALL installations within 3 seconds
            val cancelTime = cancelTimestamps[packageName]
            if (cancelTime != null) {
                val timeSinceCancel = System.currentTimeMillis() - cancelTime
                if (timeSinceCancel < 3000) {
                    return@launch
                } else {
                    // More than 3 seconds passed, clear the timestamp
                    cancelTimestamps.remove(packageName)
                }
            }

            // CRITICAL: Check session ID to ignore phantom installations from old downloads
            // Handle -1L as "no session id"
            val broadcastSession = if (sessionId == -1L) null else sessionId

            // Check if this session was already consumed
            if (broadcastSession != null && consumedSessions.contains(broadcastSession)) {
                return@launch
            }

            // Check if this is the current valid session
            if (broadcastSession != null && currentSessionId != null && broadcastSession != currentSessionId) {
                return@launch
            }

            // If no sessionId provided (from system broadcast), only accept if we don't have a current session
            if (broadcastSession != null) {
                // Mark this session as consumed to prevent any duplicates
                consumedSessions.add(broadcastSession)
                // Clear the current session
                downloadSessions.remove(packageName)
            }

            // Remember if this was an update or fresh install
            val wasUpdate = app.hasUpdate || app.status == AppStatus.UPDATING

            // Give PackageManager a moment to update
            delay(300)

            // Get the newly installed version
            val installedVersionInfo = getInstalledVersionInfo(packageName)
            val installedVersionCode = installedVersionInfo?.second
            val latestVersionCode = app.versionCode

            // Calculate if we still have an update available
            val stillHasUpdate = if (installedVersionCode != null && latestVersionCode != null) {
                installedVersionCode < latestVersionCode
            } else {
                false
            }

            // Update database with new version info
            if (latestVersionCode != null) {
                apkRepository.updateVersionInfo(
                    packageName = packageName,
                    latestVersionCode = latestVersionCode,
                    hasUpdate = stillHasUpdate
                )
            }

            val finalStatus =
                if (stillHasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED

            _uiState.update { state ->
                state.copy(
                    appDetails = app.copy(
                        status = finalStatus,
                        installedVersion = installedVersionInfo?.first,
                        installedVersionCode = installedVersionCode,
                        hasUpdate = stillHasUpdate,
                        latestVersionCode = latestVersionCode
                    ),
                    errorMessage = if (wasUpdate) "Updated successfully!" else "Installation complete!"
                )
            }
        }
    }

    /**
     * Handle installation failure (when user cancels or installation fails)
     */
    fun handleInstallationFailed(packageName: String, errorMessage: String?) {
        viewModelScope.launch {
            val app = _uiState.value.appDetails
            if (app == null || app.packageName != packageName) return@launch

            // Check if app is still installed (for update failures)
            val installedVersionInfo = getInstalledVersionInfo(packageName)
            val isStillInstalled = installedVersionInfo != null

            // Delete download from database
            apkRepository.deleteDownload(packageName)

            if (isStillInstalled) {
                // App is still installed - it was an update attempt that failed
                // Return to UPDATE_AVAILABLE state
                val latestVersionCode = app.versionCode
                val installedVersionCode = installedVersionInfo?.second
                val stillHasUpdate =
                    if (installedVersionCode != null && latestVersionCode != null) {
                        installedVersionCode < latestVersionCode
                    } else {
                        false
                    }

                // Update database with hasUpdate flag
                if (latestVersionCode != null) {
                    apkRepository.updateVersionInfo(packageName, latestVersionCode, stillHasUpdate)
                }

                val finalStatus =
                    if (stillHasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                _uiState.update { state ->
                    state.copy(
                        appDetails = app.copy(
                            status = finalStatus,
                            installedVersion = installedVersionInfo?.first,
                            installedVersionCode = installedVersionCode,
                            hasUpdate = stillHasUpdate,
                            latestVersionCode = latestVersionCode
                        )
                    )
                }
            } else {
                // App is not installed - fresh install attempt failed
                _uiState.update { state ->
                    state.copy(appDetails = app.copy(status = AppStatus.NOT_INSTALLED))
                }
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
                // CRITICAL: Invalidate the current session immediately!
                val oldSessionId = downloadSessions[app.packageName]
                if (oldSessionId != null) {
                    // Mark old session as consumed so it will be rejected if it completes
                    consumedSessions.add(oldSessionId)
                    // DON'T remove from downloadSessions - we need it for the next installApp() call!
                    // It will be naturally replaced when the next download starts.
                }

                // NUCLEAR OPTION: Record cancel timestamp - ignore ALL installations for 3 seconds
                cancelTimestamps[app.packageName] = System.currentTimeMillis()

                // CRITICAL: Cancel the monitoring job first
                monitoringJob?.cancel()
                monitoringJob = null

                // Mark download as CANCELLED so Worker knows to stop (don't delete yet)
                val existingDownload = apkRepository.getDownload(app.packageName)
                if (existingDownload != null) {
                    apkRepository.updateDownloadStatus(app.packageName, DownloadStatus.CANCELLED)
                }

                // Cancel work via WorkManager using the old sessionId
                if (oldSessionId != null) {
                    val oldWorkName = "apkstation_download_session_$oldSessionId"
                    workManager.cancelUniqueWork(oldWorkName)
                }

                // Give Worker time to see CANCELLED status and stop
                delay(500)

                // Now delete the cancelled download entry
                apkRepository.deleteDownload(app.packageName)

                // Check if app is still installed (for cancelled updates)
                val installedVersionInfo = getInstalledVersionInfo(app.packageName)
                val isStillInstalled = installedVersionInfo != null

                if (isStillInstalled) {
                    // App is still installed - this was an update download that was cancelled
                    // Return to UPDATE_AVAILABLE or INSTALLED state
                    val installedVersionCode = installedVersionInfo?.second
                    val latestVersionCode = app.versionCode
                    val stillHasUpdate =
                        if (installedVersionCode != null && latestVersionCode != null) {
                            installedVersionCode < latestVersionCode
                        } else {
                            false
                        }

                    // Update database with hasUpdate flag
                    if (latestVersionCode != null) {
                        apkRepository.updateVersionInfo(
                            app.packageName,
                            latestVersionCode,
                            stillHasUpdate
                        )
                    }

                    val finalStatus =
                        if (stillHasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                    _uiState.update { state ->
                        state.copy(
                            appDetails = app.copy(
                                status = finalStatus,
                                installedVersion = installedVersionInfo?.first,
                                installedVersionCode = installedVersionCode,
                                hasUpdate = stillHasUpdate,
                                latestVersionCode = latestVersionCode
                            )
                        )
                    }
                } else {
                    // App is not installed - fresh install download was cancelled
                    _uiState.update { state ->
                        state.copy(appDetails = app.copy(status = AppStatus.NOT_INSTALLED))
                    }
                }

                _uiState.update { it.copy(errorMessage = "Download cancelled") }
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
     * 3. If app is in REQUESTED state (from database)
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

        // Check database for special statuses (REQUESTED, UNAVAILABLE)
        val dbApp = apkRepository.getAppByPackageName(packageName)
        when (dbApp?.status) {
            AppStatus.REQUESTED -> return AppStatus.REQUESTED
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

