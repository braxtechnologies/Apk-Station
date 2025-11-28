package com.brax.apkstation.data.helper

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.room.dao.StoreDao
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.data.workers.DownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper for managing download queue
 * Observes downloads and automatically triggers them
 */
@Singleton
class DownloadHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadDao: StoreDao
) {

    companion object {
        private const val TAG = "DownloadHelper"
        const val DOWNLOAD_WORKER = "DOWNLOAD_WORKER"
        const val PACKAGE_NAME = "PACKAGE_NAME"

        private const val DOWNLOAD_APP = "DOWNLOAD_APP"
        private const val DOWNLOAD_UPDATE = "DOWNLOAD_UPDATE"
    }

    private val workManager = WorkManager.getInstance(context)

    /**
     * Flow of all downloads
     */
    val downloadsList
        get() = downloadDao.getAllDownloads()
            .stateIn(StoreApplication.scope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     * Initialize the helper - removes failed downloads and starts observing
     */
    fun init() {
        StoreApplication.scope.launch {
            cancelFailedDownloads(downloadDao.getAllDownloads().firstOrNull() ?: emptyList())
        }.invokeOnCompletion {
            observeDownloads()
        }
    }

    /**
     * Observe downloads and automatically trigger the next one in queue
     */
    private fun observeDownloads() {
        downloadDao.getAllDownloads().onEach { list ->
            try {
                // Check if any download is currently running
                val hasRunningDownload = list.any {
                    it.status == DownloadStatus.DOWNLOADING ||
                            it.status == DownloadStatus.VERIFYING ||
                            it.status == DownloadStatus.INSTALLING
                }

                if (!hasRunningDownload) {
                    // Find next queued download
                    list.find { it.status == DownloadStatus.QUEUED }?.let { queuedDownload ->
                        Log.i(TAG, "Enqueued download worker for ${queuedDownload.packageName}")
                        trigger(queuedDownload)
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to enqueue download worker", exception)
            }
        }.launchIn(StoreApplication.scope)
    }

    /**
     * Enqueue a download
     * @param download Download to enqueue
     */
    suspend fun enqueueDownload(download: Download) {
        downloadDao.insertDownload(download)
        Log.i(TAG, "Enqueued download for ${download.packageName}")
    }

    /**
     * Cancel a specific download
     * @param packageName Package name of the download to cancel
     */
    suspend fun cancel(packageName: String) {
        Log.i(TAG, "Cancelling download for $packageName")

        // Cancel the work
        workManager.cancelAllWorkByTag("$DOWNLOAD_WORKER/$packageName")

        // Delete the download entry from database
        downloadDao.deleteDownload(packageName)

        // Check if app should be removed from applications table
        val app = downloadDao.findApplicationByPackageName(packageName)
        if (app != null) {
            // Check if app is installed
            val isInstalled = try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: Exception) {
                false
            }

            // Only delete if NOT installed and NOT favorited
            if (!isInstalled && !app.isFavorite) {
                downloadDao.deleteApplication(packageName)
            }
        }

        // Delete downloaded files if any
        try {
            val downloadDir = File(context.filesDir, "downloads/$packageName")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete download files for $packageName", e)
        }
    }

    /**
     * Cancel all downloads
     * @param updatesOnly If true, only cancel update downloads
     */
    suspend fun cancelAll(updatesOnly: Boolean = false) {
        // Cancel all enqueued downloads first to avoid triggering re-download
        downloadDao.getAllDownloads().firstOrNull()
            ?.filter { it.status == DownloadStatus.QUEUED }
            ?.filter { if (updatesOnly) it.isUpdate else true }
            ?.forEach {
                downloadDao.updateDownloadStatus(it.packageName, DownloadStatus.CANCELLED)
            }

        WorkManager.getInstance(context)
            .cancelAllWorkByTag(if (updatesOnly) DOWNLOAD_UPDATE else DOWNLOAD_APP)
    }

    /**
     * Cancel failed downloads that are stuck
     */
    private suspend fun cancelFailedDownloads(downloadList: List<Download>) {
        val workManager = WorkManager.getInstance(context)

        downloadList.filter {
            it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.VERIFYING
        }.forEach { download ->
            // Check if the worker has actually finished
            val workInfos =
                workManager.getWorkInfosByTagFlow("$PACKAGE_NAME:${download.packageName}")
                    .firstOrNull()

            val allFinished = workInfos?.all { it.state.isFinished } ?: true
            if (allFinished) {
                Log.w(TAG, "Download ${download.packageName} was stuck, marking as failed")
                downloadDao.updateDownloadStatus(download.packageName, DownloadStatus.FAILED)
            }
        }
    }

    /**
     * Trigger download worker
     */
    private fun trigger(download: Download) {
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_PACKAGE_NAME, download.packageName)
            .build()

        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(DOWNLOAD_WORKER)
            .addTag("$PACKAGE_NAME:${download.packageName}")
            .addTag(if (download.isUpdate) DOWNLOAD_UPDATE else DOWNLOAD_APP)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputData)
            .build()

        // Ensure all app downloads are unique to preserve individual records
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "$DOWNLOAD_WORKER/${download.packageName}",
                ExistingWorkPolicy.KEEP,
                work
            )
    }

    /**
     * Clear download entry and files for a specific package
     */
    suspend fun clearDownload(packageName: String) {
        // Cancel work for this package
        workManager.cancelAllWorkByTag("$DOWNLOAD_WORKER/$packageName")

        // Delete from database
        downloadDao.deleteDownload(packageName)

        // Delete files
        try {
            val downloadDir = File(context.filesDir, "downloads/$packageName")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete download files for $packageName", e)
        }
    }

    /**
     * Clear all completed downloads
     */
    suspend fun clearCompletedDownloads() {
        Log.i(TAG, "Clearing all completed downloads")

        val downloads = downloadDao.getAllDownloadsList()
        downloads.filter {
            it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.FAILED ||
                    it.status == DownloadStatus.CANCELLED
        }.forEach { download ->
            clearDownload(download.packageName)
        }
    }

    /**
     * Clear all downloads and their files
     */
    fun clearAllDownloads() {
        Log.i(TAG, "Clearing all downloads")

        // Cancel all ongoing work
        workManager.cancelAllWorkByTag(DOWNLOAD_WORKER)

        // Delete all from database
        downloadDao.deleteAllDownloads()

        // Delete all files
        try {
            val downloadDir = File(context.filesDir, "downloads")
            if (downloadDir.exists()) {
                downloadDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete download files", e)
        }
    }
}
