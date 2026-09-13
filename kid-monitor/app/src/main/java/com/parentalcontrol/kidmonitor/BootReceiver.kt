package com.parentalcontrol.kidmonitor

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for BOOT_COMPLETED and restarts the MonitorService.
 * Since MediaProjection cannot be reused across reboots, it launches
 * ProjectionRequestActivity (a transparent trampoline) to re-request permission.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)

        if (isSetupDone) {
            // Launch transparent activity to re-request MediaProjection
            val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(trampolineIntent)

            // Start camera stream service (listens for parent camera commands)
            CameraStreamService.start(context)
        }
    }
}
