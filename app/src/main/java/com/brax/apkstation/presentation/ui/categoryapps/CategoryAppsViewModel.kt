package com.brax.apkstation.presentation.ui.categoryapps

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.presentation.ui.lending.AppItem
import com.brax.apkstation.presentation.ui.lending.AppStatus
import com.brax.apkstation.presentation.ui.navigation.CategoryAppsScreen
import com.brax.apkstation.utils.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val downloadHelper: com.brax.apkstation.data.helper.DownloadHelper,
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val categoryAppsScreen = savedStateHandle.toRoute<CategoryAppsScreen>()
    
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
        
        // Observe installation events for immediate feedback
        observeInstallationEvents()
        
        // Observe download state
        observeDownloadState()
        
        // Observe database changes
        observeDatabaseChanges()
    }
    
    /**
     * Observe installer events for immediate UI feedback and errors
     */
    private fun observeInstallationEvents() {
        viewModelScope.launch {
            com.brax.apkstation.app.android.StoreApplication.events.installerEvent.collect { event ->
                when (event) {
                    is com.brax.apkstation.data.event.InstallerEvent.Installing -> {
                        updateAppStatus(event.packageName, AppStatus.INSTALLING)
                    }
                    is com.brax.apkstation.data.event.InstallerEvent.Failed -> {
                        // Refresh status from PackageManager/DB and show error
                        val dbApp = apkRepository.getAppByPackageName(event.packageName)
                        val status = if (dbApp != null) dbApp.status else AppStatus.NOT_INSTALLED
                        updateAppStatus(event.packageName, status)
                        _uiState.update { it.copy(errorMessage = event.error) }
                    }
                    // Installed/Uninstalled handled by database observation
                    else -> {}
                }
            }
        }
    }
    
    /**
     * Observe database changes for app installations/uninstallations
     * Room automatically notifies this Flow when AppStatusHelper updates the DB
     */
    private fun observeDatabaseChanges() {
        viewModelScope.launch {
            apkRepository.getAllApplications().collect { dbApps ->
                // Update status for all apps in our list
                _uiState.value.apps.forEach { displayedApp ->
                    val dbApp = dbApps.find { it.packageName == displayedApp.packageName }
                    
                    if (dbApp != null) {
                        // App is in database - use DB status
                        updateAppStatus(dbApp.packageName, dbApp.status, dbApp.hasUpdate)
                    } else {
                        // App was removed from database (uninstalled non-favorite)
                        // Check actual installation status from PackageManager
                        val isInstalled = try {
                            context.packageManager.getPackageInfo(displayedApp.packageName, 0)
                            true
                        } catch (_: Exception) {
                            false
                        }
                        val status = if (isInstalled) AppStatus.INSTALLED else AppStatus.NOT_INSTALLED
                        updateAppStatus(displayedApp.packageName, status, false)
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
                downloads.forEach { download ->
                    val status = when (download.status) {
                        com.brax.apkstation.data.model.DownloadStatus.QUEUED,
                        com.brax.apkstation.data.model.DownloadStatus.DOWNLOADING,
                        com.brax.apkstation.data.model.DownloadStatus.VERIFYING -> AppStatus.DOWNLOADING
                        com.brax.apkstation.data.model.DownloadStatus.INSTALLING -> AppStatus.INSTALLING
                        else -> null
                    }
                    
                    status?.let { updateAppStatus(download.packageName, it) }
                }
            }
        }
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
                                appFromDb?.status == AppStatus.DOWNLOADING -> AppStatus.DOWNLOADING
                                appFromDb?.status == AppStatus.INSTALLING -> AppStatus.INSTALLING
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
                // Do nothing for INSTALLING, UNAVAILABLE, etc.
            }
        }
    }

    /**
     * Install app - fetch details and enqueue download
     */
    private fun installApp(app: AppItem) {
        viewModelScope.launch {
            try {
                // Update UI to show downloading
                updateAppStatus(app.packageName, AppStatus.DOWNLOADING)

                // Fetch full app details
                val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                val packageName = if (uuid == null) app.packageName else null

                when (val result = apkRepository.getApkDetails(uuid = uuid, packageName = packageName)) {
                    is Result.Success -> {
                        val apkDetails = result.data

                        // Create download entry
                        val download = Download.fromApkDetails(
                                apkDetails,
                                isInstalled = false,
                                isUpdate = app.hasUpdate
                            )

                        // Save to database
                        apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)

                        // Enqueue download via DownloadHelper - it handles everything
                        downloadHelper.enqueueDownload(download)
                        
                        Log.d("CategoryAppsViewModel", "Enqueued download for ${app.packageName} via DownloadHelper")
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
                Log.e("CategoryAppsViewModel", "Failed to start download for ${app.packageName}", e)
                updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)
                _uiState.update { it.copy(errorMessage = "Failed to start download: ${e.message}") }
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
                // Cancel via DownloadHelper - it handles everything
                downloadHelper.cancel(app.packageName)

                _uiState.update { it.copy(errorMessage = "Download cancelled") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to cancel download: ${e.message}") }
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
