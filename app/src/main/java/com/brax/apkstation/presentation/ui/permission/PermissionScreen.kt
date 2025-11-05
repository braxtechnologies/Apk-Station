package com.brax.apkstation.presentation.ui.permission

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.brax.apkstation.BuildConfig
import com.brax.apkstation.R
import com.brax.apkstation.presentation.ui.navigation.AppNavigationActions
import com.brax.apkstation.presentation.ui.permission.components.PermissionItem
import com.brax.apkstation.presentation.ui.permission.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    navigationActions: AppNavigationActions,
    viewModel: PermissionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    // Permission launchers
    val storageManagerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
        viewModel.showSnackbar(context.getString(R.string.permission_granted))
    }

    val batteryOptimizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
        viewModel.showSnackbar(context.getString(R.string.permission_granted))
    }

    val packageInstallerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
        viewModel.showSnackbar(context.getString(R.string.permission_granted))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            viewModel.showSnackbar(context.getString(R.string.permission_granted))
        } else {
            viewModel.showSnackbar(context.getString(R.string.permissions_denied))
        }
    }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp)
                    .navigationBarsPadding()
            ) {
                Button(
                    onClick = {
                        viewModel.markPermissionsComplete()
                        navigationActions.navigateToStoreLendingFromPermissionScreen()
                    },
                    enabled = viewModel.areAllRequiredPermissionsGranted(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_finish),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Title
            Text(
                text = stringResource(R.string.title_permissions),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Subtitle
            Text(
                text = stringResource(R.string.permission_select),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Required Permissions Section
                item {
                    SectionHeader(title = stringResource(R.string.item_required))
                }

                items(
                    items = viewModel.getRequiredPermissions(),
                    key = { it.id }
                ) { permission ->
                    PermissionItem(
                        permission = permission,
                        onClick = {
                            handlePermissionClick(
                                context = context,
                                permissionId = permission.id,
                                storageManagerLauncher = storageManagerLauncher,
                                batteryOptimizationLauncher = batteryOptimizationLauncher,
                                packageInstallerLauncher = packageInstallerLauncher,
                                notificationPermissionLauncher = notificationPermissionLauncher
                            )
                        }
                    )
                }

                // Optional Permissions Section (if any exist)
                if (viewModel.getOptionalPermissions().isNotEmpty()) {
                    item {
                        SectionHeader(title = stringResource(R.string.item_optional))
                    }

                    items(
                        items = viewModel.getOptionalPermissions(),
                        key = { it.id }
                    ) { permission ->
                        PermissionItem(
                            permission = permission,
                            onClick = {
                                handlePermissionClick(
                                    context = context,
                                    permissionId = permission.id,
                                    storageManagerLauncher = storageManagerLauncher,
                                    batteryOptimizationLauncher = batteryOptimizationLauncher,
                                    packageInstallerLauncher = packageInstallerLauncher,
                                    notificationPermissionLauncher = notificationPermissionLauncher
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
private fun handlePermissionClick(
    context: android.content.Context,
    permissionId: Int,
    storageManagerLauncher: ActivityResultLauncher<Intent>,
    batteryOptimizationLauncher: ActivityResultLauncher<Intent>,
    packageInstallerLauncher: ActivityResultLauncher<Intent>,
    notificationPermissionLauncher: ActivityResultLauncher<String>
) {
    when (permissionId) {
        1 -> {
            // Package Installer
            packageInstallerLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${BuildConfig.APPLICATION_ID}".toUri()
                )
            )
        }

        2 -> {
            // Battery Optimization
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = "package:${context.packageName}".toUri()
            batteryOptimizationLauncher.launch(intent)
        }

        3 -> {
            // External Storage Manager
            storageManagerLauncher.launch(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        }

        4 -> {
            // Post Notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
            }
        }
    }
}
