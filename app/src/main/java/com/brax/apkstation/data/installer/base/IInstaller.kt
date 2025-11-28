package com.brax.apkstation.data.installer.base

import com.brax.apkstation.data.room.entity.Download

/**
 * Interface for app installers
 */
interface IInstaller {
    /**
     * Install an app from a download
     * @param download The download containing app information and file location
     */
    suspend fun install(download: Download)
    
    /**
     * Clear the installation queue
     */
    fun clearQueue()
    
    /**
     * Check if a package is already queued for installation
     * @param packageName Package name to check
     * @return true if already queued
     */
    fun isAlreadyQueued(packageName: String): Boolean
    
    /**
     * Remove a package from the installation queue
     * @param packageName Package name to remove
     */
    fun removeFromInstallQueue(packageName: String)
}

