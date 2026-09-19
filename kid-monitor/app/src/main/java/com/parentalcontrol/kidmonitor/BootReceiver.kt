package com.parentalcontrol.kidmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for:
 *  - BOOT_COMPLETED    → restart services after device reboot
 *  - MY_PACKAGE_REPLACED → restart services after APK update
 *    (Android 10+ grants background-activity-launch exemption for this action)
 *
 * Screen share requires a fresh MediaProjection grant on every restart,
 * so we launch ProjectionRequestActivity (transparent) to silently re-ask.
 * CameraStreamService restarts automatically (reads prefs, no permission needed).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot && !isUpdate) return

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)
        if (!isSetupDone) return

        // Restart camera service — no special permission needed, reads from prefs
        CameraStreamService.start(context)

        // Restart screen service — needs fresh MediaProjection grant from user
        // MY_PACKAGE_REPLACED and BOOT_COMPLETED have background-activity-launch exemption
        val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(trampolineIntent)
    }
}
