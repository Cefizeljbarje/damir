package com.damir.homecamviewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes motion alerts after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AlertService.sync(context)
        }
    }
}
