package com.brax.apkstation.presentation.ui.lending

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.data.workers.DownloadWorker
import com.brax.apkstation.data.workers.RequestDownloadUrlWorker
import com.brax.apkstation.presentation.ui.lending.components.SectionTab
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StoreLendingViewModel @Inject constructor(
    private val apkRepository: ApkRepository,
    private val workManager: WorkManager,
    private val appPreferencesRepository: AppPreferencesRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private var allApps = emptyList<AppItem>() // Store all apps

    // Track which packages are currently being monitored to prevent duplicates
    private val monitoringPackages = mutableSetOf<String>()

    // Search cache to avoid overwhelming the backend
    private val searchCache = SearchCache()
    
    // Section cache for BRAX Picks, Top Charts, New Releases
    private val sectionCache = SectionCache()
    
    // Featured apps details cache (with images for carousel)
    private val featuredAppsDetailsCache = FeaturedAppsDetailsCache()

    private val _lendingUiState = MutableStateFlow(
        LendingViewState(
            isLoading = true,
            selectedSection = "featured" // Default to BRAX Picks
        )
    )
    val lendingUiState = _lendingUiState
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(3000L),
            LendingViewState(
                isLoading = true,
                selectedSection = "featured"
            )
        )
    
    val favoritesEnabled = appPreferencesRepository
        .getPreference(Constants.ENABLE_FAVORITES_KEY, false)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(3000L),
            false
        )

    private var searchJob: Job? = null

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
                installApp(app)
            }

            AppStatus.DOWNLOADING -> {
                cancelAppDownload(app)
            }

            else -> {
                // Do nothing for INSTALLING, REQUESTED, UNAVAILABLE, etc.
            }
        }
    }

    /**
     * Retrieve available apps list from API with caching support
     * Cache is network-aware and only used when connected
     */
    fun retrieveAvailableAppsList(
        category: String? = null,
        sort: String = "requests",
        isRefresh: Boolean = false
    ) {
        viewModelScope.launch {
            // Clean up stale downloads first (only on initial load, not on refresh)
            if (!isRefresh) {
                cleanupStaleDownloads()
            }

            // Check if we have network connection
            val isConnected = _lendingUiState.value.isConnected

            // Try to get from cache first (only if connected and not refreshing and not categories section)
            if (isConnected && !isRefresh && category == null) {
                val cachedApps = sectionCache.get(sort)
                if (cachedApps != null) {
                    Log.d("StoreLendingViewModel", "Using cached apps for section: $sort")
                    _lendingUiState.update {
                        it.copy(
                            apps = cachedApps,
                            isLoading = false,
                            isRefreshing = false
                        )
                    }
                    allApps = cachedApps
                    return@launch
                }
            }

            // Use isLoading only for initial load, isRefreshing for pull-to-refresh
            if (isRefresh) {
                _lendingUiState.update { it.copy(isRefreshing = true, isLoading = false) }
            } else {
                _lendingUiState.update { it.copy(isLoading = true, isRefreshing = false) }
            }

            // If no network, show error immediately
            if (!isConnected) {
                _lendingUiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        showNetworkAlert = true,
                        errorMessage = "No network connection"
                    )
                }
                return@launch
            }

            try {
                // Fetch apps from API
                when (val result = apkRepository.fetchApkList(category = category, limit = 50, sort = sort)) {
                    is Result.Success -> {
                        // Convert API DTOs to AppItem for display with actual installation status
                        val appItems = result.data.map { apk ->
                            // Check if app is installed and get its version
                            val installedVersionInfo = try {
                                val packageInfo =
                                    context.packageManager.getPackageInfo(apk.packageName, 0)
                                val versionCode = packageInfo.longVersionCode.toInt()
                                versionCode
                            } catch (_: PackageManager.NameNotFoundException) {
                                null // Not installed
                            }

                            // Get latest version from API
                            val latestVersionCode = apk.versionCode

                            // Check if update is available by comparing versions
                            val hasUpdate = if (installedVersionInfo != null) {
                                installedVersionInfo < latestVersionCode
                            } else {
                                false
                            }

                            // Update database with latest version info if installed
                            if (installedVersionInfo != null) {
                                viewModelScope.launch {
                                    apkRepository.updateVersionInfo(
                                        packageName = apk.packageName,
                                        latestVersionCode = latestVersionCode,
                                        hasUpdate = hasUpdate
                                    )
                                }
                            }

                            val actualStatus = getAppStatus(apk.packageName, hasUpdate)

                            AppItem(
                                uuid = apk.uuid,
                                packageName = apk.packageName,
                                name = apk.name,
                                version = apk.version,
                                icon = apk.icon,
                                author = apk.author,
                                rating = null, // Preview doesn't have rating
                                size = formatFileSize(apk.fileSize),
                                status = actualStatus,
                                hasUpdate = hasUpdate,
                                category = if (apk.category == "UNKNOWN") "Others" else formatCategoryName(
                                    apk.category
                                )
                            )
                        }

                        allApps = appItems
                        _lendingUiState.update { it.copy(apps = appItems) }
                        
                        // Cache the results for this section (except categories)
                        if (category == null) {
                            sectionCache.put(sort, appItems)
                            Log.d("StoreLendingViewModel", "Cached apps for section: $sort")
                        }

                        // Extract and create categories
                        updateCategories(appItems)

                        // Start monitoring any apps that are currently downloading/installing
                        appItems.forEach { app ->
                            if (app.status == AppStatus.DOWNLOADING || app.status == AppStatus.INSTALLING) {
                                monitorDownloadProgress(app.packageName)
                            }
                        }
                        
                        // Fetch featured app details with images for BRAX Picks section
                        if (sort == "featured" && category == null) {
                            fetchFeaturedAppsDetails(appItems)
                        }
                    }

                    is Result.Error -> {
                        _lendingUiState.update { it.copy(errorMessage = result.message) }
                    }

                    is Result.Loading -> {
                        // Already handled by _isLoading
                    }
                }
            } catch (e: Exception) {
                _lendingUiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to retrieve apps"
                    )
                }
            } finally {
                _lendingUiState.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }
    
    /**
     * Fetch detailed information (including images) for the first 5 featured apps
     * Results are cached for 5 minutes to avoid excessive API calls
     */
    private fun fetchFeaturedAppsDetails(appItems: List<AppItem>) {
        viewModelScope.launch {
            try {
                // Check cache first
                val cachedDetails = featuredAppsDetailsCache.get()
                if (cachedDetails != null) {
                    Log.d("StoreLendingViewModel", "Using cached featured apps details")
                    // Update apps list with cached details
                    val updatedApps = appItems.mapIndexed { index, app ->
                        if (index < 5) {
                            cachedDetails.getOrNull(index) ?: app
                        } else {
                            app
                        }
                    }
                    _lendingUiState.update { it.copy(apps = updatedApps) }
                    allApps = updatedApps
                    return@launch
                }
                
                // Fetch details for first 5 apps
                val first5Apps = appItems.take(5)
                val appsWithDetails = mutableListOf<AppItem>()
                
                first5Apps.forEach { app ->
                    // Use UUID if available, otherwise use package name
                    val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                    when (val result = apkRepository.getApkDetails(uuid = uuid, packageName = if (uuid == null) app.packageName else null)) {
                        is Result.Success -> {
                            val details = result.data
                            // Create updated AppItem with images and excerpt
                            appsWithDetails.add(
                                app.copy(
                                    images = details.images,
                                    excerpt = details.excerpt?.substringBefore("\r\n")
                                )
                            )
                            Log.d("StoreLendingViewModel", "Fetched details for ${app.name}, images: ${details.images.size}")
                        }
                        is Result.Error -> {
                            Log.e("StoreLendingViewModel", "Failed to fetch details for ${app.name}: ${result.message}")
                            // Keep app without images
                            appsWithDetails.add(app)
                        }
                        is Result.Loading -> {
                            // Keep app without images
                            appsWithDetails.add(app)
                        }
                    }
                }
                
                // Cache the enriched apps
                featuredAppsDetailsCache.put(appsWithDetails)
                
                // Update UI with enriched apps
                val updatedApps = appItems.mapIndexed { index, app ->
                    if (index < 5) {
                        appsWithDetails.getOrNull(index) ?: app
                    } else {
                        app
                    }
                }
                
                _lendingUiState.update { it.copy(apps = updatedApps) }
                allApps = updatedApps
                
                // Update section cache with enriched data
                sectionCache.put("featured", updatedApps)
                
            } catch (e: Exception) {
                Log.e("StoreLendingViewModel", "Error fetching featured apps details", e)
                // Continue with non-enriched apps
            }
        }
    }

    /**
     * Enter search mode - clear the app list to show empty state
     */
    fun enterSearchMode() {
        _lendingUiState.update {
            it.copy(
                isSearchMode = true,
                apps = emptyList(),
                searchQuery = "",
                searchSuggestions = emptyList(),
                hasExecutedSearch = false
            )
        }
    }

    /**
     * Update search query and fetch suggestions with debounce
     * Automatically fetches suggestions only if query has at least 4 characters
     * and after 1 second of user inactivity
     */
    fun updateSearchQuery(query: String) {
        _lendingUiState.update { it.copy(searchQuery = query) }

        // Cancel previous search job
        searchJob?.cancel()

        if (query.isBlank()) {
            // Clear suggestions and show empty state
            _lendingUiState.update {
                it.copy(
                    searchSuggestions = emptyList(),
                    isSearching = false,
                    apps = emptyList()
                )
            }
            return
        }

        // Only auto-fetch suggestions if query has at least 4 characters
        if (query.length >= 4) {
            // Debounce: Wait 1 second after user stops typing before making API call
            searchJob = viewModelScope.launch {
                delay(1000)
                fetchSearchSuggestions(query)
            }
        } else {
            // Clear suggestions if less than 4 characters
            _lendingUiState.update {
                it.copy(
                    searchSuggestions = emptyList(),
                    isSearching = false
                )
            }
        }
    }

    /**
     * Fetch search suggestions from API (or local favorites if in favorites mode)
     * Uses cache to avoid overwhelming the backend
     */
    private suspend fun fetchSearchSuggestions(query: String) {
        try {
            _lendingUiState.update { it.copy(isSearching = true) }

            // Normalize query for cache lookup
            val normalizedQuery = query.trim().lowercase()

            // If in favorites mode, search only within favorites (don't cache local searches)
            if (_lendingUiState.value.isFavoritesMode) {
                val favoriteApps = apkRepository.getFavoriteAppsNoFlow()
                val suggestions = favoriteApps
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .take(5)
                    .map { it.name }
                _lendingUiState.update { it.copy(searchSuggestions = suggestions) }
            } else {
                // Check cache first
                val cachedSuggestions = searchCache.get(normalizedQuery)
                if (cachedSuggestions != null) {
                    Log.d("StoreLendingViewModel", "Using cached suggestions for: $query")
                    _lendingUiState.update { it.copy(searchSuggestions = cachedSuggestions) }
                } else {
                    // Normal mode - search API
                    when (val result = apkRepository.searchApks(query)) {
                        is Result.Success -> {
                            // Take first 5 app names as suggestions
                            val suggestions = result.data.take(5).map { it.name }
                            // Cache the results
                            searchCache.put(normalizedQuery, suggestions)
                            _lendingUiState.update { it.copy(searchSuggestions = suggestions) }
                        }

                        else -> {
                            _lendingUiState.update { it.copy(searchSuggestions = emptyList()) }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            _lendingUiState.update { it.copy(searchSuggestions = emptyList()) }
        } finally {
            _lendingUiState.update { it.copy(isSearching = false) }
        }
    }

    /**
     * Execute final search when user selects a suggestion
     *
     * Strategy: In favorites mode, search only favorites. Otherwise, /search returns basic app info
     * (package, name, author, icon, rating) which is enough to display in the app list.
     * User can click to see full details.
     */
    fun executeSearch(selectedName: String) {
        viewModelScope.launch {
            try {
                Log.d("StoreLendingViewModel", "Executing search for: '$selectedName'")

                // Update search query with selected name and mark that search has been executed
                _lendingUiState.update {
                    it.copy(
                        searchQuery = selectedName,
                        searchSuggestions = emptyList(),
                        isLoading = true,
                        hasExecutedSearch = true
                    )
                }

                // If in favorites mode, search only within favorites
                if (_lendingUiState.value.isFavoritesMode) {
                    val favoriteApps = apkRepository.getFavoriteAppsNoFlow()
                    val filteredFavorites = favoriteApps.filter { 
                        it.name.contains(selectedName, ignoreCase = true) 
                    }

                    if (filteredFavorites.isEmpty()) {
                        _lendingUiState.update {
                            it.copy(
                                errorMessage = "No favorite apps found matching your search",
                                apps = emptyList()
                            )
                        }
                        return@launch
                    }

                    // Convert to AppItems
                    val appItems = filteredFavorites.map { dbApp ->
                        val installedVersionInfo = try {
                            val packageInfo = context.packageManager.getPackageInfo(dbApp.packageName, 0)
                            packageInfo.longVersionCode.toInt()
                        } catch (_: PackageManager.NameNotFoundException) {
                            null
                        }

                        val latestVersionCode = dbApp.latestVersionCode ?: dbApp.versionCode
                        val hasUpdate = if (installedVersionInfo != null && latestVersionCode != null) {
                            installedVersionInfo < latestVersionCode
                        } else {
                            false
                        }

                        val actualStatus = getAppStatus(dbApp.packageName, hasUpdate)

                        AppItem(
                            uuid = dbApp.uuid ?: "",
                            packageName = dbApp.packageName,
                            name = dbApp.name,
                            author = dbApp.author ?: "Unknown",
                            icon = dbApp.icon,
                            version = dbApp.version ?: "",
                            size = dbApp.size ?: "",
                            category = dbApp.category ?: "Others",
                            status = actualStatus,
                            rating = dbApp.rating
                        )
                    }.sortedWith(
                        compareByDescending<AppItem> {
                            it.name.equals(selectedName, ignoreCase = true)
                        }.thenBy {
                            it.name.lowercase()
                        }
                    )

                    _lendingUiState.update { it.copy(apps = appItems) }
                } else {
                    // Normal mode - search API
                    when (val searchResult = apkRepository.searchApks(selectedName)) {
                        is Result.Success -> {
                            Log.d(
                                "StoreLendingViewModel",
                                "Search successful, found ${searchResult.data.size} results"
                            )

                            if (searchResult.data.isEmpty()) {
                                Log.w(
                                    "StoreLendingViewModel",
                                    "No results found for '$selectedName'"
                                )
                                _lendingUiState.update {
                                    it.copy(
                                        errorMessage = "No apps found matching your search",
                                        apps = emptyList()
                                    )
                                }
                                return@launch
                            }

                            // Convert all search results to AppItems
                            // Sort by relevance: exact matches first, then alphabetically
                            val appItems = searchResult.data.map { apkPreview ->
                                // Check actual app status including database state (REQUESTED, DOWNLOADING, etc.)
                                val status = getAppStatus(apkPreview.packageName, hasUpdate = false)

                                AppItem(
                                    uuid = apkPreview.uuid.ifBlank { "" }, // May be empty from search
                                    packageName = apkPreview.packageName,
                                    name = apkPreview.name,
                                    author = apkPreview.author,
                                    icon = apkPreview.icon,
                                    version = "", // Not available from search
                                    size = "", // Not available from search
                                    category = "Others", // Not available from search
                                    status = status,
                                    rating = null // Not available from search (only in details)
                                )
                            }.sortedWith(
                                compareByDescending<AppItem> {
                                    // Exact match gets highest priority
                                    it.name.equals(selectedName, ignoreCase = true)
                                }.thenBy {
                                    // Then alphabetically by name
                                    it.name.lowercase()
                                }
                            )

                            _lendingUiState.update { it.copy(apps = appItems) }
                            Log.d(
                                "StoreLendingViewModel",
                                "Displaying ${appItems.size} search results"
                            )
                        }

                        is Result.Error -> {
                            Log.e(
                                "StoreLendingViewModel",
                                "Search error: ${searchResult.message}"
                            )
                            _lendingUiState.update {
                                it.copy(
                                    errorMessage = "Search failed: ${searchResult.message}",
                                    apps = emptyList()
                                )
                            }
                        }

                        else -> {
                            _lendingUiState.update {
                                it.copy(
                                    errorMessage = "Search failed",
                                    apps = emptyList()
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("StoreLendingViewModel", "Search failed", e)
                _lendingUiState.update {
                    it.copy(
                        errorMessage = "Search failed: ${e.message}",
                        apps = emptyList()
                    )
                }
            } finally {
                _lendingUiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Clear search and return to category list
     */
    fun clearSearch() {
        _lendingUiState.update {
            it.copy(
                isSearchMode = false,
                searchQuery = "",
                searchSuggestions = emptyList(),
                isSearching = false,
                isLoading = false,
                hasExecutedSearch = false
            )
        }
        searchJob?.cancel()
        val section = _lendingUiState.value.selectedSection ?: SectionTab.BRAX_PICKS.queryName
        retrieveAvailableAppsList(sort = section)
    }

    /**
     * Enter favorites mode - show only favorited apps
     */
    fun enterFavoritesMode() {
        viewModelScope.launch {
            _lendingUiState.update {
                it.copy(
                    isFavoritesMode = true,
                    isLoading = true,
                    selectedCategory = null // Clear category selection in favorites mode
                )
            }

            try {
                val favoriteApps = apkRepository.getFavoriteAppsNoFlow()

                // Convert to AppItems with actual status
                val appItems = favoriteApps.map { dbApp ->
                    val installedVersionInfo = try {
                        val packageInfo = context.packageManager.getPackageInfo(dbApp.packageName, 0)
                        val versionCode = packageInfo.longVersionCode.toInt()
                        versionCode
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }

                    val latestVersionCode = dbApp.latestVersionCode ?: dbApp.versionCode
                    val hasUpdate = if (installedVersionInfo != null && latestVersionCode != null) {
                        installedVersionInfo < latestVersionCode
                    } else {
                        false
                    }

                    val actualStatus = getAppStatus(dbApp.packageName, hasUpdate)

                    AppItem(
                        uuid = dbApp.uuid,
                        packageName = dbApp.packageName,
                        name = dbApp.name,
                        version = dbApp.version,
                        icon = dbApp.icon,
                        author = dbApp.author,
                        rating = dbApp.rating,
                        size = dbApp.size,
                        status = actualStatus,
                        hasUpdate = hasUpdate,
                        category = if (dbApp.category == "UNKNOWN") "Others" else formatCategoryName(dbApp.category)
                    )
                }

                _lendingUiState.update {
                    it.copy(
                        apps = appItems,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _lendingUiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Failed to load favorites",
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Exit favorites mode and return to normal view
     */
    fun exitFavoritesMode() {
        _lendingUiState.update {
            it.copy(
                isFavoritesMode = false
            )
        }
        // Reload the normal app list
        val section = _lendingUiState.value.selectedSection ?: SectionTab.BRAX_PICKS.queryName
        retrieveAvailableAppsList(sort = section)
    }

    fun selectSection(section: String) {
        viewModelScope.launch {
            _lendingUiState.update {
                it.copy(
                    selectedSection = section
                )
            }
            when (section) {
                SectionTab.CATEGORIES.queryName -> {
                    // Show categories list
                    loadCategories()
                }
                SectionTab.MY_APPS.queryName -> {
                    // Show installed apps from Apk Station
                    _lendingUiState.update { it.copy(isCategoriesListMode = false) }
                    loadInstalledApps()
                }
                else -> {
                    // Load apps for the selected section
                    _lendingUiState.update { it.copy(isCategoriesListMode = false) }
                    retrieveAvailableAppsList(sort = section)
                }
            }
        }
    }

    /**
     * Load available categories from the API
     */
    fun loadCategories() {
        viewModelScope.launch {
            _lendingUiState.update {
                it.copy(
                    isLoadingCategories = true,
                    isCategoriesListMode = true,
                    apps = emptyList()
                )
            }

            when (val result = apkRepository.getCategories(onlyWithApps = true)) {
                is Result.Success -> {
                    val categories = result.data.map { (key, category) ->
                        CategoryInfo(
                            key = key,
                            name = when {
                                key == "UNKNOWN" || category.name.isBlank() || category.name.equals("Unknown", ignoreCase = true) -> "Others"
                                else -> category.name
                            },
                            count = category.count,
                            packages = category.packages
                        )
                    }.sortedWith(
                        compareBy<CategoryInfo> { it.name == "Others" } // Put "Others" at the end
                            .thenBy { it.name } // Then sort alphabetically
                    )

                    _lendingUiState.update {
                        it.copy(
                            availableCategories = categories,
                            isLoadingCategories = false
                        )
                    }
                }
                is Result.Error -> {
                    _lendingUiState.update {
                        it.copy(
                            isLoadingCategories = false,
                            errorMessage = "Failed to load categories: ${result.message}"
                        )
                    }
                }
                else -> {
                    _lendingUiState.update {
                        it.copy(isLoadingCategories = false)
                    }
                }
            }
        }
    }

    /**
     * Load apps that were installed through Apk Station
     * Shows only apps that exist in the database and are currently installed on the device
     */
    fun loadInstalledApps() {
        viewModelScope.launch {
            _lendingUiState.update { it.copy(isLoading = true, errorMessage = null) }
            
            try {
                // Get all apps from database that were downloaded/installed through Apk Station
                val dbApps = apkRepository.getAllAppsFromDbNoFlow()
                
                // Filter to only show apps that are currently installed on the device
                val installedAppItems = dbApps.mapNotNull { dbApp ->
                    try {
                        // Check if app is actually installed
                        val packageInfo = context.packageManager.getPackageInfo(dbApp.packageName, 0)
                        val installedVersionCode = packageInfo.longVersionCode.toInt()
                        
                        // Check if update is available
                        val hasUpdate = dbApp.hasUpdate
                        
                        val status = if (hasUpdate) {
                            AppStatus.UPDATE_AVAILABLE
                        } else {
                            AppStatus.INSTALLED
                        }
                        
                        // Create AppItem for this installed app
                        AppItem(
                            uuid = dbApp.uuid,
                            packageName = dbApp.packageName,
                            name = dbApp.name,
                            version = dbApp.version,
                            icon = dbApp.icon,
                            author = dbApp.author,
                            rating = null,
                            size = dbApp.size?.let { formatFileSize(it) },
                            status = status,
                            hasUpdate = hasUpdate,
                            category = formatCategoryName(dbApp.category ?: "Others")
                        )
                    } catch (e: PackageManager.NameNotFoundException) {
                        // App is not installed, skip it
                        null
                    }
                }
                
                allApps = installedAppItems
                _lendingUiState.update {
                    it.copy(
                        apps = installedAppItems,
                        isLoading = false
                    )
                }
                
                Log.d("StoreLendingViewModel", "Loaded ${installedAppItems.size} installed apps from Apk Station")
                
            } catch (e: Exception) {
                Log.e("StoreLendingViewModel", "Failed to load installed apps", e)
                _lendingUiState.update {
                    it.copy(
                        errorMessage = "Failed to load installed apps: ${e.message}",
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * Update categories based on available apps
     */
    private fun updateCategories(apps: List<AppItem>) {
        val categoryMap = apps.groupBy { it.category }
        val sortedCategories = categoryMap.map { (category, categoryApps) ->
            AppCategory(name = category, count = categoryApps.size)
        }.sortedWith(compareBy<AppCategory> { it.name == "Others" }.thenBy { it.name })

        val categories = listOf(
            AppCategory(name = "BRAX Picks", count = apps.size)
        ) + sortedCategories

        _lendingUiState.update { it.copy(categories = categories) }
    }

    /**
     * Format category name from API format (e.g., COMMUNICATION -> Communication)
     */
    private fun formatCategoryName(category: String?): String {
        if (category.isNullOrBlank()) return "Others"

        return category.split("_")
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Update app status in the UI when package is installed/removed
     * Called by broadcast receiver when system package events occur
     */
    fun updateAppInDb(packageName: String, status: AppStatus) {
        viewModelScope.launch {
            // If app was installed, check if an update is available
            if (status == AppStatus.INSTALLED) {
                val dbApp = apkRepository.getAppByPackageName(packageName)
                val hasUpdate = dbApp?.hasUpdate ?: false
                val finalStatus = if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
                updateAppStatus(packageName, finalStatus, hasUpdate)
            } else {
                updateAppStatus(packageName, status)
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
            if (!_lendingUiState.value.isConnected) {
                _lendingUiState.update { it.copy(errorMessage = "Network connection unavailable") }
                return@launch
            }

            try {
                // Update UI to show downloading
                updateAppStatus(app.packageName, AppStatus.DOWNLOADING)

                // Fetch full app details to get download URL
                // Use UUID if not empty/null, otherwise use package name
                val uuid = app.uuid?.takeIf { it.isNotEmpty() }
                val packageName = if (uuid == null) app.packageName else null

                when (val result =
                    apkRepository.getApkDetails(uuid = uuid, packageName = packageName)) {
                    is Result.Success -> {
                        val apkDetails = result.data

                        // Check if versions are available
                        if (apkDetails.versions.isEmpty()) {
                            // App not yet cached - use RequestDownloadUrlWorker for potentially long request
                            Log.d(
                                "StoreLendingViewModel",
                                "No versions for ${app.packageName}, using RequestDownloadUrlWorker"
                            )

                            // Create download entry first (without URL)
                            val download = Download(
                                packageName = apkDetails.packageName,
                                url = null, // Will be filled by RequestDownloadUrlWorker
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

                            apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)
                            apkRepository.saveDownloadToDb(download)

                            // Enqueue RequestDownloadUrlWorker (handles 3-minute timeout)
                            val sessionId = System.currentTimeMillis()
                            val requestWorkRequest =
                                OneTimeWorkRequestBuilder<RequestDownloadUrlWorker>()
                                    .setInputData(
                                        workDataOf(
                                            RequestDownloadUrlWorker.KEY_PACKAGE_NAME to app.packageName,
                                            RequestDownloadUrlWorker.KEY_SESSION_ID to sessionId,
                                            RequestDownloadUrlWorker.KEY_UUID to uuid,
                                            RequestDownloadUrlWorker.KEY_VERSION_CODE to -1
                                        )
                                    )
                                    .addTag("request_${app.packageName}")
                                    .build()

                            workManager.enqueueUniqueWork(
                                "request_download_${app.packageName}_session_$sessionId",
                                ExistingWorkPolicy.REPLACE,
                                requestWorkRequest
                            )

                            // Monitor the download process
                            monitorDownloadProgress(app.packageName)
                        } else {
                            // Normal case: versions available, proceed as before
                            val download = Download.fromApkDetails(
                                apkDetails,
                                false, // not installed
                                app.hasUpdate // true if this is an update
                            )

                            // Save to database
                            apkRepository.saveApkDetailsToDb(apkDetails, AppStatus.DOWNLOADING)
                            apkRepository.saveDownloadToDb(download)

                            // Enqueue download worker
                            val workRequest =
                                OneTimeWorkRequestBuilder<DownloadWorker>()
                                    .setInputData(
                                        workDataOf(
                                            DownloadWorker.KEY_PACKAGE_NAME to app.packageName
                                        )
                                    )
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .build()

                            workManager.enqueueUniqueWork(
                                "download_${app.packageName}",
                                ExistingWorkPolicy.KEEP,
                                workRequest
                            )

                            // Start monitoring download progress
                            monitorDownloadProgress(app.packageName)
                        }
                    }

                    is Result.Error -> {
                        updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)
                        _lendingUiState.update { it.copy(errorMessage = "Failed to get app details: ${result.message}") }
                    }

                    is Result.Loading -> {
                        // Already handled by isLoading
                    }
                }
            } catch (e: Exception) {
                // Handle errors during setup
                Log.e(
                    "StoreLendingViewModel",
                    "Failed to start download for ${app.packageName}",
                    e
                )
                updateAppStatus(app.packageName, AppStatus.NOT_INSTALLED)
                _lendingUiState.update { it.copy(errorMessage = "Failed to start download: ${e.message}") }
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
                        val status =
                            if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED

                        updateAppStatus(packageName, status, hasUpdate)
                        break
                    }

                    // Check download status from database
                    val download = apkRepository.getDownload(packageName)

                    // If download is null, check if app was marked as REQUESTED
                    if (download == null) {
                        val dbApp = apkRepository.getAppByPackageName(packageName)
                        if (dbApp?.status == AppStatus.REQUESTED) {
                            Log.i(
                                "StoreLendingViewModel",
                                "App $packageName marked as REQUESTED - stopping monitoring"
                            )
                            updateAppStatus(packageName, AppStatus.REQUESTED)
                        }
                        break
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

                _lendingUiState.update { it.copy(errorMessage = "Download cancelled") }
            } catch (e: Exception) {
                _lendingUiState.update { it.copy(errorMessage = "Failed to cancel download: ${e.message}") }
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
        val updatedApps = _lendingUiState.value.apps.map { app ->
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
        _lendingUiState.update { it.copy(apps = updatedApps) }

        // Also update allApps for search filtering
        allApps = allApps.map { app ->
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
    }

    /**
     * Handle connectivity changes
     * Automatically reloads content when connection is restored
     */
    fun updateConnectivityStatus(isConnected: Boolean) {
        viewModelScope.launch {
            val wasDisconnected = !_lendingUiState.value.isConnected
            Log.d("StoreLendingViewModel", "Connectivity status updated: isConnected=$isConnected, wasDisconnected=$wasDisconnected")
            
            _lendingUiState.update { 
                it.copy(
                    isConnected = isConnected,
                    // Show alert when connection is lost, hide when restored
                    showNetworkAlert = !isConnected
                ) 
            }
            
            Log.d("StoreLendingViewModel", "UI State after update: isConnected=${_lendingUiState.value.isConnected}, showNetworkAlert=${_lendingUiState.value.showNetworkAlert}")
            
            // Auto-reload content when connection is restored (was disconnected, now connected)
            if (wasDisconnected && isConnected) {
                Log.d("StoreLendingViewModel", "Connection restored - auto-reloading content")
                
                // Reload based on current mode
                when {
                    _lendingUiState.value.isCategoriesListMode -> {
                        Log.d("StoreLendingViewModel", "Reloading categories")
                        loadCategories()
                    }
                    _lendingUiState.value.isSearchMode && _lendingUiState.value.hasExecutedSearch -> {
                        Log.d("StoreLendingViewModel", "Re-executing search: ${_lendingUiState.value.searchQuery}")
                        executeSearch(_lendingUiState.value.searchQuery)
                    }
                    _lendingUiState.value.isFavoritesMode -> {
                        Log.d("StoreLendingViewModel", "Reloading favorites")
                        enterFavoritesMode()
                    }
                    else -> {
                        val section = _lendingUiState.value.selectedSection ?: SectionTab.BRAX_PICKS.queryName
                        Log.d("StoreLendingViewModel", "Reloading section: $section")
                        retrieveAvailableAppsList(sort = section, isRefresh = true)
                    }
                }
            }
        }
    }

    /**
     * Show network error message
     */
    fun showNetworkError() {
        viewModelScope.launch {
            _lendingUiState.update { 
                it.copy(
                    errorMessage = "Network connection unavailable",
                    showNetworkAlert = true
                ) 
            }
        }
    }

    /**
     * Dismiss network alert banner
     */
    fun dismissNetworkAlert() {
        viewModelScope.launch {
            _lendingUiState.update { it.copy(showNetworkAlert = false) }
        }
    }

    /**
     * Retry connection - refresh the current view
     */
    fun retryConnection() {
        viewModelScope.launch {
            // Check current connection status
            if (_lendingUiState.value.isConnected) {
                // Connection is available, dismiss alert and reload
                _lendingUiState.update { it.copy(showNetworkAlert = false) }
                
                when {
                    _lendingUiState.value.isCategoriesListMode -> {
                        loadCategories()
                    }
                    _lendingUiState.value.isSearchMode && _lendingUiState.value.hasExecutedSearch -> {
                        executeSearch(_lendingUiState.value.searchQuery)
                    }
                    _lendingUiState.value.isFavoritesMode -> {
                        enterFavoritesMode()
                    }
                    else -> {
                        val section = _lendingUiState.value.selectedSection ?: SectionTab.BRAX_PICKS.queryName
                        retrieveAvailableAppsList(sort = section, isRefresh = true)
                    }
                }
            } else {
                // Still no connection, show error
                _lendingUiState.update { 
                    it.copy(
                        errorMessage = "Still no internet connection. Please check your network settings."
                    ) 
                }
            }
        }
    }

    /**
     * Get actual app status by checking:
     * 1. If app is installed on device (ground truth)
     * 2. If there's an active download
     * 3. If app is in REQUESTED state (from database)
     */
    private suspend fun getAppStatus(packageName: String, hasUpdate: Boolean = false): AppStatus {
        // Check if app is actually installed first (ground truth)
        val isInstalled = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        // If installed, clean up any stale download entries
        if (isInstalled) {
            val download = apkRepository.getDownload(packageName)
            if (download != null) {
                // App is installed but there's a download entry - clean it up
                apkRepository.deleteDownload(packageName)
            }
            // Return UPDATE_AVAILABLE if update exists, otherwise INSTALLED
            return if (hasUpdate) AppStatus.UPDATE_AVAILABLE else AppStatus.INSTALLED
        }

        // Not installed, check if there's an active download
        val download = apkRepository.getDownload(packageName)
        if (download != null) {
            return when (download.status) {
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.DOWNLOADED,
                DownloadStatus.VERIFYING -> AppStatus.DOWNLOADING

                DownloadStatus.INSTALLING -> AppStatus.INSTALLING
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED -> {
                    // Failed/cancelled downloads should be cleaned up
                    apkRepository.deleteDownload(packageName)
                    AppStatus.NOT_INSTALLED
                }

                else -> AppStatus.NOT_INSTALLED
            }
        }

        // Check database for special statuses (REQUESTED, UNAVAILABLE)
        val dbApp = apkRepository.getAppByPackageName(packageName)
        return when (dbApp?.status) {
            AppStatus.REQUESTED -> AppStatus.REQUESTED
            AppStatus.UNAVAILABLE -> AppStatus.UNAVAILABLE
            else -> {
                // No special status, no download, not installed
                AppStatus.NOT_INSTALLED
            }
        }
    }

    /**
     * Clean up stale downloads that might be stuck in QUEUED/DOWNLOADING/VERIFYING state
     * This can happen if the app was killed while a download was in progress
     */
    private suspend fun cleanupStaleDownloads() {
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
                        val workInfo =
                            workManager.getWorkInfosForUniqueWork("download_${download.packageName}")
                                .get()

                        // If no active worker or worker is in terminal state, clean up
                        val hasActiveWorker = workInfo.any { info ->
                            info.state == WorkInfo.State.RUNNING ||
                                    info.state == WorkInfo.State.ENQUEUED
                        }

                        if (!hasActiveWorker) {
                            Log.i(
                                "StoreLendingViewModel",
                                "Cleaning up stale download for ${download.packageName} with status ${download.status}"
                            )
                            apkRepository.deleteDownload(download.packageName)
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
            Log.e("StoreLendingViewModel", "Failed to clean up stale downloads", e)
        }
    }

    /**
     * Refresh a specific app's status (call this after installation completes)
     */
    fun refreshAppStatus(packageName: String) {
        viewModelScope.launch {
            // Check if this app has an update available
            val dbApp = apkRepository.getAppByPackageName(packageName)
            val hasUpdate = dbApp?.hasUpdate ?: false

            val updatedApps = _lendingUiState.value.apps.map { app ->
                if (app.packageName == packageName) {
                    app.copy(
                        status = getAppStatus(packageName, hasUpdate),
                        hasUpdate = hasUpdate
                    )
                } else {
                    app
                }
            }

            _lendingUiState.update { it.copy(apps = updatedApps) }
            allApps = updatedApps
        }
    }
}

/**
 * Data class representing an app item in the lending/landing page list
 */
data class AppItem(
    val uuid: String?, // May be null or empty when app is from search results
    val packageName: String,
    val name: String,
    val version: String? = null,
    val icon: String? = null,
    val author: String? = null,
    val rating: String? = null,
    val size: String? = null,
    val status: AppStatus = AppStatus.NOT_INSTALLED,
    val hasUpdate: Boolean = false,
    val category: String = "Others",
    val images: List<String> = emptyList(), // App screenshot images, populated for featured apps
    val excerpt: String? = null // App description excerpt, populated for featured apps
)

/**
 * Data class representing an app category with count
 */
data class AppCategory(
    val name: String,
    val count: Int
)

/**
 * App status enum for UI display
 */
enum class AppStatus(val status: String) {
    INSTALLED("installed"),
    NOT_INSTALLED("not_installed"),
    UPDATE_AVAILABLE("update_available"),
    REQUESTING("requesting"), // Currently requesting the app
    REQUESTED("requested"), // Request completed, waiting for app to be available
    UNAVAILABLE("unavailable"), // App is not available after 3 retry attempts
    DOWNLOADING("downloading"),
    INSTALLING("installing"),
    UPDATING("updating"),
    UNINSTALLING("uninstalling")
}

@Immutable
data class LendingViewState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isConnected: Boolean = true,
    val showNetworkAlert: Boolean = false, // Show network connectivity alert banner

    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchSuggestions: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val hasExecutedSearch: Boolean = false, // Track if user has actually executed a search

    val isFavoritesMode: Boolean = false,
    val isCategoriesListMode: Boolean = false, // Track if showing categories list

    val apps: List<AppItem> = emptyList(),
    val categories: List<AppCategory> = emptyList(),
    val availableCategories: List<CategoryInfo> = emptyList(), // Categories from /categories endpoint
    val isLoadingCategories: Boolean = false,
    val selectedSection: String? = null,
    val selectedCategory: String? = null,

    val errorMessage: String? = null
)

/**
 * Data class representing a category from the /categories endpoint
 */
data class CategoryInfo(
    val key: String,
    val name: String,
    val count: Int,
    val packages: List<String>
)

/**
 * Simple LRU cache for search suggestions
 * Stores the last 5 search queries with their results for 5 minutes
 */
private class SearchCache(
    private val maxSize: Int = 5,
    private val expirationTimeMillis: Long = 5 * 60 * 1000 // 5 minutes
) {
    private data class CacheEntry(
        val suggestions: List<String>,
        val timestamp: Long
    )

    private val cache = LinkedHashMap<String, CacheEntry>(maxSize, 0.75f, true)

    /**
     * Get cached suggestions for a query if not expired
     */
    fun get(query: String): List<String>? {
        val entry = cache[query] ?: return null
        val currentTime = System.currentTimeMillis()
        
        // Check if entry has expired
        if (currentTime - entry.timestamp > expirationTimeMillis) {
            cache.remove(query)
            return null
        }
        
        return entry.suggestions
    }

    /**
     * Store suggestions for a query with LRU eviction
     */
    fun put(query: String, suggestions: List<String>) {
        // Remove oldest entry if cache is full
        if (cache.size >= maxSize && !cache.containsKey(query)) {
            val oldestKey = cache.keys.first()
            cache.remove(oldestKey)
        }
        
        cache[query] = CacheEntry(
            suggestions = suggestions,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Clear all cached entries
     */
    fun clear() {
        cache.clear()
    }
}

/**
 * Cache for section data (BRAX Picks, Top Charts, New Releases)
 * Network-aware: only serves cached data when connected
 * Expires after 10 minutes
 */
private class SectionCache(
    private val expirationTimeMillis: Long = 10 * 60 * 1000 // 10 minutes
) {
    private data class SectionCacheEntry(
        val apps: List<AppItem>,
        val timestamp: Long
    )

    private val cache = mutableMapOf<String, SectionCacheEntry>()

    /**
     * Get cached apps for a section if not expired
     * @param sectionKey The section identifier (e.g., "featured", "requests", "date")
     * @return Cached app list or null if not cached or expired
     */
    fun get(sectionKey: String): List<AppItem>? {
        val entry = cache[sectionKey] ?: return null
        val currentTime = System.currentTimeMillis()
        
        // Check if entry has expired
        if (currentTime - entry.timestamp > expirationTimeMillis) {
            cache.remove(sectionKey)
            return null
        }
        
        return entry.apps
    }

    /**
     * Store apps for a section
     * @param sectionKey The section identifier (e.g., "featured", "requests", "date")
     * @param apps The list of apps to cache
     */
    fun put(sectionKey: String, apps: List<AppItem>) {
        cache[sectionKey] = SectionCacheEntry(
            apps = apps,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Clear all cached entries
     */
    fun clear() {
        cache.clear()
    }

    /**
     * Clear cache for a specific section
     */
    fun clearSection(sectionKey: String) {
        cache.remove(sectionKey)
    }
}

/**
 * Cache for featured apps details (with images)
 * Caches for 5 minutes to avoid excessive API calls
 */
private class FeaturedAppsDetailsCache(
    private val expirationTimeMillis: Long = 5 * 60 * 1000 // 5 minutes
) {
    private data class CacheEntry(
        val appsWithDetails: List<AppItem>,
        val timestamp: Long
    )

    private var cache: CacheEntry? = null

    /**
     * Get cached featured apps with details if not expired
     */
    fun get(): List<AppItem>? {
        val entry = cache ?: return null
        val currentTime = System.currentTimeMillis()
        
        // Check if entry has expired
        if (currentTime - entry.timestamp > expirationTimeMillis) {
            cache = null
            return null
        }
        
        return entry.appsWithDetails
    }

    /**
     * Store featured apps with details
     */
    fun put(appsWithDetails: List<AppItem>) {
        cache = CacheEntry(
            appsWithDetails = appsWithDetails,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Clear cache
     */
    fun clear() {
        cache = null
    }
}
