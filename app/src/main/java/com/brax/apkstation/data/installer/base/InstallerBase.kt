package com.brax.apkstation.data.installer.base

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInstaller
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import com.brax.apkstation.BuildConfig
import com.brax.apkstation.R
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.data.room.entity.Download
import com.brax.apkstation.utils.NotificationHelper
import java.io.File

/**
 * Base class for all installers
 * Provides common functionality for installation management
 */
abstract class InstallerBase(protected val context: Context) : IInstaller {

    companion object {
        /**
         * Show a notification when app is successfully installed
         */
        fun notifyInstallation(context: Context, displayName: String, packageName: String) {
            val notificationManager = context.getSystemService<NotificationManager>()
            val notification = NotificationHelper.createInstallSuccessNotification(
                context,
                displayName,
                packageName
            )
            notificationManager?.notify(packageName.hashCode(), notification)
        }

        /**
         * Get a user-friendly error string for a given status code
         */
        fun getErrorString(context: Context, status: Int): String {
            return when (status) {
                PackageInstaller.STATUS_FAILURE_ABORTED -> 
                    context.getString(R.string.installer_status_user_action)
                PackageInstaller.STATUS_FAILURE_BLOCKED -> 
                    context.getString(R.string.installer_status_failure_blocked)
                PackageInstaller.STATUS_FAILURE_CONFLICT -> 
                    context.getString(R.string.installer_status_failure_conflict)
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> 
                    context.getString(R.string.installer_status_failure_incompatible)
                PackageInstaller.STATUS_FAILURE_INVALID -> 
                    context.getString(R.string.installer_status_failure_invalid)
                PackageInstaller.STATUS_FAILURE_STORAGE -> 
                    context.getString(R.string.installer_status_failure_storage)
                else -> context.getString(R.string.installer_status_failure)
            }
        }
    }

    private val TAG = InstallerBase::class.java.simpleName

    var download: Download? = null
        protected set

    override suspend fun install(download: Download) {
        this.download = download
    }

    override fun clearQueue() {
        StoreApplication.enqueuedInstalls.clear()
    }

    override fun isAlreadyQueued(packageName: String): Boolean {
        return StoreApplication.enqueuedInstalls.contains(packageName)
    }

    override fun removeFromInstallQueue(packageName: String) {
        StoreApplication.enqueuedInstalls.remove(packageName)
    }

    /**
     * Handle successful installation
     */
    protected fun onInstallationSuccess() {
        download?.let {
            notifyInstallation(context, it.displayName, it.packageName)
        }
    }

    /**
     * Handle installation error
     */
    open fun postError(packageName: String, error: String?, extra: String?) {
        Log.e(TAG, "Installer Error: $error")
        StoreApplication.events.send(
            InstallerEvent.Failed(
                packageName = packageName,
                error = error,
                extra = extra
            )
        )
    }

    /**
     * Get APK files for a package
     */
    protected fun getFiles(packageName: String, downloadLocation: String): List<File> {
        val downloadFile = File(downloadLocation)
        
        if (!downloadFile.exists()) {
            Log.e(TAG, "Download file does not exist: $downloadLocation")
            return emptyList()
        }
        
        // If the download location points to a directory, get all APK files in it
        if (downloadFile.isDirectory) {
            return downloadFile.listFiles { file -> 
                file.extension.equals("apk", ignoreCase = true) 
            }?.toList() ?: emptyList()
        }
        
        // If it's a single file, return it
        return listOf(downloadFile)
    }

    /**
     * Get a content URI for a file
     */
    protected fun getUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileProvider",
            file
        )
    }
}

