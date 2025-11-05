package com.brax.apkstation.presentation.ui.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.domain.model.Permission
import com.brax.apkstation.utils.Constants.SHOULD_SHOW_PERMISSION_SCREEN_KEY
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appPreferences: AppPreferencesRepository
) : ViewModel() {

    private val _permissions = mutableStateListOf<Permission>()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    init {
        initializePermissions()
    }

    private fun initializePermissions() {
        _permissions.clear()
        
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // REQUIRED PERMISSIONS
        
        // Package Installer (Required)
        _permissions.add(
            Permission(
                id = 1,
                title = "Installer Permission",
                description = "Allow installing apps from Apk Station",
                granted = context.packageManager.canRequestPackageInstalls(),
                optional = false
            )
        )

        // Battery Optimization (Required for background downloads)
        _permissions.add(
            Permission(
                id = 2,
                title = "Battery optimization",
                description = "Allow app to run without battery optimization for reliable background downloads",
                granted = pm.isIgnoringBatteryOptimizations(context.packageName),
                optional = false
            )
        )

        // External Storage Manager (Required)
        _permissions.add(
            Permission(
                id = 3,
                title = "External Storage Manager",
                description = "To save downloads (APKs & OBBs), export and import device configs to and from external storage.",
                granted = Environment.isExternalStorageManager(),
                optional = false
            )
        )

        // OPTIONAL PERMISSIONS
        
        // Post Notifications (Optional - Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            _permissions.add(
                Permission(
                    id = 4,
                    title = "Notifications",
                    description = "Send notifications regarding installations status",
                    granted = ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED,
                    optional = true
                )
            )
        }
    }

    fun refreshPermissions() {
        initializePermissions()
    }

    fun showSnackbar(message: String) {
        _snackbarMessage.value = message
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun areAllRequiredPermissionsGranted(): Boolean {
        return _permissions.filter { !it.optional }.all { it.granted }
    }

    fun getRequiredPermissions(): List<Permission> {
        return _permissions.filter { !it.optional }
    }

    fun getOptionalPermissions(): List<Permission> {
        return _permissions.filter { it.optional }
    }

    fun markPermissionsComplete() {
        viewModelScope.launch {
            appPreferences.savePreference(SHOULD_SHOW_PERMISSION_SCREEN_KEY, false)
        }
    }
    
    // Future use: Manual permission status update
    // fun updatePermissionStatus(permissionId: Int, granted: Boolean) {
    //     val index = _permissions.indexOfFirst { it.id == permissionId }
    //     if (index != -1) {
    //         _permissions[index] = _permissions[index].copy(granted = granted)
    //     }
    // }
    
    // Future use: Check if all permissions (including optional) are granted
    // fun areAllPermissionsGranted(): Boolean {
    //     return _permissions.all { it.granted }
    // }
}
