package com.brax.apkstation.app.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brax.apkstation.data.repository.ApkRepository
import com.brax.apkstation.utils.Constants.SHOULD_SHOW_PERMISSION_SCREEN_KEY
import com.brax.apkstation.utils.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    appPreferences: AppPreferencesRepository,
    private val apkRepository: ApkRepository
) : ViewModel() {

    val shouldShowPermissionScreen = appPreferences.dataStore.data.map {
        it[SHOULD_SHOW_PERMISSION_SCREEN_KEY] ?: true
    }

    // State to control splash screen visibility
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // Initialize app - just wait for splash screen
        // Enrollment will happen automatically on first API call
        viewModelScope.launch {
            try {
                // Minimum splash screen time
                delay(2000)
            } catch (e: Exception) {
                // Silent failure - app will attempt enrollment on first API call
            } finally {
                _isReady.value = true
            }
        }
    }
}
