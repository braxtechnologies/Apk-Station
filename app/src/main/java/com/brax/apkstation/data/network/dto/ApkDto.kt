package com.brax.apkstation.data.network.dto

import com.google.gson.annotations.SerializedName

/**
 * Enrollment request - for /enroll endpoint
 */
data class EnrollRequestDto(
    @SerializedName("deviceSn")
    val deviceSn: String,
    
    @SerializedName("deviceModel")
    val deviceModel: String,
    
    @SerializedName("imei1")
    val imei1: String?,
    
    @SerializedName("imei2")
    val imei2: String?,
    
    @SerializedName("deviceUid")
    val deviceUid: String,
    
    @SerializedName("manufacturer")
    val manufacturer: String
)

/**
 * Enrollment response - JWT tokens
 */
data class EnrollResponseDto(
    @SerializedName("uuid")
    val uuid: String,
    
    @SerializedName("accessToken")
    val accessToken: String,
    
    @SerializedName("refreshToken")
    val refreshToken: String,
    
    @SerializedName("expiresIn")
    val expiresIn: Int // Seconds until access token expires
)

/**
 * Refresh token response
 */
data class RefreshResponseDto(
    @SerializedName("uuid")
    val uuid: String,
    
    @SerializedName("accessToken")
    val accessToken: String,
    
    @SerializedName("expiresIn")
    val expiresIn: Int
)

/**
 * APK Preview - matches ApkPreview schema from API specs
 * Used in /list and /search endpoints
 */
data class ApkPreviewDto(
    @SerializedName("uuid")
    val uuid: String = "",
    
    @SerializedName("package")
    val packageName: String = "",
    
    @SerializedName("name")
    val name: String = "",
    
    @SerializedName("author")
    val author: String = "",
    
    @SerializedName("icon")
    val icon: String = "",
    
    @SerializedName("version")
    val version: String = "",
    
    @SerializedName("versionCode")
    val versionCode: Int = 0,
    
    @SerializedName("fileSize")
    val fileSize: String = "",
    
    @SerializedName("fileType")
    val fileType: String = "apk", // apk, xapk, zip
    
    @SerializedName("category")
    val category: String? = null,
    
    @SerializedName("excerpt")
    val excerpt: String? = null,
    
    @SerializedName("contentRating")
    val contentRating: String = "",
    
    @SerializedName("link")
    val link: String = "",
    
    @SerializedName("created")
    val created: String = ""
)

/**
 * APK Details - matches ApkDetails schema from API specs
 * Used in /details endpoint
 */
data class ApkDetailsDto(
    @SerializedName("uuid")
    val uuid: String? = null, // May be null when querying by package name
    
    @SerializedName("package")
    val packageName: String,
    
    @SerializedName("name")
    val name: String,
    
    @SerializedName("author")
    val author: String,
    
    @SerializedName("icon")
    val icon: String,
    
    @SerializedName("version")
    val version: String? = null, // Not present in details response, get from versions array
    
    @SerializedName("versionCode")
    val versionCode: Int? = null,
    
    @SerializedName("fileSize")
    val fileSize: String? = null, // Not present in details response, get from versions array
    
    @SerializedName("fileType")
    val fileType: String? = null,
    
    @SerializedName("category")
    val category: String? = null,
    
    @SerializedName("excerpt")
    val excerpt: String? = null,
    
    @SerializedName("contentRating")
    val contentRating: String,
    
    @SerializedName("link")
    val link: String,
    
    @SerializedName("created")
    val created: String,
    
    @SerializedName("images")
    val images: List<String>,
    
    @SerializedName("description")
    val description: String,
    
    @SerializedName("rating")
    val rating: String? = null, // Can be empty string or null - parse to Float when needed
    
    @SerializedName("versions")
    val versions: List<ApkVersionDto>
)

/**
 * APK Version - matches ApkVersion schema from API specs
 */
data class ApkVersionDto(
    @SerializedName("version")
    val version: String,
    
    @SerializedName("versionCode")
    val versionCode: Int,
    
    @SerializedName("minSdkLevel")
    val minSdkLevel: String,
    
    @SerializedName("fileType")
    val fileType: String,
    
    @SerializedName("md5")
    val md5: String,
    
    @SerializedName("url")
    val url: String? = null, // May not be present - use /download endpoint instead
    
    @SerializedName("fileSize")
    val fileSize: String,
    
    @SerializedName("architecture")
    val architecture: String,
    
    @SerializedName("changeLog")
    val changeLog: String?,
    
    @SerializedName("trustLevel")
    val trustLevel: Int,
    
    @SerializedName("trustInfo")
    val trustInfo: TrustInfoDto,
    
    @SerializedName("permissions")
    val permissions: List<String>
)

/**
 * Trust Information for APK versions
 */
data class TrustInfoDto(
    @SerializedName("isSigned")
    val isSigned: Boolean,
    
    @SerializedName("signedBy")
    val signedBy: String,
    
    @SerializedName("signerCertificate")
    val signerCertificate: String,
    
    @SerializedName("schema")
    val schema: String,
    
    @SerializedName("existInPlayMarket")
    val existInPlayMarket: Boolean
)

/**
 * Download response - matches /download endpoint response
 */
data class DownloadResponseDto(
    @SerializedName("package")
    val packageName: String,
    
    @SerializedName("uuid")
    val uuid: String? = null, // May be null when using package name
    
    @SerializedName("url")
    val url: String,
    
    @SerializedName("md5")
    val md5: String? = null, // Optional - not present for "request" type
    
    @SerializedName("type")
    val type: String? = null // "download" or "request" - may be null in some error cases
)

/**
 * Update check request - for /updates endpoint
 * Maps package names to their current version codes
 */
data class UpdateCheckRequestDto(
    @SerializedName("apps")
    val apps: Map<String, Int> // packageName -> versionCode
)

/**
 * Update check response - from /updates endpoint
 * Returns update information for apps that have updates available
 */
data class UpdateCheckResponseDto(
    @SerializedName("apps")
    val apps: Map<String, UpdateInfo> // packageName -> UpdateInfo (or empty list if no update)
)

/**
 * Update information for a single app
 * Can be either ApkVersionDto (if update available) or empty object
 */
typealias UpdateInfo = ApkVersionDto?
