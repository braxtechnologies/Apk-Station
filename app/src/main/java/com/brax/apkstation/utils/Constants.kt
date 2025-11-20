package com.brax.apkstation.utils

import androidx.datastore.preferences.core.booleanPreferencesKey

object Constants {

    // DataStore
    val SHOULD_SHOW_PERMISSION_SCREEN_KEY = booleanPreferencesKey("should_show_permission_screen")
    val ENABLE_FAVORITES_KEY = booleanPreferencesKey("enable_favorites")

    // DTO - Global
    const val SERVER_TIME = "serverTime"
    const val ACTION = "action"
    const val CODE = "code"
    const val RESPONSE = "response"

    // DTO - DBApplication List
    const val APPLICATIONS = "applications"
    const val SECURE = "secure"
    const val GLOBAL = "global"
    const val GROUP = "group"
    const val PERSONAL = "personal"

    // DTO - Locked Policies
    const val LOCKED_APPLICATIONS = "lockedApplications"

    // API - Link
    // Note: This constant is no longer used directly.
    // API URL is now resolved dynamically via SRV record: "_https._tcp.api.braxtech.net"
    // Fallback: https://api.braxtech.net/apk/
    @Deprecated("Use SrvResolver.resolveApiUrl() instead", ReplaceWith("SrvResolver.resolveApiUrl()"))
    const val API_URL = "https://api.braxtech.net/apk/"
}
