package com.brax.apkstation.data.event

/**
 * Base event class for all events in the application
 */
abstract class Event

/**
 * Bus events for general application-wide events
 */
sealed class BusEvent : Event() {
    var extra: String = ""
    var error: String = ""

    data class Blacklisted(val packageName: String) : BusEvent()
    data class Download(val packageName: String) : BusEvent()
}

/**
 * Installer events for tracking app installation lifecycle
 */
open class InstallerEvent(open val packageName: String) : Event() {
    /**
     * Package was successfully installed
     */
    data class Installed(override val packageName: String) : InstallerEvent(packageName)
    
    /**
     * Package was uninstalled
     */
    data class Uninstalled(override val packageName: String) : InstallerEvent(packageName)

    /**
     * Installation is in progress
     * @param progress Installation progress (0.0 to 1.0)
     */
    data class Installing(
        override val packageName: String,
        val progress: Float = 0.0F
    ) : InstallerEvent(packageName)

    /**
     * Installation failed
     * @param error Error message
     * @param extra Additional error details
     */
    data class Failed(
        override val packageName: String,
        val error: String? = null,
        val extra: String? = null,
    ) : InstallerEvent(packageName)
}

/**
 * Download events for tracking download lifecycle
 */
open class DownloadEvent(open val packageName: String) : Event() {
    /**
     * Download started
     */
    data class Started(override val packageName: String) : DownloadEvent(packageName)
    
    /**
     * Download is in progress
     * @param progress Download progress (0-100)
     * @param downloadedBytes Bytes downloaded so far
     * @param totalBytes Total bytes to download
     */
    data class Progress(
        override val packageName: String,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadEvent(packageName)
    
    /**
     * Download completed successfully
     */
    data class Completed(override val packageName: String) : DownloadEvent(packageName)
    
    /**
     * Download failed
     */
    data class Failed(
        override val packageName: String,
        val error: String? = null
    ) : DownloadEvent(packageName)
    
    /**
     * Download cancelled
     */
    data class Cancelled(override val packageName: String) : DownloadEvent(packageName)
}

