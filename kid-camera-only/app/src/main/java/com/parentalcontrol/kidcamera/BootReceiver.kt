package com.parentalcontrol.kidcamera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Listens for BOOT_COMPLETED, MY_PACKAGE_REPLACED, and USER_PRESENT.
 * Restarts CameraStreamService automatically. No screen-share trampoline needed.
 * Also schedules the AlarmManager watchdog.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == "miui.intent.action.BOOT_COMPLETED" ||
                action == "android.huawei.intent.action.BOOT_COMPLETED" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == Intent.ACTION_USER_PRESENT

        if (!relevant) return

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)) return

        Log.d(TAG, "Receiver: $action — starting CameraStreamService")
        try {
            CameraStreamService.start(context)
            WatchdogReceiver.schedule(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
