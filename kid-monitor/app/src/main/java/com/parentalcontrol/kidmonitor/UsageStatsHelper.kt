package com.parentalcontrol.kidmonitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Tracks which apps the child is using via UsageStatsManager.
 */
class UsageStatsHelper(private val context: Context) {

    private val usageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }
    private val packageManager: PackageManager = context.packageManager

    data class AppUsageRecord(
        val packageName: String,
        val appName: String,
        val totalTimeMs: Long,
        val lastUsedMs: Long
    )

    data class ActiveAppEvent(
        val packageName: String,
        val appName: String,
        val timestamp: Long,
        val eventType: String   // "MOVE_TO_FOREGROUND" | "MOVE_TO_BACKGROUND"
    )

    /**
     * Returns per-app usage stats for the last 24 hours.
     */
    fun getUsageStats(): List<AppUsageRecord> {
        val now = System.currentTimeMillis()
        val dayAgo = now - 24 * 60 * 60 * 1000L

        return try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, dayAgo, now
            )
            stats
                .filter { it.totalTimeInForeground > 5000 }  // At least 5 seconds
                .map { stat ->
                    AppUsageRecord(
                        packageName = stat.packageName,
                        appName = getAppName(stat.packageName),
                        totalTimeMs = stat.totalTimeInForeground,
                        lastUsedMs = stat.lastTimeUsed
                    )
                }
                .sortedByDescending { it.totalTimeMs }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Returns recent app events (foreground/background transitions) for the last hour.
     */
    fun getRecentAppEvents(): List<ActiveAppEvent> {
        val now = System.currentTimeMillis()
        val hourAgo = now - 60 * 60 * 1000L

        val events = mutableListOf<ActiveAppEvent>()
        val usageEvents = usageStatsManager.queryEvents(hourAgo, now)
        val event = UsageEvents.Event()

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val type = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> "FOREGROUND"
                UsageEvents.Event.MOVE_TO_BACKGROUND -> "BACKGROUND"
                else -> null
            }
            if (type != null) {
                events.add(
                    ActiveAppEvent(
                        packageName = event.packageName,
                        appName = getAppName(event.packageName),
                        timestamp = event.timeStamp,
                        eventType = type
                    )
                )
            }
        }
        return events
    }

    /**
     * Get the currently foreground app (best effort).
     */
    fun getCurrentForegroundApp(): String? {
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - 60_000, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName  // Fallback to package name
        }
    }
}
