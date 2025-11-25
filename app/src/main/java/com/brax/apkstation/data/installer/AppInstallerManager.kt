package com.brax.apkstation.data.installer

import com.brax.apkstation.data.installer.base.IInstaller
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Main installer manager
 * Provides a unified interface for app installation
 * Uses SessionInstaller (Android PackageInstaller API) - Google Play compliant
 */
@Singleton
class AppInstallerManager @Inject constructor(
    private val sessionInstaller: SessionInstaller
) {

    /**
     * Get the installer
     * Always returns SessionInstaller (standard Android installation)
     */
    fun getPreferredInstaller(): IInstaller {
        return sessionInstaller
    }
}
