package com.brax.apkstation.data.installer

/**
 * Interface for app installation
 */
interface AppInstaller {
    /**
     * Install an app from a downloaded APK/XAPK file
     * @param packageName Package name of the app to install
     */
    suspend fun install(packageName: String, sessionId: Long = -1L)
    
    /**
     * Uninstall an app
     * @param packageName Package name of the app to uninstall
     */
    fun uninstall(packageName: String)
    
    /**
     * Check if this installer is supported on the current device
     * @return true if supported, false otherwise
     */
    fun isSupported(): Boolean
}

/**
 * Result of an installation attempt
 */
sealed class InstallResult {
    object Success : InstallResult()
    data class Failed(val error: String) : InstallResult()
    object UserCancelled : InstallResult()
    data class Pending(val sessionId: Int) : InstallResult()
}

