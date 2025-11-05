package com.brax.apkstation.presentation.ui.lending.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.lending.LendingViewState
import com.brax.apkstation.presentation.ui.lending.StoreLendingViewModel
import com.brax.apkstation.presentation.ui.navigation.AppNavigationActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LendingTopAppBar(
    uiState: LendingViewState,
    viewModel: StoreLendingViewModel,
    keyboardController: SoftwareKeyboardController?,
    focusRequester: FocusRequester,
    navigationActions: AppNavigationActions,
    favoritesEnabled: Boolean = false
) {
    TopAppBar(
        title = {
            if (uiState.isSearchMode) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_hint),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    trailingIcon = {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (uiState.searchQuery.isNotBlank()) {
                                viewModel.executeSearch(uiState.searchQuery)
                                keyboardController?.hide()
                            }
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            } else {
                Text(
                    text = if (uiState.isFavoritesMode) {
                        stringResource(R.string.favorites)
                    } else {
                        stringResource(R.string.app_name_store)
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        navigationIcon = {
            // Show back arrow when in favorites mode
            if (uiState.isFavoritesMode && !uiState.isSearchMode) {
                IconButton(onClick = { viewModel.exitFavoritesMode() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        },
        actions = {
            // Search icon
            IconButton(
                onClick = {
                    if (uiState.isSearchMode) {
                        viewModel.clearSearch()
                    } else {
                        viewModel.enterSearchMode()
                    }
                }
            ) {
                Icon(
                    imageVector = if (uiState.isSearchMode) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (uiState.isSearchMode) "Close search" else "Search"
                )
            }
            
            // Heart icon - toggle favorites view (hide when search is open, only show if enabled)
            if (!uiState.isSearchMode && favoritesEnabled) {
                IconButton(
                    onClick = {
                        if (uiState.isFavoritesMode) {
                            viewModel.exitFavoritesMode()
                        } else {
                            viewModel.enterFavoritesMode()
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (uiState.isFavoritesMode) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = if (uiState.isFavoritesMode) "Exit favorites" else "View favorites",
                        tint = if (uiState.isFavoritesMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
            
            // Settings icon (hide when search is open)
            if (!uiState.isSearchMode) {
                IconButton(
                    onClick = { navigationActions.navigateToSettings() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
