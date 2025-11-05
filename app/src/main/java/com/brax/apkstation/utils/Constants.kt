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

    // DTO - TrustInfo
    const val SCHEMA = "schema"
    const val IS_SIGNED = "isSigned"
    const val SIGNED_BY = "signedBy"
    const val SIGNER_CERTIFICATE = "signerCertificate"
    const val EXIST_IN_PLAY_MARKET = "existInPlayMarket"

    // DTO - DBApplication List
    const val APPLICATIONS = "applications"
    const val SECURE = "secure"
    const val GLOBAL = "global"
    const val GROUP = "group"
    const val PERSONAL = "personal"

    // DTO - Locked Policies
    const val LOCKED_APPLICATIONS = "lockedApplications"

    // API - Endpoints
    const val API_METHOD_PUT_APPS_CONFIG = "putAppsConfig"
    const val API_METHOD_GET_APP_LIST = "availableAppsList"
    const val API_METHOD_GET_LOCKED_APPS = "lockedApps"
    const val API_METHOD_GET_APP_DETAILS = "appDetails"

    // API - Link (v2 API)
    const val API_URL = "https://noc-noc.cc/apk/v2/"
}
