package com.brax.apkstation.data.room.entity

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.brax.apkstation.data.network.objects.AppInfo
import com.brax.apkstation.data.room.Converters
import com.brax.apkstation.presentation.ui.lending.AppStatus
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(tableName = "application")
data class DBApplication(
    @PrimaryKey val packageName: String,
    var uuid: String? = null, // UUID from API, nullable for backward compatibility
    var name: String,
    var version: String? = null,
    var versionCode: Int? = null,
    val downloadUrl: String? = null,
    var type: Int = 2,
    var fileType: String? = null,
    val icon: String? = null,
    var author: String? = null,
    var rating: String? = null,
    var size: String? = null,
    var category: String? = null,
    var contentRating: String? = null,
    var description: String? = null,
    @field:TypeConverters(Converters::class) var images: List<String>? = null,
    var status: AppStatus = AppStatus.NOT_INSTALLED,
    var latestVersionCode: Int? = null, // Latest available version code from API
    var hasUpdate: Boolean = false, // True if latestVersionCode > installed versionCode
    var retryCount: Int = 0, // Number of retry attempts for REQUESTED apps
    var isFavorite: Boolean = false // True if user marked this app as favorite
) : Parcelable {

    companion object {
        fun fromAppInfo(app: AppInfo, status: AppStatus) = DBApplication(
            packageName = app.packageName,
            uuid = null, // AppInfo doesn't have UUID
            name = app.name,
            version = app.version,
            versionCode = null,
            downloadUrl = app.url,
            type = app.type,
            fileType = app.fileType,
            icon = app.icon,
            author = app.author,
            rating = app.rating,
            size = app.fileSize,
            category = "",
            contentRating = "",
            description = "",
            images = emptyList(),
            status = status
        )
    }
}
