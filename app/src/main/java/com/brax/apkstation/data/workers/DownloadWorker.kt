package com.brax.apkstation.data.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.brax.apkstation.data.installer.AppInstallerManager
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.room.entity.Download
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Background worker for downloading APK files from Lunr API
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val storeDao: StoreDao,
    private val installerManager: AppInstallerManager,
    private val okHttpClient: OkHttpClient,
    private val apkRepository: com.brax.apkstation.data.repository.ApkRepository
) : CoroutineWorker(context, workerParams) {

    private val notificationManager = context.getSystemService<NotificationManager>()!!
    private val TAG = "DownloadWorker"
    private val NOTIFICATION_ID = 100
    private val CHANNEL_ID = "download_channel"

    override suspend fun doWork(): Result {
        val packageName = inputData.getString(KEY_PACKAGE_NAME) ?: return Result.failure()

        Log.i(TAG, "Starting download process for $packageName")

        return withContext(Dispatchers.IO) {
            try {
                val download = storeDao.getDownload(packageName)
                    ?: return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Download not found in database")
                    )

                // If URL is not present, fetch it from API first
                if (download.url.isNullOrBlank()) {
                    Log.i(TAG, "No download URL for $packageName, fetching from API...")
                    fetchDownloadUrl(packageName, download) ?: return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Failed to get download URL")
                    )
                }

                // Re-fetch download to get updated URL and MD5 after fetchDownloadUrl
                val updatedDownload = storeDao.getDownload(packageName)
                    ?: return@withContext Result.failure(
                        workDataOf(KEY_ERROR to "Download not found after URL fetch")
                    )

                // Try to set foreground notification (may fail if quota exhausted on Android 14+)
                try {
                    setForeground(getForegroundInfo(updatedDownload.displayName, 0))
                } catch (e: Exception) {
                    // Quota exhausted or foreground service not allowed
                    // Continue download in background with regular notification
                    Log.w(TAG, "Could not start foreground service (quota exhausted?), continuing in background", e)
                    showBackgroundNotification(updatedDownload.displayName, 0)
                }

                // Update status to downloading
                storeDao.updateDownloadStatus(packageName, DownloadStatus.DOWNLOADING)

                // Download the file
                val downloadedFile =
                    downloadFile(updatedDownload.url, updatedDownload.displayName, updatedDownload.fileSize)

                // Check if cancelled after download
                if (isStopped) {
                    Log.i(TAG, "Worker stopped after download, aborting for $packageName")
                    throw IOException("Download cancelled")
                }

                val downloadAfter = storeDao.getDownload(packageName)
                if (downloadAfter == null || downloadAfter.status == DownloadStatus.CANCELLED) {
                    Log.i(TAG, "Download cancelled in DB after download phase for $packageName")
                    throw IOException("Download cancelled")
                }

                // Update status to downloaded
                storeDao.updateDownloadStatus(packageName, DownloadStatus.DOWNLOADED)
                Log.i(TAG, "Download completed for $packageName")

                // Check if cancelled before verification
                if (isStopped) {
                    Log.i(TAG, "Worker stopped before verification, aborting for $packageName")
                    throw IOException("Download cancelled")
                }

                val downloadBeforeVerify = storeDao.getDownload(packageName)
                if (downloadBeforeVerify == null || downloadBeforeVerify.status == DownloadStatus.CANCELLED) {
                    Log.i(TAG, "Download cancelled in DB before verification for $packageName")
                    throw IOException("Download cancelled")
                }

                // Verify file integrity
                storeDao.updateDownloadStatus(packageName, DownloadStatus.VERIFYING)
                val md5Hash = updatedDownload.md5 // Capture to local variable for smart cast
                if (md5Hash != null) {
                    Log.i(TAG, "Verifying MD5 for $packageName (expected: $md5Hash)")
                    if (!verifyMd5(downloadedFile, md5Hash)) {
                        throw IOException("MD5 verification failed. File may be corrupted.")
                    }
                    Log.i(TAG, "MD5 verification passed for $packageName")
                } else {
                    Log.w(TAG, "No MD5 hash provided for $packageName - skipping verification!")
                    // Just check file exists and has content
                    if (!downloadedFile.exists() || downloadedFile.length() == 0L) {
                        throw IOException("Downloaded file is invalid")
                    }
                    Log.w(TAG, "File exists check passed for $packageName (size: ${downloadedFile.length()} bytes) - but integrity NOT verified!")
                }

                // Update download with file location
                storeDao.updateApkLocation(packageName, downloadedFile.absolutePath)

                Log.i(
                    TAG,
                    "Verification completed for $packageName at ${downloadedFile.absolutePath}"
                )

                // CRITICAL: Final check before installation - this is the last chance to abort!
                if (isStopped) {
                    Log.i(TAG, "Worker stopped, aborting installation for $packageName")
                    throw IOException("Download cancelled")
                }

                // Double-check download still exists and NOT cancelled in DB
                val currentDownload = storeDao.getDownload(packageName)
                if (currentDownload == null || currentDownload.status == DownloadStatus.CANCELLED) {
                    Log.i(
                        TAG,
                        "Download ${if (currentDownload == null) "removed" else "cancelled"}, aborting installation for $packageName"
                    )
                    // Clean up downloaded file
                    try {
                        downloadedFile.delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete file after cancellation", e)
                    }
                    throw IOException("Download cancelled")
                }

                // Trigger installation using the new installer manager
                storeDao.updateDownloadStatus(packageName, DownloadStatus.INSTALLING)
                
                // Get the download entity
                val downloadEntity = storeDao.getDownload(packageName)
                if (downloadEntity != null) {
                    installerManager.getPreferredInstaller().install(downloadEntity)
                    Log.i(TAG, "Triggered installation for $packageName")
                } else {
                    Log.e(TAG, "Download entity not found for $packageName after download completed")
                }

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $packageName", e)

                // CRITICAL: Only update DB if this download still exists and hasn't been superseded
                // If a new download started, the old entry would be deleted, so don't create a FAILED entry
                val currentDownload = storeDao.getDownload(packageName)
                if (currentDownload != null) {
                    // Download still exists, so this is a legitimate failure (not superseded)
                    storeDao.updateDownloadStatus(packageName, DownloadStatus.FAILED)
                    Log.i(TAG, "Marked download as FAILED for $packageName")
                } else {
                    // Download was deleted (superseded by new session), don't update DB
                    Log.i(TAG, "Download was superseded for $packageName, not marking as FAILED")
                }

                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download failed")))
            }
        }
    }

    /**
     * Fetch download URL from API
     * This can take time if the app needs to be fetched from external source
     */
    private suspend fun fetchDownloadUrl(packageName: String, download: Download): String? {
        return try {
            Log.i(TAG, "Fetching download URL from API for $packageName")
            
            // Get app info to extract UUID
            val app = storeDao.findApplicationByPackageName(packageName)
            val uuid = app?.uuid
            val versionCode = download.versionCode
            
            when (val result = apkRepository.getDownloadUrl(
                uuid = uuid,
                packageName = if (uuid == null) packageName else null,
                versionCode = if (versionCode > 0) versionCode else null
            )) {
                is com.brax.apkstation.utils.Result.Success -> {
                    val downloadResponse = result.data
                    
                    when {
                        // URL is ready
                        downloadResponse.type == "download" || 
                        (downloadResponse.type == null && downloadResponse.url.isNotEmpty()) -> {
                            Log.i(TAG, "Download URL received for $packageName")
                            
                            // Update download entry with URL and MD5
                            storeDao.updateDownload(download.copy(
                                url = downloadResponse.url,
                                md5 = downloadResponse.md5
                            ))
                            
                            downloadResponse.url
                        }
                        
                        // App needs to be fetched - this shouldn't happen with the new flow
                        // We'll just retry or fail
                        else -> {
                            Log.e(TAG, "App needs to be fetched from external source (type: ${downloadResponse.type})")
                            null
                        }
                    }
                }
                
                is com.brax.apkstation.utils.Result.Error -> {
                    Log.e(TAG, "Failed to get download URL: ${result.message}")
                    null
                }
                
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching download URL for $packageName", e)
            null
        }
    }

    private suspend fun downloadFile(
        url: String?,
        displayName: String,
        totalSize: Long
    ): File {
        if (url == null) throw IOException("Download URL is null")

        val packageName = inputData.getString(KEY_PACKAGE_NAME)!!
        val downloadDir = File(context.filesDir, "downloads/$packageName")
        downloadDir.mkdirs()

        // Determine file name and extension from URL or default to APK
        val fileName = url.substringAfterLast('/').ifBlank { "$packageName.apk" }
        val file = File(downloadDir, fileName)
        val tmpFile = File(file.absolutePath + ".tmp")

        // Clean up any old tmp files
        downloadDir.listFiles { _, name -> name.endsWith(".tmp") }?.forEach { oldTmp ->
            Log.i(TAG, "Cleaning up old tmp file: ${oldTmp.name}")
            oldTmp.delete()
        }

        Log.i(TAG, "Downloading from: $url")
        Log.i(TAG, "Saving to: ${file.absolutePath}")

        val request = Request.Builder()
            .url(url)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Response body is null")
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytes = 0L
                    var bytes: Int
                    var checkCounter = 0 // For periodic DB checks

                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (isStopped) {
                            throw IOException("Download cancelled")
                        }

                        // CRITICAL: Check DB every 64KB to detect if superseded by new download
                        if (checkCounter++ % 8 == 0) { // Every 8 * 8KB = 64KB
                            val currentDownload = storeDao.getDownload(packageName)
                            if (currentDownload == null || currentDownload.status == DownloadStatus.CANCELLED) {
                                Log.i(
                                    TAG,
                                    "Download superseded/cancelled in DB during download for $packageName"
                                )
                                throw IOException("Download cancelled")
                            }
                        }

                        output.write(buffer, 0, bytes)
                        totalBytes += bytes

                        // Calculate and update progress
                        val progress = if (contentLength > 0) {
                            ((totalBytes * 100) / contentLength).toInt()
                        } else if (totalSize > 0) {
                            ((totalBytes * 100) / totalSize).toInt()
                        } else {
                            0
                        }

                        if (totalBytes % (512 * 1024) == 0L) { // Update every 512KB
                            updateProgress(displayName, progress, totalBytes, contentLength)
                        }
                    }

                    // Final progress update
                    updateProgress(displayName, 100, totalBytes, contentLength)
                }
            }
        }

        // CRITICAL: Final check - verify download hasn't been cancelled/superseded
        val finalCheck = storeDao.getDownload(packageName)
        if (finalCheck == null || finalCheck.status == DownloadStatus.CANCELLED) {
            Log.i(
                TAG,
                "Download was cancelled/removed before rename, cleaning up tmp file for $packageName"
            )
            tmpFile.delete()
            throw IOException("Download cancelled before completion")
        }

        // Rename temp file to final file
        if (!tmpFile.renameTo(file)) {
            throw IOException("Failed to rename temp file")
        }

        return file
    }

    private suspend fun updateProgress(
        displayName: String,
        progress: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val packageName = inputData.getString(KEY_PACKAGE_NAME)!!

        // Update database
        storeDao.updateDownloadProgress(packageName, progress)

        // Update WorkManager progress
        setProgress(
            workDataOf(
                KEY_PROGRESS to progress,
                KEY_DOWNLOADED_BYTES to downloadedBytes,
                KEY_TOTAL_BYTES to totalBytes
            )
        )

        // Update notification (try foreground, fallback to background)
        try {
            setForeground(getForegroundInfo(displayName, progress))
        } catch (e: Exception) {
            // Foreground service not available, use regular notification
            showBackgroundNotification(displayName, progress)
        }
    }

    private fun getForegroundInfo(appName: String, progress: Int): ForegroundInfo {
        createNotificationChannel()

        val contentText = if (progress > 0) "$appName - $progress%" else "$appName - Starting..."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Apk Station - Downloading")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            // Use BigTextStyle to allow multiple lines for long app names
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
                    .setBigContentTitle("Apk Station - Downloading")
            )
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of app downloads"
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * Show background notification when foreground service is not available
     */
    private fun showBackgroundNotification(appName: String, progress: Int) {
        createNotificationChannel()
        
        val contentText = if (progress > 0) "$appName - $progress%" else "$appName - Starting..."
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Apk Station - Downloading")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
                    .setBigContentTitle("Apk Station - Downloading")
            )
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Verify MD5 checksum of downloaded file
     */
    private fun verifyMd5(file: File, expectedMd5: String): Boolean {
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }

            val digest = md.digest()
            val calculatedMd5 = digest.joinToString("") { "%02x".format(it) }

            val matches = calculatedMd5.equals(expectedMd5, ignoreCase = true)
            if (!matches) {
                Log.e(TAG, "MD5 mismatch! Expected: $expectedMd5, Got: $calculatedMd5")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify MD5", e)
            false
        }
    }

    companion object {
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR = "error"
    }
}
