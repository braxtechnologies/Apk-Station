package com.brax.apkstation.data.room.entity

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.brax.apkstation.data.model.DownloadStatus
import com.brax.apkstation.data.network.dto.ApkDetailsDto
import com.brax.apkstation.data.network.objects.ApplicationPolicy
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "download")
data class Download(
    @PrimaryKey val packageName: String,
    val url: String?,
    val version: String,
    val versionCode: Int,
    val isInstalled: Boolean,
    val isUpdate: Boolean = false, // True if this is an update, false if fresh install
    val displayName: String,
    val icon: String?,
    var status: DownloadStatus,
    var progress: Int,
    var fileSize: Long,
    var speed: Long,
    var timeRemaining: Long,
    var totalFiles: Int,
    var fileType: String?,
    var downloadedFiles: Int,
    var apkLocation: String,
    var md5: String? = null // For file verification
) : Parcelable {
    val isFinished get() = status in DownloadStatus.finished

    companion object {
        fun fromApp(
            app: ApplicationPolicy,
            isInstalled: Boolean,
            isUpdate: Boolean = false
        ): Download {
            return Download(
                app.packageName,
                app.url,
                app.version,
                0, // versionCode not available from ApplicationPolicy
                isInstalled,
                isUpdate,
                app.name!!,
                app.icon,
                DownloadStatus.QUEUED,
                0,
                app.fileSize,
                0L,
                0L,
                0,
                app.fileType,
                0,
                "",
                null
            )
        }

        fun fromDBApp(
            app: DBApplication,
            isInstalled: Boolean,
            isUpdate: Boolean = false
        ): Download {
            return Download(
                app.packageName,
                app.downloadUrl,
                app.version ?: "-",
                app.versionCode ?: 0,
                isInstalled,
                isUpdate,
                app.name,
                app.icon,
                DownloadStatus.QUEUED,
                0,
                app.size?.toLong() ?: 0,
                0L,
                0L,
                0,
                app.fileType,
                0,
                "",
                null
            )
        }

        /**
         * Create Download from Lunr API ApkDetailsDto
         * Uses the latest version (first in the versions array)
         * Note: url is initially null and will be set by calling /download endpoint
         */
        fun fromApkDetails(
            apkDetails: ApkDetailsDto,
            isInstalled: Boolean,
            isUpdate: Boolean = false
        ): Download {
            val latestVersion = apkDetails.versions.firstOrNull()
                ?: throw IllegalArgumentException("No versions available for ${apkDetails.packageName}")
            
            return Download(
                packageName = apkDetails.packageName,
                url = null, // Will be fetched via /download endpoint
                version = latestVersion.version,
                versionCode = latestVersion.versionCode,
                isInstalled = isInstalled,
                isUpdate = isUpdate,
                displayName = apkDetails.name,
                icon = apkDetails.icon,
                status = DownloadStatus.QUEUED,
                progress = 0,
                fileSize = latestVersion.fileSize.toLongOrNull() ?: 0L,
                speed = 0L,
                timeRemaining = 0L,
                totalFiles = 1,
                fileType = latestVersion.fileType,
                downloadedFiles = 0,
                apkLocation = "",
                md5 = latestVersion.md5
            )
        }
    }
}
