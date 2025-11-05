package com.brax.apkstation.data.model

enum class DownloadStatus {
    QUEUED,          // Download is queued but not started
    DOWNLOADING,     // Download is in progress
    DOWNLOADED,      // Download completed, ready for installation
    VERIFYING,       // Verifying file integrity (MD5/SHA)
    INSTALLING,      // Installation in progress
    COMPLETED,       // Installation completed successfully
    FAILED,          // Download or installation failed
    CANCELLED,       // Download or installation cancelled by user
    UNAVAILABLE;     // App is not available

    companion object {
        val finished = listOf(FAILED, CANCELLED, COMPLETED)
        val inProgress = listOf(DOWNLOADING, DOWNLOADED, VERIFYING, INSTALLING)
    }
}