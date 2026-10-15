package com.parentalcontrol.kidmonitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Kid-side WebRTC camera client.
 * Channel: "camera-{pairingKey}-{deviceId}"
 *
 * Uses VANILLA ICE (GATHER_ONCE):
 *   - All candidates embedded in the offer SDP before sending.
 *   - Zero ice-* messages over Realtime — avoids rate limits.
 *   - 3 messages total per session: request -> offer -> answer.
 *
 * Pro feature: Remote mic control — parent sends "mic-on"/"mic-off" via signaling;
 * this client enables/disables the local AudioTrack accordingly.
 */
class WebRTCCameraKidClient(
    private val context: Context,
    private val deviceId: String,
    private val pairingKey: String,
    private var facingFront: Boolean = false
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: Camera2Capturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    // ─── Audio ────────────────────────────────────────────────────────
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var isMicEnabled = false

    @Volatile private var offerSent = false

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
        initAudio()
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

    // ─── Audio source (created once; track added when peer connects) ──
    private fun initAudio() {
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = factory!!.createAudioSource(constraints)
            audioTrack  = factory!!.createAudioTrack("mic0", audioSource!!)
            // Start muted — the parent must explicitly enable it
            audioTrack!!.setEnabled(false)
            Log.d(TAG, "Audio source ready (muted)")
        } catch (e: Exception) {
            Log.e(TAG, "initAudio failed: ${e.message}", e)
        }
    }

    // ─── Remote mic control ───────────────────────────────────────────
    private fun setMicEnabled(enable: Boolean) {
        isMicEnabled = enable
        audioTrack?.setEnabled(enable)
        Log.d(TAG, "Mic ${if (enable) "ENABLED" else "DISABLED"} by parent (camera channel)")
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

    // ─── Create PeerConnection + video track (vanilla ICE) ────────────
    private fun setupPeerConnectionWithVideo(): Boolean {
        return try {
            val cameraId = getCameraId()
            if (cameraId.isEmpty()) {
                Log.e(TAG, "No camera found")
                return false
            }

            capturer = Camera2Capturer(context, cameraId, null)
            val vs = factory!!.createVideoSource(false)
            videoSource = vs
            surfaceTextureHelper = SurfaceTextureHelper.create("CamThread", eglBase!!.eglBaseContext)
            capturer!!.initialize(surfaceTextureHelper, context, vs.capturerObserver)
            capturer!!.startCapture(1280, 720, 30)
            Log.d(TAG, "Camera capture started: $cameraId (front=$facingFront)")

            val videoTrack = factory!!.createVideoTrack("cam0", vs)

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // VANILLA ICE: gather all candidates once, embed in offer SDP
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }

            pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    // Vanilla ICE: candidates embedded in SDP, not sent individually
                }
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                    // Send offer only after ALL candidates are gathered
                    if (s == PeerConnection.IceGatheringState.COMPLETE && !offerSent) {
                        offerSent = true
                        val sdp = pc?.localDescription?.description ?: return
                        signaling.send("offer", JSONObject().apply { put("sdp", sdp) })
                        Log.d(TAG, "Camera offer sent (vanilla ICE)")
                    }
                }
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                    Log.d(TAG, "Camera connection: $s")
                    signaling.send("status", JSONObject().apply { put("msg", s?.name ?: "unknown") })
                    // Auto-release camera if peer drops without sending an explicit "stop"
                    if (s == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        s == PeerConnection.PeerConnectionState.FAILED) {
                        Log.d(TAG, "Peer disconnected/failed — releasing camera")
                        // Also mute mic when connection drops
                        setMicEnabled(false)
                        releaseCamera()
                    }
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

            // Add video track
            pc?.addTrack(videoTrack)

            // Add audio track, then immediately apply whatever mic state the parent last requested.
            // If the parent pressed mic-on before a reconnect, isMicEnabled is still true here.
            audioTrack?.let {
                pc?.addTrack(it)
                it.setEnabled(isMicEnabled)
                Log.d(TAG, "Audio track added to new PC — isMicEnabled=$isMicEnabled")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "setupPeerConnection failed: ${e.message}", e)
            false
        }
    }

    // ─── Create & send offer ──────────────────────────────────────────
    private fun createAndSendOffer() {
        offerSent = false
        if (!setupPeerConnectionWithVideo()) {
            Log.e(TAG, "Cannot create offer — camera setup failed")
            return
        }
        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set — waiting for ICE gathering")
                        // Offer sent from onIceGatheringChange(COMPLETE)
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
                    switchCamera(front)
                }
                "answer" -> {
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotEmpty() && pc != null) {
                        pc!!.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Remote answer set — ICE connecting")
                            }
                            override fun onCreateSuccess(s: SessionDescription?) {}
                            override fun onCreateFailure(e: String?) {}
                            override fun onSetFailure(e: String?) { Log.e(TAG, "setRemote fail: $e") }
                        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    }
                }
                "stop" -> {
                    // Parent closed the camera view — stop streaming immediately
                    Log.d(TAG, "Parent sent stop — releasing camera and closing peer connection")
                    setMicEnabled(false)
                    pc?.close()
                    pc = null
                    releaseCamera()
                }
                // ── Pro feature: remote microphone control ──────────────
                "mic-on"  -> setMicEnabled(true)
                "mic-off" -> setMicEnabled(false)
                // No ice-parent handler needed — vanilla ICE embeds all candidates in SDP
            }
        }

        signaling.onConnected = { Log.d(TAG, "Camera signaling ready") }
        signaling.connect()
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        setMicEnabled(false)
        try { releaseCamera() } catch (_: Exception) {}
        pc?.close()
        audioTrack?.dispose()
        audioSource?.dispose()
        factory?.dispose()
        eglBase?.release()
        signaling.disconnect()
    }

    private fun releaseCamera() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
    }

    companion object { private const val TAG = "WebRTCCameraKidClient" }
}
