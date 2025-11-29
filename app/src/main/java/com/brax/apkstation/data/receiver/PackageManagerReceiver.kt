package com.brax.apkstation.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brax.apkstation.app.android.StoreApplication
import com.brax.apkstation.data.event.InstallerEvent
import com.brax.apkstation.utils.CommonUtils.TAG

/**
 * Broadcast receiver to handle package installation and uninstallation events
 */
open class PackageManagerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val packageName = intent.data?.schemeSpecificPart ?: return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    Log.i(TAG, "Package installed: $packageName")
                    StoreApplication.events.send(InstallerEvent.Installed(packageName))
                }
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.i(TAG, "Package updated: $packageName")
                StoreApplication.events.send(InstallerEvent.Installed(packageName))
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    Log.i(TAG, "Package uninstalled: $packageName")
                    StoreApplication.events.send(InstallerEvent.Uninstalled(packageName))
                }
            }
        }
    }
}




