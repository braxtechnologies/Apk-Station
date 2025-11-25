package com.brax.apkstation.utils

import android.content.Context
import android.util.Log

object CommonUtils {

    val <T : Any> T.TAG: String
        get() = this.javaClass.simpleName

    fun cleanupInstallationSessions(context: Context) {
        val packageInstaller = context.packageManager.packageInstaller
        for (sessionInfo in packageInstaller.mySessions) {
            try {
                val sessionId = sessionInfo.sessionId
                packageInstaller.abandonSession(sessionInfo.sessionId)
                Log.i(TAG, "Abandoned session id -> $sessionId")
            } catch (_: Exception) {
                Log.e(TAG, "Failed to cleanup installation sessions")
            }
        }
    }
}