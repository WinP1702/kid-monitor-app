package com.parentalcontrol.parentview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.*

/**
 * Parent-side WebRTC camera client.
 * Channel: "camera-{pairingKey}-{deviceId}"
 *
 * Uses VANILLA ICE (GATHER_ONCE):
 *   - Answer is sent only after all ICE candidates are gathered and embedded in SDP.
 *   - Zero ice-* messages over Realtime — avoids "too many messages" rate limit.
 */
class WebRTCCameraParentClient(
    private val context: Context,
    private val deviceId: String,
    private val pairingKey: String,
    private val renderer: SurfaceViewRenderer
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var eglBase: EglBase? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isConnected = false
    private var requestJob: Job? = null

    @Volatile private var answerSent = false

    var onStatusChanged: ((String) -> Unit)? = null

    private val signaling = SupabaseSignaling(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY,
        channelId   = "camera-$pairingKey-$deviceId"
    )

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    // ─── Start ────────────────────────────────────────────────────────
    fun start() {
        initWebRTC()
        setupSignaling()
    }

    private fun initWebRTC() {
        eglBase = EglBase.create()

        val initOpts = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(HardwareVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(HardwareVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
    }

    // ─── Create PeerConnection (answerer, vanilla ICE) ────────────────
    private fun createPeerConnection() {
        answerSent = false

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // VANILLA ICE: gather all candidates once, embed in answer SDP
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // Vanilla ICE: candidates embedded in SDP, not sent individually
            }

            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                // Send answer only after ALL local candidates are gathered
                if (s == PeerConnection.IceGatheringState.COMPLETE && !answerSent) {
                    answerSent = true
                    val sdp = pc?.localDescription?.description ?: return
                    signaling.send("answer", JSONObject().apply { put("sdp", sdp) })
                    Log.d(TAG, "Camera answer sent (vanilla ICE)")
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    mainHandler.post {
                        track.addSink(renderer)
                        onStatusChanged?.invoke("Camera live")
                    }
                }
            }

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                val label = when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        isConnected = true
                        "Camera live"
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        isConnected = false
                        "Disconnected"
                    }
                    PeerConnection.PeerConnectionState.FAILED -> {
                        isConnected = false
                        "Connection failed"
                    }
                    else -> s?.name ?: "..."
                }
                mainHandler.post { onStatusChanged?.invoke(label) }
            }

            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<MediaStream>?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
        })
    }

    // ─── Handle incoming offer from kid ───────────────────────────────
    private fun handleOffer(sdpStr: String) {
        // Always create a fresh PeerConnection for each offer.
        // A stale/closed PC from a previous failed attempt would
        // silently reject setRemoteDescription.
        pc?.close()
        pc = null
        createPeerConnection()

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
        pc?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set — creating camera answer")
                pc?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local answer set — waiting for ICE gathering")
                                // Answer sent from onIceGatheringChange(COMPLETE)
                            }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                        }, answer)
                    }
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer fail: $e") }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(e: String?) {}
                }, MediaConstraints())
            }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemoteOffer fail: $e") }
        }, offer)
    }

    // ─── Retry request until kid answers ──────────────────────────────
    private fun sendRequestWithRetry() {
        requestJob?.cancel()
        requestJob = scope.launch {
            while (isActive && !isConnected) {
                Log.d(TAG, "Sending camera request to kid")
                signaling.send("request", JSONObject().apply { put("front", false) })
                delay(8_000L)
            }
        }
    }

    // ─── Signaling ────────────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "offer" -> {
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotEmpty()) handleOffer(sdp)
                }
                "status" -> {
                    val msg = payload.optString("msg")
                    mainHandler.post { onStatusChanged?.invoke(msg) }
                }
                "camera-switched" -> {
                    val front = payload.optBoolean("front", false)
                    val label = if (front) "Front camera" else "Back camera"
                    mainHandler.post { onStatusChanged?.invoke(label) }
                }
                // No ice-kid handler needed — vanilla ICE embeds all candidates in SDP
            }
        }

        signaling.onConnected = {
            Log.d(TAG, "Camera signaling connected — requesting stream")
            mainHandler.post { onStatusChanged?.invoke("Requesting camera...") }
            sendRequestWithRetry()
        }

        signaling.connect()
    }

    // ─── Switch camera ────────────────────────────────────────────────
    fun switchCamera(front: Boolean) {
        signaling.send("switch", JSONObject().apply { put("front", front) })
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        isConnected = true
        scope.cancel()
        signaling.send("stop", JSONObject())
        signaling.disconnect()
        pc?.close()
        factory?.dispose()
        try { renderer.release() } catch (_: Exception) {}
        eglBase?.release()
    }

    companion object { private const val TAG = "WebRTCCameraParent" }
}
