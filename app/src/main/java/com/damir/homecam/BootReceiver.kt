package com.damir.homecam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the camera again after the phone reboots or the app is updated, when
 * "Always on" is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Settings(context).autoStart) return

        try {
            CameraService.startInBackground(context)
        } catch (e: Exception) {
            Log.w(TAG, "auto-start refused by the system", e)
            CameraService.notifyStartFailed(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
