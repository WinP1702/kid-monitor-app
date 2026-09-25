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
 *
 * KEY FIX: The heartbeat reply ALSO produces event="phx_reply" with status="ok".
 * We distinguish the join reply from heartbeat replies by checking that
 * topic == channelTopic (join reply) vs topic == "phoenix" (heartbeat reply).
 * Without this, onConnected fires every 25s, spawning a new sendRequestWithRetry()
 * coroutine each time, flooding the kid and preventing ICE from completing.
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

    // Scope is recreated each time connect() is called so that reconnects work
    // correctly even after disconnect() was previously called (which cancels the old scope).
    @Volatile private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var joined = false
    private var heartbeatJob: Job? = null

    var onMessage: ((event: String, payload: JSONObject) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect() {
        // Cancel the old scope and create a fresh one so coroutines launched here
        // (heartbeat, reconnect delay) always have an active scope to run in.
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        joined = false
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $supabaseKey")
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WS connected -> joining $channelTopic")
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

                heartbeatJob?.cancel()
                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(25_000)
                        val sent = ws.send(JSONObject().apply {
                            put("event", "heartbeat")
                            put("topic", "phoenix")
                            put("payload", JSONObject())
                            put("ref", refCounter.getAndIncrement().toString())
                        }.toString())
                        if (!sent) break
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val event = json.optString("event")
                    val topic = json.optString("topic")

                    when (event) {
                        "phx_reply" -> {
                            val status = json.optJSONObject("payload")?.optString("status")
                            // Only fire onConnected for the CHANNEL JOIN reply (topic=channelTopic).
                            // Heartbeat replies have topic="phoenix" - ignore those.
                            if (status == "ok" && topic == channelTopic && !joined) {
                                joined = true
                                Log.d(TAG, "Channel joined ok")
                                onConnected?.invoke()
                            }
                        }
                        "broadcast" -> {
                            val payload = json.optJSONObject("payload") ?: return
                            val broadcastEvent = payload.optString("event")
                            val data = payload.optJSONObject("payload") ?: return
                            Log.d(TAG, "<- Broadcast: $broadcastEvent")
                            onMessage?.invoke(broadcastEvent, data)
                        }
                        "phx_error" -> Log.e(TAG, "Channel error: $text")
                        "phx_close" -> Log.w(TAG, "Channel closed by server")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS error: ${t.message}")
                joined = false
                onDisconnected?.invoke()
                scope.launch {
                    delay(5_000)
                    if (isActive) connect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS closed (code=$code): $reason — reconnecting in 5s")
                joined = false
                onDisconnected?.invoke()
                // Supabase Realtime closes the socket with code 1000 on server-side
                // idle timeout (~60 s without a heartbeat) or after a network blip.
                // Without reconnecting here the signaling channel stays dead forever.
                scope.launch {
                    delay(5_000)
                    if (isActive) connect()
                }
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
        Log.d(TAG, "-> Broadcast: $event")
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope.cancel()
        webSocket?.close(1000, "bye")
        webSocket = null
    }

    companion object {
        private const val TAG = "SupabaseSignaling"
    }
}
