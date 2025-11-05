package com.brax.apkstation.data.network

import androidx.annotation.Keep
import com.brax.apkstation.data.network.objects.AppDetails
import com.brax.apkstation.data.network.objects.AppInfo
import com.brax.apkstation.utils.Constants.ACTION
import com.brax.apkstation.utils.Constants.APPLICATIONS
import com.brax.apkstation.utils.Constants.CODE
import com.brax.apkstation.utils.Constants.GLOBAL
import com.brax.apkstation.utils.Constants.GROUP
import com.brax.apkstation.utils.Constants.LOCKED_APPLICATIONS
import com.brax.apkstation.utils.Constants.PERSONAL
import com.brax.apkstation.utils.Constants.RESPONSE
import com.brax.apkstation.utils.Constants.SECURE
import com.brax.apkstation.utils.Constants.SERVER_TIME
import com.google.gson.annotations.SerializedName

data class AvailableAppsListResponse(
    @Keep @SerializedName(SERVER_TIME) var serverTime: String,
    @Keep @SerializedName(ACTION) var action: String,
    @Keep @SerializedName(CODE) var code: Int,
    @Keep @SerializedName(RESPONSE) var response: AvailableAppsListSubResponse
)

data class AvailableAppsListSubResponse(
    @Keep @SerializedName(APPLICATIONS) var applications: ApplicationsListResponse
)

data class ApplicationsListResponse(
    @Keep @SerializedName(SECURE) var secure: List<AppInfo>,
    @Keep @SerializedName(GLOBAL) var global: List<AppInfo>,
    @Keep @SerializedName(GROUP) var group: List<AppInfo>,
    @Keep @SerializedName(PERSONAL) var personal: List<AppInfo>
)

data class AppDetailsResponse(
    @Keep @SerializedName(SERVER_TIME) var serverTime: String,
    @Keep @SerializedName(ACTION) var action: String,
    @Keep @SerializedName(CODE) var code: Int,
    @Keep @SerializedName(RESPONSE) var response: AppDetails
)

data class LockedAppsResponse(
    @Keep @SerializedName(SERVER_TIME) var serverTime: String,
    @Keep @SerializedName(ACTION) var action: String,
    @Keep @SerializedName(CODE) var code: Int,
    @Keep @SerializedName(RESPONSE) var response: LockedAppsListResponse
)

data class LockedAppsListResponse(
    @Keep @SerializedName(LOCKED_APPLICATIONS) var lockedApplications: List<String>,
)

data class PutAppConfigResponse(
    @Keep @SerializedName(SERVER_TIME) var serverTime: String,
    @Keep @SerializedName(ACTION) var action: String,
    @Keep @SerializedName(CODE) var code: Int,
    @Keep @SerializedName(RESPONSE) var response: String
)
