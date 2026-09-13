package com.parentalcontrol.parentview

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight Supabase Realtime client using OkHttp WebSocket.
 * Implements the Phoenix socket protocol used by Supabase Realtime.
 * Used exclusively for WebRTC signaling (offer/answer/ICE candidates).
 */
class SupabaseSignaling(
    supabaseUrl: String,
    private val supabaseKey: String,
    channelId: String
) {
    private val channelTopic = "realtime:$channelId"
    private val wsUrl = supabaseUrl
        .replace("https://", "wss://")
        .replace("http://", "ws://") +
        "/realtime/v1/websocket?apikey=$supabaseKey&vsn=1.0.0"

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val refCounter = AtomicInteger(1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onMessage: ((event: String, payload: JSONObject) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect() {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WS connected → joining $channelTopic")
                ws.send(JSONObject().apply {
                    put("event", "phx_join")
                    put("topic", channelTopic)
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("broadcast", JSONObject().apply {
                                put("ack", false)
                                put("self", false)
                            })
                        })
                    })
                    put("ref", refCounter.getAndIncrement().toString())
                    put("join_ref", "1")
                }.toString())

                scope.launch {
                    while (isActive) {
                        delay(25_000)
                        ws.send(JSONObject().apply {
                            put("event", "heartbeat")
                            put("topic", "phoenix")
                            put("payload", JSONObject())
                            put("ref", refCounter.getAndIncrement().toString())
                        }.toString())
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("event")) {
                        "phx_reply" -> {
                            if (json.optJSONObject("payload")?.optString("status") == "ok") {
                                Log.d(TAG, "Channel joined ✓")
                                onConnected?.invoke()
                            }
                        }
                        "broadcast" -> {
                            val payload = json.optJSONObject("payload") ?: return
                            val event   = payload.optString("event")
                            val data    = payload.optJSONObject("payload") ?: return
                            Log.d(TAG, "← Broadcast: $event")
                            onMessage?.invoke(event, data)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS error: ${t.message}")
                onDisconnected?.invoke()
                scope.launch {
                    delay(5_000)
                    if (isActive) connect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                onDisconnected?.invoke()
            }
        })
    }

    fun send(event: String, payload: JSONObject) {
        val msg = JSONObject().apply {
            put("event", "broadcast")
            put("topic", channelTopic)
            put("payload", JSONObject().apply {
                put("type", "broadcast")
                put("event", event)
                put("payload", payload)
            })
            put("ref", refCounter.getAndIncrement().toString())
        }
        webSocket?.send(msg.toString())
        Log.d(TAG, "→ Broadcast: $event")
    }

    fun disconnect() {
        scope.cancel()
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    companion object {
        private const val TAG = "SupabaseSignaling"
    }
}
