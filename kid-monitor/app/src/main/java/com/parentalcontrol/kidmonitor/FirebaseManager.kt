package com.parentalcontrol.kidmonitor

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles all Firebase Realtime DB operations for the kid device.
 * NO Firebase Storage required — screenshots stored as Base64 in RTDB.
 *
 * Firebase Structure:
 * /devices/{deviceId}/
 *   info/         → device name, model, last seen
 *   liveFrame/    → { data: "<base64-jpeg>", ts: <timestamp> }  ← always overwritten
 *   appUsage/     → per-day per-app usage stats
 *   events/       → foreground/background app events
 */
class FirebaseManager(private val context: Context, private val deviceId: String) {

    private val db = FirebaseDatabase.getInstance()
    private val deviceRef = db.getReference("devices/$deviceId")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ─── Device Registration ──────────────────────────────────────────
    suspend fun registerDevice(model: String, manufacturer: String) {
        try {
            deviceRef.child("info").setValue(
                mapOf(
                    "deviceId" to deviceId,
                    "model" to model,
                    "manufacturer" to manufacturer,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "timezone" to TimeZone.getDefault().id
                )
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice failed", e)
        }
    }

    // ─── Live Frame Upload (replaces Firebase Storage screenshots) ────
    // Stores a single compressed Base64 JPEG frame in RTDB.
    // This node is always OVERWRITTEN so it never grows unboundedly.
    // ~3-10 KB per frame is well within RTDB free-tier limits.
    suspend fun pushLiveFrame(jpegBytes: ByteArray) {
        try {
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            deviceRef.child("liveFrame").setValue(
                mapOf(
                    "data" to base64,
                    "ts" to ServerValue.TIMESTAMP,
                    "sizeBytes" to jpegBytes.size
                )
            ).await()
            // Update heartbeat
            deviceRef.child("info/lastSeen").setValue(ServerValue.TIMESTAMP).await()
            Log.d(TAG, "Live frame pushed: ${jpegBytes.size / 1024}KB")
        } catch (e: Exception) {
            Log.e(TAG, "pushLiveFrame failed", e)
        }
    }

    // ─── Status / Diagnostic ──────────────────────────────────────────
    // Written every capture cycle so parent can see service heartbeat.
    suspend fun writeStatus(captureStatus: String) {
        try {
            deviceRef.child("info").updateChildren(
                mapOf(
                    "lastSeen"     to ServerValue.TIMESTAMP,
                    "serviceAlive" to true,
                    "captureStatus" to captureStatus
                )
            ).await()
        } catch (e: Exception) {
            Log.e(TAG, "writeStatus failed", e)
        }
    }

    // ─── Usage Stats Upload ───────────────────────────────────────────
    suspend fun uploadUsageStats(stats: List<UsageStatsHelper.AppUsageRecord>) {
        if (stats.isEmpty()) return
        try {
            val today = dateFormat.format(Date())
            val usageMap = stats.associate { record ->
                val key = record.packageName.replace(".", "_")
                key to mapOf(
                    "packageName" to record.packageName,
                    "appName" to record.appName,
                    "totalTimeMinutes" to record.totalTimeMs / 60_000,
                    "lastUsed" to record.lastUsedMs
                )
            }
            deviceRef.child("appUsage/$today").setValue(usageMap).await()
        } catch (e: Exception) {
            Log.e(TAG, "uploadUsageStats failed", e)
        }
    }

    // ─── App Events Upload ────────────────────────────────────────────
    suspend fun uploadAppEvents(events: List<UsageStatsHelper.ActiveAppEvent>) {
        if (events.isEmpty()) return
        try {
            val eventsRef = deviceRef.child("events")
            events.forEach { event ->
                eventsRef.push().setValue(
                    mapOf(
                        "packageName" to event.packageName,
                        "appName" to event.appName,
                        "timestamp" to event.timestamp,
                        "type" to event.eventType
                    )
                ).await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAppEvents failed", e)
        }
    }

    companion object {
        private const val TAG = "FirebaseManager"
    }
}
