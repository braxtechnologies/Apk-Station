package com.brax.apkstation.presentation.ui.categoryapps

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker
import com.brax.apkstation.presentation.ui.lending.AppItem
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.presentation.ui.navigation.CategoryAppsScreen
import com.brax.apkstation.utils.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryAppsViewState(
    val categoryKey: String = "",
    val categoryName: String = "",
    val apps: List<AppItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class CategoryAppsViewModel @Inject constructor(
    private val apkRepository: ApkRepository,
    private val workManager: WorkManager,
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val categoryAppsScreen = savedStateHandle.toRoute<CategoryAppsScreen>()
    
    // Track which packages are currently being monitored to prevent duplicates
    private val monitoringPackages = mutableSetOf<String>()
    
    private val _uiState = MutableStateFlow(
        CategoryAppsViewState(
            categoryKey = categoryAppsScreen.categoryKey,
            categoryName = categoryAppsScreen.categoryName,
            isLoading = true
        )
    )
    val uiState = _uiState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000L),
            CategoryAppsViewState(
                categoryKey = categoryAppsScreen.categoryKey,
                categoryName = categoryAppsScreen.categoryName,
                isLoading = true
            )
        )

    init {
        loadCategoryApps()
        // Clean up stale downloads on initialization
        cleanupStaleDownloads()
    }

    fun loadCategoryApps(isRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isRefresh) {
                _uiState.update { it.copy(isRefreshing = true, errorMessage = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            try {
                when (val result = apkRepository.fetchApkList(
                    category = _uiState.value.categoryKey,
                    sort = "requests",
                    limit = 100
                )) {
                    is Result.Success -> {
                        // Get installed packages
                        val installedPackages = try {
                            context.packageManager
                                .getInstalledPackages(0)
                                .map { it.packageName }
                                .toSet()
                        } catch (_: Exception) {
                            emptySet()
                        }

                        // Get all apps from DB
                        val appsFromDb = apkRepository.getAllAppsFromDbNoFlow()

                        // Map API response to AppItems
                        val appItems = result.data.map { apkDto ->
                            val appFromDb = appsFromDb.find { it.packageName == apkDto.packageName }
                            val isInstalled = installedPackages.contains(apkDto.packageName)
                            
                            val status = when {
                                appFromDb?.status == AppStatus.DOWNLOADING -> {
                                    // Resume monitoring if app is downloading
                                    monitorDownloadProgress(apkDto.packageName)
                                    AppStatus.DOWNLOADING
                                }
                                appFromDb?.status == AppStatus.INSTALLING -> {
                                    // Resume monitoring if app is installing
                                    monitorDownloadProgress(apkDto.packageName)
                                    AppStatus.INSTALLING
                                }
                                appFromDb?.status == AppStatus.REQUESTED -> AppStatus.REQUESTED
                                appFromDb?.status == AppStatus.UNAVAILABLE -> AppStatus.UNAVAILABLE
                                isInstalled -> {
                                    try {
                                        val installedVersion = context.packageManager
                                            .getPackageInfo(apkDto.packageName, 0)
                                            .versionCode
                                        if (installedVersion < apkDto.versionCode) {
                                            AppStatus.UPDATE_AVAILABLE
                                        } else {
                                            AppStatus.INSTALLED
                                        }
                                    } catch (_: PackageManager.NameNotFoundException) {
                                        AppStatus.NOT_INSTALLED
                                    }
                                }
                                else -> AppStatus.NOT_INSTALLED
                            }

                            AppItem(
                                uuid = apkDto.uuid,
                                packageName = apkDto.packageName,
                                name = apkDto.name,
                                version = apkDto.version,
                                icon = apkDto.icon,
                                author = apkDto.author,
                                rating = null,
                                size = apkDto.fileSize,
                                status = status,
                                hasUpdate = status == AppStatus.UPDATE_AVAILABLE,
                                category = apkDto.category ?: "Others"
                            )
                        }

                        _uiState.update {
                            it.copy(
                                apps = appItems,
                                isLoading = false,
                                isRefreshing = false
                            )
                        }
                    }

                    is Result.Error -> {
                        _uiState.update {
                            it.copy(
                                errorMessage = result.message,
                                isLoading = false,
                                isRefreshing = false
                            )
                        }
                    }

                    is Result.Loading -> {
                        // Already handled
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to load apps",
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            }
        }
    }

    fun onAppActionButtonClick(app: AppItem) {
        when (app.status) {
            AppStatus.INSTALLED -> {
                // Launch the installed app
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage(app.packageName)
                launchIntent?.let { context.startActivity(it) }
            }

            AppStatus.UPDATE_AVAILABLE,
            AppStatus.NOT_INSTALLED -> {
                // Install/update app
                installApp(app)
            }

            AppStatus.DOWNLOADING -> {
                // Cancel download
                cancelAppDownload(app)
            }

            else -> {
                // Do nothing for INSTALLING, REQUESTED, UNAVAILABLE, etc.
            }
        }
    }

    /**
     * Install app - fetch details and enqueue download
     * Uses UUID if available, otherwise falls back to package name
     * Handles apps not yet cached (empty versions array) by requesting from external source
     */
    private fun installApp(app: AppItem) {
        viewModelScope.launch {
            try {
                // Update UI to show downloading
                updateAppStatus(app.packageName, AppStatus.DOWNLOADING)

                // Fetch full app details to get download URL
                // Use UUID if not empty/null, otherwise use package name
                val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                val packageName = if (uuid == null) app.packageName else null

                when (val result = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)) {
                    is Result.Success -> {
                        val apkDetails = result.data
                        val latestVersion = apkDetails.versions.firstOrNull()

                        // Create download entry (URL will be filled by RequestDownloadUrlWorker)
                        val download = if (latestVersion != null) {
                            // Use companion method if version info is available
                            Download.fromApkDetails(
                                apkDetails,
                                isInstalled = false,
                                isUpdate = app.hasUpdate
                            )
                        } else {
                            // Fallback if no version info (rare case)
                            Download(
                                packageName = apkDetails.packageName,
                                url = null,
                                version = "Latest",
                                versionCode = 0,
                                isInstalled = false,
                                isUpdate = app.hasUpdate,
                                displayName = apkDetails.name,
                                icon = apkDetails.icon,
                                status = DownloadStatus.QUEUED,
                                progress = 0,
                                fileSize = 0L,
                                speed = 0L,
                                timeRemaining = 0L,
                                totalFiles = 1,
                                fileType = "application/vnd.android.package-archive",
                                downloadedFiles = 0,
                                apkLocation = "",
                                md5 = null
                            )
                        }

                        // Save to database
                        apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)
                        apkRepository.saveDownloadToDb(download)

                        // ALWAYS use RequestDownloadUrlWorker to fetch the download URL
                        // This worker calls /download endpoint and then enqueues DownloadWorker
                        val sessionId = System.currentTimeMillis()
                        val requestWorkRequest = OneTimeWorkRequestBuilder<RequestDownloadUrlWorker>()
                            .setInputData(
                                workDataOf(
                                    RequestDownloadUrlWorker.KEY_PACKAGE_NAME to app.packageName,
                                    RequestDownloadUrlWorker.KEY_SESSION_ID to sessionId,
                                    RequestDownloadUrlWorker.KEY_UUID to uuid,
                                    RequestDownloadUrlWorker.KEY_VERSION_CODE to (latestVersion?.versionCode ?: -1)
                                )
                            )
                            .addTag("request_${app.packageName}")
                            .build()

                        workManager.enqueueUniqueWork(
                            "request_download_${app.packageName}_session_$sessionId",
                            ExistingWorkPolicy.REPLACE,
                            requestWorkRequest
                        )

                        Log.d(
                            "CategoryAppsViewModel",
                            "Enqueued RequestDownloadUrlWorker for ${app.packageName} with versionCode: ${latestVersion?.versionCode ?: -1}"
                        )

                        // Monitor the download process
                        monitorDownloadProgress(app.packageName)
                    }

                    is Result.Error -> {
                        updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)
                        _uiState.update { it.copy(errorMessage = "Failed to get app details: ${result.message}") }
                    }

                    is Result.Loading -> {
                        // Already handled by status update
                    }
                }
            } catch (e: Exception) {
                // Handle errors during setup
                Log.e("CategoryAppsViewModel", "Failed to start download for ${app.packageName}", e)
                updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)
                _uiState.update { it.copy(errorMessage = "Failed to start download: ${e.message}") }
            }
        }
    }

    /**
     * Monitor download progress by polling the database
     */
    private fun monitorDownloadProgress(packageName: String) {
        // Prevent duplicate monitoring
        synchronized(monitoringPackages) {
            if (monitoringPackages.contains(packageName)) {
                return // Already monitoring this package
            }
            monitoringPackages.add(packageName)
        }

        viewModelScope.launch {
            try {
                var lastStatus: AppStatus? = null

                while (true) {
                    delay(500) // Check every 500ms

                    // Check if app is installed (ground truth)
                    val isInstalled = try {
                        context.packageManager.getPackageInfo(packageName, 0)
                        true
                    } catch (_: PackageManager.NameNotFoundException) {
                        false
                    }

                    if (isInstalled) {
                        // App is installed, clean up and exit
                        apkRepository.deleteDownload(packageName)

                        // Check if update is available
                        val dbApp = apkRepository.getAppByPackageName(packageName)
                        val hasUpdate = dbApp?.hasUpdate ?: false
                        val status = if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED

                        updateAppStatus(packageName, status, hasUpdate)
                        break
                    }

                    // Check download status from database
                    val download = apkRepository.getDownload(packageName)

                    // If download is null, check if app was marked as REQUESTED
                    if (download == null) {
                        val dbApp = apkRepository.getAppByPackageName(packageName)
                        if (dbApp?.status == AppStatus.REQUESTED) {
                            Log.i("CategoryAppsViewModel", "App $packageName marked as REQUESTED - stopping monitoring")
                            updateAppStatus(packageName, AppStatus.REQUESTED)
                        } else {
                            // Download entry doesn't exist, mark as not installed
                            updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                        }
                        break
                    }

                    // Check if download has a null URL and is in QUEUED state - this means it's waiting for RequestDownloadUrlWorker
                    if (download.url == null && download.status == DownloadStatus.QUEUED) {
                        // Check if there's an active RequestDownloadUrlWorker
                        val requestWorkInfo = workManager.getWorkInfosByTag("request_${packageName}").get()
                        val hasActiveRequestWorker = requestWorkInfo.any { info ->
                            info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                        }
                        
                        if (!hasActiveRequestWorker) {
                            // No active request worker and no URL - this is a stale/failed request
                            Log.w("CategoryAppsViewModel", "Download for $packageName has no URL and no active worker - cleaning up")
                            apkRepository.deleteDownload(packageName)
                            
                            // Check if app should be marked as REQUESTED or UNAVAILABLE
                            val dbApp = apkRepository.getAppByPackageName(packageName)
                            when (dbApp?.status) {
                                AppStatus.REQUESTED -> updateAppStatus(packageName, AppStatus.REQUESTED)
                                AppStatus.UNAVAILABLE -> updateAppStatus(packageName, AppStatus.UNAVAILABLE)
                                else -> updateAppStatus(packageName, AppStatus.NOT_INSTALLED)
                            }
                            break
                        }
                        // Otherwise, continue monitoring - worker is active
                    }

                    val newStatus = when (download.status) {
                        DownloadStatus.DOWNLOADING -> AppStatus.DOWNLOADING
                        DownloadStatus.VERIFYING -> AppStatus.DOWNLOADING
                        DownloadStatus.INSTALLING -> AppStatus.INSTALLING
                        DownloadStatus.FAILED -> AppStatus.NOT_INSTALLED
                        DownloadStatus.CANCELLED -> AppStatus.NOT_INSTALLED
                        else -> null
                    }

                    if (newStatus != null && newStatus != lastStatus) {
                        updateAppStatus(packageName, newStatus)
                        lastStatus = newStatus

                        // Exit if failed or cancelled
                        if (newStatus == AppStatus.NOT_INSTALLED) {
                            apkRepository.deleteDownload(packageName)
                            break
                        }
                    }
                }
            } finally {
                // Remove from monitoring set when done
                synchronized(monitoringPackages) {
                    monitoringPackages.remove(packageName)
                }
            }
        }
    }

    /**
     * Helper function to update a specific app's status in the UI
     */
    private fun updateAppStatus(
        packageName: String,
        newStatus: AppStatus,
        hasUpdate: Boolean? = null
    ) {
            _uiState.update { state ->
                state.copy(
                apps = state.apps.map { app ->
                    if (app.packageName == packageName) {
                        if (hasUpdate != null) {
                            app.copy(status = newStatus, hasUpdate = hasUpdate)
                        } else {
                            app.copy(status = newStatus)
                        }
                    } else {
                        app
                    }
                }
            )
        }
    }

    /**
     * Cancel app download
     */
    private fun cancelAppDownload(app: AppItem) {
        viewModelScope.launch {
            try {
                // Cancel the WorkManager task
                workManager.cancelUniqueWork("download_${app.packageName}")

                // Delete download from database
                apkRepository.deleteDownload(app.packageName)

                // Update UI
                updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)

                _uiState.update { it.copy(errorMessage = "Download cancelled") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to cancel download: ${e.message}") }
            }
        }
    }

    /**
     * Clean up stale downloads that might be stuck in QUEUED/DOWNLOADING/VERIFYING state
     * This can happen if the app was killed while a download was in progress
     */
    private fun cleanupStaleDownloads() {
        viewModelScope.launch {
            try {
                // Get all downloads in potentially stale states
                val allDownloads = apkRepository.getAllDownloads()

                allDownloads.forEach { download ->
                    when (download.status) {
                        DownloadStatus.QUEUED,
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.DOWNLOADED,
                        DownloadStatus.VERIFYING -> {
                            // Check if there's an active worker for this download
                            val workInfo = workManager.getWorkInfosForUniqueWork("download_${download.packageName}").get()

                            // If no active worker or worker is in terminal state, clean up
                            val hasActiveWorker = workInfo.any { info ->
                                info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                            }

                            if (!hasActiveWorker) {
                                Log.i(
                                    "CategoryAppsViewModel",
                                    "Cleaning up stale download for ${download.packageName} with status ${download.status}"
                                )
                                apkRepository.deleteDownload(download.packageName)
                            } else {
                                // There's an active worker, resume monitoring
                                monitorDownloadProgress(download.packageName)
                            }
                        }

                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELLED -> {
                            // These should already be cleaned up, but do it anyway
                            apkRepository.deleteDownload(download.packageName)
                        }

                        else -> {
                            // INSTALLING status is ok - it's waiting for user confirmation
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CategoryAppsViewModel", "Failed to clean up stale downloads", e)
            }
        }
    }

    fun refreshAppStatus(packageName: String) {
        viewModelScope.launch {
            val appFromDb = apkRepository.getAllAppsFromDbNoFlow().find { it.packageName == packageName }
            if (appFromDb != null) {
                _uiState.update { state ->
                    state.copy(
                        apps = state.apps.map {
                            if (it.packageName == packageName) {
                                it.copy(status = appFromDb.status)
                            } else {
                                it
                            }
                        }
                    )
                }
            }
        }
    }
}
