package com.parentalcontrol.parentview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Parent-side WebRTC camera client.
 * Channel: "camera-{pairingKey}-{deviceId}"
 *
 * Sends "request" → kid starts camera stream.
 * Sends "switch"  → kid switches front/back camera.
 * Receives video track → renders on SurfaceViewRenderer.
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

    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val mainHandler = Handler(Looper.getMainLooper())

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

    // ─── Signaling ────────────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "offer" -> {
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotEmpty()) handleOffer(sdp)
                }
                "ice-kid" -> {
                    val candidate = IceCandidate(
                        payload.optString("sdpMid"),
                        payload.optInt("sdpMLineIndex"),
                        payload.optString("candidate")
                    )
                    if (pc?.remoteDescription != null) pc?.addIceCandidate(candidate)
                    else pendingRemoteIce.add(candidate)
                }
                "status" -> {
                    val msg = payload.optString("msg")
                    mainHandler.post { onStatusChanged?.invoke(msg) }
                }
                "camera-switched" -> {
                    val front = payload.optBoolean("front", false)
                    val label = if (front) "📷 Front camera" else "📷 Back camera"
                    mainHandler.post { onStatusChanged?.invoke(label) }
                }
            }
        }

        signaling.onConnected = {
            Log.d(TAG, "Camera signaling connected — requesting stream")
            mainHandler.post { onStatusChanged?.invoke("Connecting...") }
            // Request stream from kid (default back camera)
            signaling.send("request", JSONObject().apply { put("front", false) })
        }

        signaling.connect()
    }

    private fun handleOffer(sdpStr: String) {
        pendingRemoteIce.clear()
        setupPeerConnection()

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdpStr)
        pc?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
                pendingRemoteIce.clear()
                createAndSendAnswer()
            }
            override fun onCreateSuccess(s: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
        }, offer)
    }

    private fun setupPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signaling.send("ice-parent", JSONObject().apply {
                    put("candidate",    c.sdp)
                    put("sdpMid",       c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                })
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    mainHandler.post {
                        track.addSink(renderer)
                        onStatusChanged?.invoke("📷 Camera live")
                    }
                }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                val label = when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED    -> "📷 Camera live"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "Disconnected"
                    PeerConnection.PeerConnectionState.FAILED       -> "Connection failed"
                    else -> s?.name ?: "..."
                }
                mainHandler.post { onStatusChanged?.invoke(label) }
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<MediaStream>?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
        })
    }

    private fun createAndSendAnswer() {
        pc?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signaling.send("answer", JSONObject().apply { put("sdp", sdp.description) })
                    }
                    override fun onCreateSuccess(s: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer fail: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    // ─── Switch camera ────────────────────────────────────────────────
    fun switchCamera(front: Boolean) {
        signaling.send("switch", JSONObject().apply { put("front", front) })
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        // Tell kid to stop streaming
        signaling.send("stop", JSONObject())

        pc?.close()
        factory?.dispose()
        try { renderer.release() } catch (_: Exception) {}
        eglBase?.release()
        signaling.disconnect()
    }

    companion object { private const val TAG = "WebRTCCameraParent" }
}
