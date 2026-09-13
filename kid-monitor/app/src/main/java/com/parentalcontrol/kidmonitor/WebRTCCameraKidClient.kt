package com.parentalcontrol.kidmonitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Kid-side WebRTC camera client.
 * Captures from front/back camera and streams to parent via WebRTC P2P.
 * Channel: "camera-{pairingKey}-{deviceId}"
 *
 * The parent sends "request" → kid creates offer → parent answers → P2P stream starts.
 * The parent can send "switch" → kid switches camera (front ↔ back).
 */
class WebRTCCameraKidClient(
    private val context: Context,
    private val deviceId: String,
    private val pairingKey: String,
    private var facingFront: Boolean = false   // false = back camera
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: Camera2Capturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()

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

    // ─── WebRTC init ──────────────────────────────────────────────────
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
    }

    // ─── Resolve camera ID ────────────────────────────────────────────
    private fun getCameraId(): String {
        val enumerator = Camera2Enumerator(context)
        val cameras = enumerator.deviceNames
        for (id in cameras) {
            if (facingFront && enumerator.isFrontFacing(id)) return id
            if (!facingFront && enumerator.isBackFacing(id)) return id
        }
        return cameras.firstOrNull() ?: ""
    }

    // ─── Create PeerConnection + video track ──────────────────────────
    private fun setupPeerConnectionWithVideo(): Boolean {
        return try {
            val cameraId = getCameraId()
            if (cameraId.isEmpty()) {
                Log.e(TAG, "No camera found")
                return false
            }

            capturer = Camera2Capturer(context, cameraId, null)
            val videoSource = factory!!.createVideoSource(false /* not screencast */)
            surfaceTextureHelper = SurfaceTextureHelper.create("CamThread", eglBase!!.eglBaseContext)
            capturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer!!.startCapture(640, 480, 30)
            Log.d(TAG, "Camera capture started: $cameraId (front=$facingFront)")

            val videoTrack = factory!!.createVideoTrack("cam0", videoSource)

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    signaling.send("ice-kid", JSONObject().apply {
                        put("candidate",    c.sdp)
                        put("sdpMid",       c.sdpMid)
                        put("sdpMLineIndex", c.sdpMLineIndex)
                    })
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Connection: $s")
                    signaling.send("status", JSONObject().apply { put("msg", s?.name ?: "unknown") })
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

            pc?.addTrack(videoTrack)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setupPeerConnection failed: ${e.message}", e)
            false
        }
    }

    // ─── Create & send offer ──────────────────────────────────────────
    private fun createAndSendOffer() {
        pendingRemoteIce.clear()
        if (!setupPeerConnectionWithVideo()) {
            Log.e(TAG, "Cannot create offer")
            return
        }
        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signaling.send("offer", JSONObject().apply { put("sdp", sdp.description) })
                    }
                    override fun onCreateSuccess(s: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) { Log.e(TAG, "createOffer fail: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    // ─── Switch camera (hot switch without full reconnect) ────────────
    fun switchCamera(front: Boolean) {
        facingFront = front
        if (capturer == null) return
        val enumerator = Camera2Enumerator(context)
        val newId = getCameraId()
        if (newId.isNotEmpty()) {
            capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    Log.d(TAG, "Camera switched: front=$isFront")
                    signaling.send("camera-switched", JSONObject().apply { put("front", isFront) })
                }
                override fun onCameraSwitchError(error: String?) {
                    Log.e(TAG, "Camera switch error: $error")
                }
            }, newId)
        }
    }

    // ─── Signaling ────────────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "request" -> {
                    Log.d(TAG, "Parent requested camera stream")
                    pc?.close()
                    releaseCamera()
                    pc = null
                    createAndSendOffer()
                }
                "switch" -> {
                    val front = payload.optBoolean("front", false)
                    Log.d(TAG, "Parent requested camera switch: front=$front")
                    switchCamera(front)
                }
                "answer" -> {
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotEmpty() && pc != null) {
                        pc!!.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
                                pendingRemoteIce.clear()
                            }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
                        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                }
                "ice-parent" -> {
                    val candidate = IceCandidate(
                        payload.optString("sdpMid"),
                        payload.optInt("sdpMLineIndex"),
                        payload.optString("candidate")
                    )
                    if (pc?.remoteDescription != null) pc?.addIceCandidate(candidate)
                    else pendingRemoteIce.add(candidate)
                }
            }
        }

        signaling.onConnected = { Log.d(TAG, "Camera signaling ready") }
        signaling.connect()
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        try { releaseCamera() } catch (e: Exception) { }
        pc?.close()
        factory?.dispose()
        eglBase?.release()
        signaling.disconnect()
    }

    private fun releaseCamera() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    companion object { private const val TAG = "WebRTCCameraKidClient" }
}
