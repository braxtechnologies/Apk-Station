package com.brax.apkstation.data.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A periodic worker to automatically clear old downloads cache
 * Runs every 6 hours and deletes files older than 24 hours
 */
@HiltWorker
class CacheWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "CacheWorker"
        private const val CACHE_WORKER = "CACHE_WORKER"

        /**
         * Schedules automated cache cleanup
         * Runs every 6 hours with 1 hour flex window
         */
        fun scheduleAutomatedCacheCleanup(context: Context) {
            val periodicWorkRequest = PeriodicWorkRequestBuilder<CacheWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS
            ).build()

            Log.i(TAG, "Scheduling periodic cache cleanup (every 6 hours)")
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(CACHE_WORKER, ExistingPeriodicWorkPolicy.KEEP, periodicWorkRequest)
        }

        /**
         * Cancels automated cache cleanup
         */
        fun cancelAutomatedCacheCleanup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(CACHE_WORKER)
        }
    }

    /**
     * Duration to cache files - 24 hours (more generous than AuroraStore's 6 hours)
     */
    private val cacheDuration = 24.toDuration(DurationUnit.HOURS)

    override suspend fun doWork(): Result {
        try {
            val downloadDir = File(context.filesDir, "downloads")
            
            if (!downloadDir.exists()) {
                return Result.success()
            }

            var deletedFiles = 0
            var deletedDirs = 0

            // Iterate through package directories
            downloadDir.listFiles()?.forEach { packageDir ->
                if (!packageDir.isDirectory) {
                    // Delete any stray files
                    if (packageDir.delete()) {
                        deletedFiles++
                    }
                    return@forEach
                }

                // Check if directory is empty
                val files = packageDir.listFiles()
                if (files.isNullOrEmpty()) {
                    if (packageDir.deleteRecursively()) {
                        deletedDirs++
                    }
                    return@forEach
                }

                // Delete old files and .tmp files
                files.forEach { file ->
                    if (file.name.endsWith(".tmp")) {
                        // Always delete .tmp files (incomplete downloads)
                        if (file.delete()) {
                            deletedFiles++
                        }
                    } else if (file.isOld()) {
                        // Delete files older than cache duration
                        if (file.delete()) {
                            deletedFiles++
                        }
                    }
                }

                // Check if directory is now empty after cleanup
                if (packageDir.listFiles().isNullOrEmpty()) {
                    if (packageDir.deleteRecursively()) {
                        deletedDirs++
                    }
                }
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Cache cleanup failed", e)
            return Result.failure()
        }
    }

    /**
     * Check if file is older than cache duration
     */
    private fun File.isOld(): Boolean {
        val elapsedTime = Calendar.getInstance().timeInMillis - this.lastModified()
        return elapsedTime.toDuration(DurationUnit.MILLISECONDS) > cacheDuration
    }
}
