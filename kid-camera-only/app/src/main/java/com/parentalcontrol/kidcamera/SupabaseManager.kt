package com.parentalcontrol.kidcamera

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles Supabase Postgres (REST) operations for the kid camera device.
 * Only needs device heartbeat + camera status (no usage stats, no live frames).
 *
 * Supabase tables used:
 *   devices → device registration, heartbeat, camera status
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
            put("capture_status", "camera-only")
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

    // ─── REST helper ──────────────────────────────────────────────────
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

    companion object {
        private const val TAG = "SupabaseManager"
    }
}
