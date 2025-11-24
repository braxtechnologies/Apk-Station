package com.brax.apkstation.presentation.ui.appinfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.brax.apkstation.R
import com.brax.apkstation.data.receiver.InstallStatusReceiver
import com.brax.apkstation.presentation.ui.appinfo.components.AppActionButtons
import com.brax.apkstation.presentation.ui.appinfo.components.AppHeaderSection
import com.brax.apkstation.presentation.ui.appinfo.components.AppInfoSection
import com.brax.apkstation.presentation.ui.appinfo.components.DescriptionSection
import com.brax.apkstation.presentation.ui.appinfo.components.ScreenshotsSection
import com.brax.apkstation.presentation.ui.appinfo.components.UnsupportedAppAlertBanner
import com.brax.apkstation.presentation.ui.lending.AppStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(
    uuid: String,
    onNavigateBack: () -> Unit,
    onImageClick: (images: List<String>, initialIndex: Int) -> Unit = { _, _ -> },
    viewModel: AppInfoViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val uiState by viewModel.uiState.collectAsState()
    val favoritesEnabled by viewModel.favoritesEnabled.collectAsState()
    
    var isDescriptionExpanded by remember { mutableStateOf(false) }
    
    // Load app details
    LaunchedEffect(uuid) {
        viewModel.loadAppDetails(uuid)
    }
    
    // Listen for installation complete broadcast (both custom and system)
    DisposableEffect("installation_receiver") {
        val installReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                Log.d("AppInfoScreen", "Broadcast received! Action: ${intent?.action}")
                
                when (intent?.action) {
                    InstallStatusReceiver.ACTION_INSTALLATION_STATUS_CHANGED -> {
                        // Custom broadcast from our InstallStatusReceiver
                        val packageName = intent.getStringExtra(
                            InstallStatusReceiver.EXTRA_PACKAGE_NAME
                        )
                        val success = intent.getBooleanExtra(
                            InstallStatusReceiver.EXTRA_INSTALL_SUCCESS,
                            false
                        )
                        val downloadSessionId = intent.getLongExtra(
                            InstallStatusReceiver.EXTRA_DOWNLOAD_SESSION_ID,
                            -1L
                        )
                        val errorMessage = intent.getStringExtra(
                            InstallStatusReceiver.EXTRA_ERROR_MESSAGE
                        )
                        
                        Log.d("AppInfoScreen", "Custom broadcast - Package: $packageName, Success: $success, SessionId: $downloadSessionId, Error: $errorMessage")
                        
                        if (packageName != null) {
                            if (success) {
                                Log.i("AppInfoScreen", "Calling handleInstallationComplete for $packageName with sessionId: $downloadSessionId")
                                viewModel.handleInstallationComplete(packageName, downloadSessionId)
                            } else {
                                Log.i("AppInfoScreen", "Calling handleInstallationFailed for $packageName: $errorMessage")
                                viewModel.handleInstallationFailed(packageName, errorMessage)
                            }
                        }
                    }
                    
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        // System broadcast as fallback
                        val packageName = intent.data?.encodedSchemeSpecificPart
                        Log.d("AppInfoScreen", "System broadcast - Package added/replaced: $packageName")
                        
                        val currentStatus = uiState.appDetails?.status
                        if (packageName != null && 
                            (currentStatus == AppStatus.INSTALLING || currentStatus == AppStatus.UPDATING)) {
                            Log.i("AppInfoScreen", "System fallback: Calling handleInstallationComplete for $packageName")
                            viewModel.handleInstallationComplete(packageName)
                        }
                    }
                }
            }
        }
        
        val installFilter = IntentFilter().apply {
            // Listen to our custom broadcast
            addAction(InstallStatusReceiver.ACTION_INSTALLATION_STATUS_CHANGED)
            // Also listen to system broadcasts as fallback
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        
        Log.d("AppInfoScreen", "Registering broadcast receiver for installation events")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(installReceiver, installFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(installReceiver, installFilter)
        }
        
        onDispose {
            Log.d("AppInfoScreen", "Unregistering broadcast receiver")
            try {
                context.unregisterReceiver(installReceiver)
            } catch (e: Exception) {
                Log.e("AppInfoScreen", "Error unregistering receiver", e)
            }
        }
    }
    
    // Observe lifecycle to refresh status when returning from uninstall
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Only refresh if NOT currently installing/updating/downloading
                // (if installing/updating, we're waiting for broadcast confirmation)
                val currentStatus = uiState.appDetails?.status
                if (currentStatus != AppStatus.INSTALLING &&
                    currentStatus != AppStatus.UPDATING &&
                    currentStatus != AppStatus.DOWNLOADING
                ) {
                    // Refresh app status when screen resumes (user might have uninstalled)
                    viewModel.loadAppDetails(uuid)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Show error messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    
    // Network connectivity monitoring
    DisposableEffect(Unit) {
        viewModel.startNetworkMonitoring(context)
        onDispose {
            viewModel.stopNetworkMonitoring()
        }
    }
    
    // Back handler for search
    BackHandler(enabled = uiState.isSearchExpanded) {
        viewModel.collapseSearch()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSearchExpanded) {
                        TextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChange(it) },
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (uiState.isSearchExpanded) {
                            viewModel.collapseSearch()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Favorite toggle - only show when app details are loaded, not in search mode, and favorites is enabled
                    if (!uiState.isSearchExpanded && uiState.appDetails != null && favoritesEnabled) {
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = if (uiState.isFavorite) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = if (uiState.isFavorite) "Remove from favorites" else "Add to favorites",
                                tint = if (uiState.isFavorite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    
                    // Search icon
                    if (!uiState.isSearchExpanded) {
                        IconButton(onClick = { viewModel.expandSearch() }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        when {
            uiState.isSearchExpanded -> {
                SearchResultsList(
                    searchResults = uiState.searchResults,
                    isConnected = uiState.isConnected,
                    onAppClick = { viewModel.loadAppDetails(it) },
                    paddingValues = paddingValues
                )
            }
            
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            
            uiState.appDetails != null -> {
                val isUnsupportedApp = uiState.appDetails!!.uuid == null
                Log.d("AppInfoScreen", "Showing app details - UUID: ${uiState.appDetails!!.uuid}, isUnsupportedApp: $isUnsupportedApp")
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Show warning banner if app is not available in Apk Station (UUID is null)
                    UnsupportedAppAlertBanner(
                        isVisible = isUnsupportedApp
                    )
                    
                    AppDetailsContent(
                        appDetails = uiState.appDetails!!,
                        isConnected = uiState.isConnected,
                        isDescriptionExpanded = isDescriptionExpanded,
                        onToggleDescription = { isDescriptionExpanded = !isDescriptionExpanded },
                        onInstallClick = { viewModel.installApp() },
                        onOpenClick = {
                            val intent = context.packageManager.getLaunchIntentForPackage(uiState.appDetails!!.packageName)
                            intent?.let { context.startActivity(it) }
                        },
                        onUninstallClick = {
                            // Launch system uninstall dialog
                            val uninstallIntent = viewModel.createUninstallIntent()
                            if (uninstallIntent != null) {
                                try {
                                    context.startActivity(uninstallIntent)
                                } catch (e: Exception) {
                                    Log.e("AppInfoScreen", "Failed to start uninstall", e)
                                }
                            }
                        },
                        onCancelClick = { viewModel.cancelDownload() },
                        onScreenshotClick = { screenshotIndex ->
                            uiState.appDetails?.images?.takeIf { it.isNotEmpty() }?.let { images ->
                                onImageClick(images, screenshotIndex)
                            }
                        },
                        paddingValues = PaddingValues(0.dp)
                    )
                }
            }
            
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    searchResults: List<AppDetailsData>,
    isConnected: Boolean,
    onAppClick: (String) -> Unit,
    paddingValues: PaddingValues
) {
    if (searchResults.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_results),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(searchResults, key = { it.packageName }) { app ->
                SearchResultItem(
                    app = app,
                    isConnected = isConnected,
                    onClick = { onAppClick(app.packageName) }
                )
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    app: AppDetailsData,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon
            AsyncImage(
                model = app.icon,
                contentDescription = app.name,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // App info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = app.author ?: "-",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun AppDetailsContent(
    appDetails: AppDetailsData,
    isConnected: Boolean,
    isDescriptionExpanded: Boolean,
    onToggleDescription: () -> Unit,
    onInstallClick: () -> Unit,
    onOpenClick: () -> Unit,
    onUninstallClick: () -> Unit,
    onCancelClick: () -> Unit,
    onScreenshotClick: (Int) -> Unit,
    paddingValues: PaddingValues
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App header (icon, name, author, package)
        item {
            AppHeaderSection(appDetails)
        }

        // App info (rating, size, content rating)
        item {
            AppInfoSection(appDetails)
        }

        // Action buttons
        item {
            AppActionButtons(
                appDetails = appDetails,
                isConnected = isConnected,
                onInstallClick = onInstallClick,
                onOpenClick = onOpenClick,
                onUninstallClick = onUninstallClick,
                onCancelClick = onCancelClick
            )
        }

        // Screenshots
        if (!appDetails.images.isNullOrEmpty()) {
            item {
                ScreenshotsSection(
                    screenshots = appDetails.images,
                    onScreenshotClick = onScreenshotClick
                )
            }
        }

        // Description
        item {
            if (appDetails.description.isNullOrEmpty().not()) {
                DescriptionSection(
                    description = appDetails.description,
                    isExpanded = isDescriptionExpanded,
                    onToggle = onToggleDescription
                )
            }
        }
    }
}

// Data class for app details
data class AppDetailsData(
    val uuid: String?, // May be null when app is queried by package name
    val packageName: String,
    val name: String,
    val version: String?, // Latest version available in store
    val versionCode: Int?, // Latest version code available in store
    val icon: String?,
    val iconDrawable: Drawable? = null,
    val author: String?,
    val rating: String?,
    val size: String?,
    val contentRating: String?,
    val description: String?,
    val images: List<String>?,
    val status: AppStatus,
    val hasUpdate: Boolean = false, // True if an update is available
    val latestVersionCode: Int? = null, // Latest available version code
    val installedVersion: String? = null, // Currently installed version name
    val installedVersionCode: Int? = null // Currently installed version code
) {
    /**
     * Returns true if the app is installed or being updated/downloaded for update
     * Used to determine if we should show installed version info in the UI
     */
    val shouldShowInstalledVersion: Boolean
        get() = status == AppStatus.INSTALLED
                || status == AppStatus.UPDATE_AVAILABLE
                || status == AppStatus.UPDATING
                || (status == AppStatus.DOWNLOADING && hasUpdate)
}
