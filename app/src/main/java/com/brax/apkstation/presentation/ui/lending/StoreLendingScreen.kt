package com.brax.apkstation.presentation.ui.lending

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.lending.components.AppListItem
import com.brax.apkstation.presentation.ui.lending.components.CategoriesListScreen
import com.brax.apkstation.presentation.ui.lending.components.CategoryItem
import com.brax.apkstation.presentation.ui.lending.components.FeaturedCarousel
import com.brax.apkstation.presentation.ui.lending.components.LendingTopAppBar
import com.brax.apkstation.presentation.ui.lending.components.NetworkAlertBanner
import com.brax.apkstation.presentation.ui.lending.components.SectionTab
import com.brax.apkstation.presentation.ui.lending.components.SectionTabs
import com.brax.apkstation.presentation.ui.navigation.AppNavigationActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreLendingScreen(
    navigationActions: AppNavigationActions,
    viewModel: StoreLendingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.lendingUiState.collectAsStateWithLifecycle()
    val favoritesEnabled by viewModel.favoritesEnabled.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Request focus and show keyboard when search mode is entered
    LaunchedEffect(uiState.isSearchMode) {
        if (uiState.isSearchMode) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Observe lifecycle to refresh status when returning from app info
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.retrieveAvailableAppsList(sort = uiState.selectedSection ?: SectionTab.BRAX_PICKS.queryName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Collect error messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            // Clear the error message after showing it
            viewModel.clearErrorMessage()
        }
    }

    // Initial data load
    LaunchedEffect(Unit) {
        if (uiState.apps.isEmpty() && !uiState.isCategoriesListMode) {
            viewModel.selectSection(SectionTab.BRAX_PICKS.queryName)
        }
    }

    // Back press handling
    BackHandler {
        when {
            uiState.isSearchMode -> {
                // Exit search mode
                viewModel.clearSearch()
            }

            uiState.selectedSection != null -> {
                // Clear category selection
                viewModel.selectSection(SectionTab.BRAX_PICKS.queryName)
            }

            else -> {
                // Exit app
                (context as? Activity)?.finish()
            }
        }
    }

    Scaffold(
        topBar = {
            LendingTopAppBar(
                uiState,
                viewModel,
                keyboardController,
                focusRequester,
                navigationActions,
                favoritesEnabled
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Show divider under search bar when in search mode
                if (uiState.isSearchMode) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Section tabs - always visible (except in search and favorites mode)
                if (!uiState.isSearchMode && !uiState.isFavoritesMode) {
                    SectionTabs(
                        selectedTab = uiState.selectedSection,
                        onTabSelected = { sectionQuery ->
                            viewModel.selectSection(sectionQuery)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                NetworkAlertBanner(
                    isVisible = !uiState.isConnected,
                    onRetry = { viewModel.retryConnection() }
                )

                // Content area with pull to refresh
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = {
                        if (!uiState.isConnected) {
                            viewModel.showNetworkError()
                        } else {
                            viewModel.retrieveAvailableAppsList(sort = uiState.selectedSection ?: SectionTab.BRAX_PICKS.queryName, isRefresh = true)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    state = rememberPullToRefreshState()
                ) {
                    when {
                        uiState.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        uiState.apps.isEmpty() -> {
                            // If in categories list mode, show the categories screen instead
                            if (uiState.isCategoriesListMode) {
                                CategoriesListScreen(
                                    categories = uiState.availableCategories.map {
                                        CategoryItem(
                                            key = it.key,
                                            name = it.name,
                                            count = it.count
                                        )
                                    },
                                    isLoading = uiState.isLoadingCategories,
                                    onCategoryClick = { categoryKey ->
                                        // Find the category name
                                        val categoryName = uiState.availableCategories
                                            .find { it.key == categoryKey }?.name ?: categoryKey
                                        navigationActions.navigateToCategoryApps(categoryKey, categoryName)
                                    }
                                )
                            } else {
                                // Show "no results" only if:
                                // 1. Not in search mode, OR
                                // 2. In search mode AND user has actually executed a search
                                val shouldShowEmptyState =
                                    !uiState.isSearchMode || uiState.hasExecutedSearch

                                if (shouldShowEmptyState) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (uiState.isSearchMode) {
                                                stringResource(R.string.no_results_found)
                                            } else {
                                                stringResource(R.string.no_results)
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // Show categories list or app list depending on mode
                            OnSuccessScreen(uiState, navigationActions, viewModel)
                        }
                    }
                }
            }

            // Search suggestions dropdown overlay (direct child of Box for proper alignment)
            if (uiState.isSearchMode && uiState.searchSuggestions.isNotEmpty() && uiState.apps.isEmpty()) {
                SuggestionDropDownOverlay(paddingValues, uiState, viewModel, keyboardController)
            }
        }
    }
}

@Composable
private fun OnSuccessScreen(
    uiState: LendingViewState,
    navigationActions: AppNavigationActions,
    viewModel: StoreLendingViewModel
) {
    if (uiState.isCategoriesListMode) {
        CategoriesListScreen(
            categories = uiState.availableCategories.map {
                CategoryItem(
                    key = it.key,
                    name = it.name,
                    count = it.count
                )
            },
            isLoading = uiState.isLoadingCategories,
            onCategoryClick = { categoryKey ->
                // Find the category name
                val categoryName = uiState.availableCategories
                    .find { it.key == categoryKey }?.name ?: categoryKey
                navigationActions.navigateToCategoryApps(categoryKey, categoryName)
            }
        )
    } else {
        // App list only - tabs are outside this section
        val isBraxPicksSection = uiState.selectedSection == SectionTab.BRAX_PICKS.queryName
                && !uiState.isSearchMode
                && !uiState.isFavoritesMode
        val featuredApps = if (isBraxPicksSection) uiState.apps.take(5) else emptyList()
        val remainingApps = if (isBraxPicksSection && uiState.apps.size > 5) {
            uiState.apps.drop(5)
        } else if (!isBraxPicksSection) {
            uiState.apps
        } else {
            emptyList()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            // Featured carousel for BRAX picks section
            if (featuredApps.isNotEmpty()) {
                item {
                    FeaturedCarousel(
                        featuredApps = featuredApps,
                        onAppClick = { app ->
                            val identifier =
                                if (!app.uuid.isNullOrBlank()) app.uuid else app.packageName
                            navigationActions.navigateToAppInfo(identifier)
                        },
                        onActionClick = { app ->
                            viewModel.onAppActionButtonClick(app)
                        },
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(remainingApps, key = { it.packageName }) { app ->
                AppListItem(
                    app = app,
                    isConnected = uiState.isConnected,
                    onAppClick = {
                        // Use UUID if available, otherwise use package name
                        val identifier =
                            if (!app.uuid.isNullOrBlank()) app.uuid else app.packageName
                        navigationActions.navigateToAppInfo(identifier)
                    },
                    onActionClick = { viewModel.onAppActionButtonClick(app) }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SuggestionDropDownOverlay(
    paddingValues: PaddingValues,
    uiState: LendingViewState,
    viewModel: StoreLendingViewModel,
    keyboardController: SoftwareKeyboardController?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = paddingValues.calculateTopPadding())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .align(Alignment.TopCenter),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column {
            uiState.searchSuggestions.forEachIndexed { index, suggestion ->
                SuggestionItem(
                    suggestion = suggestion,
                    onClick = {
                        viewModel.executeSearch(suggestion)
                        keyboardController?.hide()
                    }
                )
                if (index < uiState.searchSuggestions.size - 1) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SuggestionItem(
    suggestion: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            text = suggestion,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
