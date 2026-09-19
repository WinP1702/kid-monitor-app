package com.parentalcontrol.kidmonitor

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Handles all Supabase Postgres (REST) operations for the kid device.
 * Replaces FirebaseManager — uses Supabase PostgREST over OkHttp HTTP.
 *
 * Supabase tables used:
 *   devices     → device registration, heartbeat, camera status
 *   live_frames → single Base64 JPEG frame per device (upserted/overwritten)
 *   app_usage   → per-day per-app usage stats
 */
class SupabaseManager(
    private val deviceId: String,
    private val pairingKey: String
) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()

    // ─── Device Registration ──────────────────────────────────────────
    suspend fun registerDevice(model: String, manufacturer: String) {
        val body = JSONObject().apply {
            put("id", deviceId)
            put("pairing_key", pairingKey)
            put("model", model)
            put("manufacturer", manufacturer)
            put("last_seen", System.currentTimeMillis())
            put("service_alive", true)
            put("capture_status", "starting")
        }
        upsert("devices", body)
    }

    // ─── Heartbeat / Status ───────────────────────────────────────────
    suspend fun writeStatus(captureStatus: String) {
        val body = JSONObject().apply {
            put("id", deviceId)
            put("pairing_key", pairingKey)
            put("last_seen", System.currentTimeMillis())
            put("service_alive", true)
            put("capture_status", captureStatus)
        }
        upsert("devices", body)
    }

    // ─── Camera Status ────────────────────────────────────────────────
    suspend fun writeCameraStatus(streaming: Boolean, front: Boolean) {
        val body = JSONObject().apply {
            put("id", deviceId)
            put("pairing_key", pairingKey)
            put("streaming", streaming)
            put("current_front", front)
            put("last_seen", System.currentTimeMillis())
        }
        upsert("devices", body)
    }

    // ─── Live Frame Upload ────────────────────────────────────────────
    // Stores a single compressed Base64 JPEG frame — always UPSERTED (overwritten).
    // ~3–10 KB per frame. device_id is the primary key so it never grows.
    suspend fun pushLiveFrame(jpegBytes: ByteArray) {
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("data", base64)
            put("size_bytes", jpegBytes.size)
            put("ts", System.currentTimeMillis())
        }
        upsert("live_frames", body)
        // Also bump last_seen heartbeat
        writeStatus("WebRTC ready")
        Log.d(TAG, "Live frame pushed: ${jpegBytes.size / 1024}KB")
    }

    // ─── Usage Stats Upload ───────────────────────────────────────────
    suspend fun uploadUsageStats(stats: List<UsageStatsHelper.AppUsageRecord>) {
        if (stats.isEmpty()) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        // Deduplicate by package name — keep the record with the highest usage time.
        // UsageStatsHelper can return duplicate packages; Postgres rejects upserts that
        // would update the same row twice in a single batch ("ON CONFLICT DO UPDATE
        // command cannot affect row a second time").
        val deduped = stats
            .groupBy { it.packageName }
            .map { (_, records) -> records.maxByOrNull { it.totalTimeMs }!! }

        val arr = JSONArray()
        deduped.forEach { record ->
            arr.put(JSONObject().apply {
                put("device_id", deviceId)
                put("pairing_key", pairingKey)
                put("date", today)
                put("package_name", record.packageName)
                put("app_name", record.appName)
                put("total_minutes", record.totalTimeMs / 60_000)
                put("last_used", record.lastUsedMs)
            })
        }
        // on_conflict tells PostgREST to upsert on the composite unique key,
        // not the serial pk — avoids "duplicate key" errors on repeated uploads
        upsertArray("app_usage?on_conflict=device_id,date,package_name", arr)
        Log.d(TAG, "Usage stats uploaded: ${deduped.size} apps (from ${stats.size} raw)")
    }


    // ─── REST helpers ─────────────────────────────────────────────────

    /** POST with Prefer: resolution=merge-duplicates (upsert by primary key) */
    private suspend fun upsert(table: String, body: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/$table")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().toRequestBody(JSON_MT))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "upsert $table failed: ${resp.code} ${resp.body?.string()?.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upsert $table exception: ${e.message}")
        }
    }

    /** POST array with merge-duplicates — used for batch usage stats */
    private suspend fun upsertArray(table: String, arr: JSONArray) = withContext(Dispatchers.IO) {
        if (arr.length() == 0) return@withContext
        try {
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/$table")
                .header("apikey", supabaseKey)
                .header("Authorization", "Bearer $supabaseKey")
                .header("Content-Type", "application/json")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(arr.toString().toRequestBody(JSON_MT))
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "upsertArray $table failed: ${resp.code} ${resp.body?.string()?.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "upsertArray $table exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SupabaseManager"
    }
}
