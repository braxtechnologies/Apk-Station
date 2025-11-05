package com.brax.apkstation.data.room.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class App(
    var name: String = String(),
    var version: String = String(),
    var packageName: String = String(),
    var icon: String? = String(),
    var type: Int = 2,
    var fileType: String = String(),
    var softwarePolicyName: String? = String(),
    var isInstalled: Boolean = false,
) : Parcelable {

    override fun hashCode(): Int {
        return packageName.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            is App -> packageName == other.packageName
            else -> false
        }
    }
}
