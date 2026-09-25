package com.parentalcontrol.kidmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for:
 *  - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED → restart services after device reboot
 *  - MY_PACKAGE_REPLACED → restart services after APK update
 *    (Android 10+ grants background-activity-launch exemption for this action)
 *  - USER_PRESENT → screen unlocked; restart services if battery optimization killed them
 *  - Manufacturer boot actions (MIUI, Huawei, HTC QuickBoot)
 *
 * Screen share requires a fresh MediaProjection grant on every restart,
 * so we launch ProjectionRequestActivity (transparent) to silently re-ask.
 * CameraStreamService restarts automatically (reads prefs, no permission needed).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == "miui.intent.action.BOOT_COMPLETED" ||
                action == "android.huawei.intent.action.BOOT_COMPLETED"
        val isUpdate   = action == Intent.ACTION_MY_PACKAGE_REPLACED
        val isUnlocked = action == Intent.ACTION_USER_PRESENT

        if (!isBoot && !isUpdate && !isUnlocked) return

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)
        if (!isSetupDone) return

        // Always restart camera service — no special permission needed, reads from prefs
        CameraStreamService.start(context)

        // For USER_PRESENT (screen unlock), only restart screen service if it is not running.
        // Avoids re-prompting for MediaProjection every time the user unlocks their phone.
        if (isUnlocked) {
            if (!MonitorService.isRunning) {
                val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(trampolineIntent)
            }
            return
        }

        // Boot / update: always request fresh MediaProjection grant.
        // BOOT_COMPLETED and MY_PACKAGE_REPLACED have background-activity-launch exemption.
        val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(trampolineIntent)
    }
}
