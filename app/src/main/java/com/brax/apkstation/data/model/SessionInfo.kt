package com.brax.apkstation.data.model

/**
 * Information about a PackageInstaller session
 */
data class SessionInfo(
    val sessionId: Int,
    val packageName: String,
    val versionCode: Long,
    val displayName: String = ""
)

