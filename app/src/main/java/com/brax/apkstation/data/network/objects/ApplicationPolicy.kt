package com.brax.apkstation.data.network.objects

import android.os.Parcel
import android.os.Parcelable

@Suppress("ParcelCreator")
class ApplicationPolicy private constructor(
    val url: String?,
    val name: String?,
    val packageName: String,
    val version: String,
    val isForceUpdate: Boolean,
    val downloadState: Int,
    val md5: String?,
    val fileSize: Long,
    val icon: String?,
    val softwarePolicyName: String?,
    val type: Int,
    val fileType: String?,
    val downloadId: Int
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(url)
        parcel.writeString(name)
        parcel.writeString(packageName)
        parcel.writeString(version)
        parcel.writeBoolean(isForceUpdate)
        parcel.writeInt(downloadState)
        parcel.writeString(md5)
        parcel.writeLong(fileSize)
        parcel.writeString(icon)
        parcel.writeString(softwarePolicyName)
        parcel.writeInt(type)
        parcel.writeString(fileType)
        parcel.writeInt(downloadId)
    }
}
