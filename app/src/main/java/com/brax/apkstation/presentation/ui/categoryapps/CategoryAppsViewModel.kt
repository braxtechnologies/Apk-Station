package com.brax.apkstation.presentation.ui.categoryapps

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.brax.apkstation.data.repository.ApkRepository
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
                        } catch (e: Exception) {
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
                                    } catch (e: PackageManager.NameNotFoundException) {
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

            else -> {
                // Do nothing for DOWNLOADING, INSTALLING, etc.
            }
        }
    }

    private fun installApp(app: AppItem) {
        viewModelScope.launch {
            // TODO: Implement full installation logic similar to StoreLendingViewModel
            // For now, just update the status to show it's being processed
            _uiState.update { state ->
                state.copy(
                    apps = state.apps.map { 
                        if (it.packageName == app.packageName) {
                            it.copy(status = AppStatus.DOWNLOADING)
                        } else {
                            it
                        }
                    }
                )
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
