package com.parentalcontrol.kidcamera

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * AlarmManager watchdog — fires every 4 minutes via setExactAndAllowWhileIdle.
 * Restarts CameraStreamService if it's dead, then reschedules itself.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Watchdog fired — checking CameraStreamService")

        val prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SetupActivity.KEY_SETUP_DONE, false)) return

        try {
            CameraStreamService.start(context)
            Log.d(TAG, "CameraStreamService (re)started by watchdog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart CameraStreamService: ${e.message}")
        }

        // Reschedule for next tick
        schedule(context)
    }

    companion object {
        private const val TAG = "WatchdogReceiver"

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = System.currentTimeMillis() + 4 * 60 * 1000L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Watchdog rescheduled in 4 minutes")
        }
    }
}
