package com.parentalcontrol.kidmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Listens for:
 *  - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED → restart services after device reboot
 *  - MY_PACKAGE_REPLACED → restart services after APK update
 *  - USER_PRESENT → screen unlocked; restart services if battery optimization killed them
 *  - Manufacturer boot actions (MIUI, Huawei, HTC QuickBoot)
 *
 * Also starts the AlarmManager watchdog so it fires every 4 minutes even overnight.
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

        // Kick off the watchdog so it fires every 4 min and keeps services alive long-term
        MonitorService.scheduleWatchdog(context)

        // For USER_PRESENT (screen unlock), only show the projection dialog if MonitorService
        // is definitively not running. MonitorService.isRunning is reliable within a process
        // lifetime; after process death it resets to false — which is exactly what we want.
        if (isUnlocked) {
            if (!MonitorService.isRunning) {
                launchProjectionActivity(context)
            }
            return
        }

        // Boot / update: always request fresh MediaProjection grant.
        // BOOT_COMPLETED and MY_PACKAGE_REPLACED have background-activity-launch exemption.
        launchProjectionActivity(context)
    }

    private fun launchProjectionActivity(context: Context) {
        try {
            val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(trampolineIntent)
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Failed to launch ProjectionRequestActivity: ${e.message}")
        }
    }
}
