package com.parentalcontrol.kidmonitor

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Kid-side WebRTC client.
 * - Captures the screen using ScreenCapturerAndroid (MediaProjection-based)
 * - Streams it to the parent phone via WebRTC P2P
 * - Uses Supabase Realtime for signaling (offer/answer/ICE)
 *
 * Flow:
 *  1. connect() → join Supabase channel
 *  2. Parent sends "request" → kid creates offer → sends via Supabase
 *  3. Parent sends "answer" → kid sets remote description
 *  4. Both exchange ICE candidates → P2P video stream starts
 */
class WebRTCKidClient(
    private val context: Context,
    private val projectionData: Intent,   // Intent from MediaProjection permission grant
    private val deviceId: String
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private val signaling = SupabaseSignaling(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY,
        channelId   = "screen-$deviceId"
    )

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )

    // ─── Start ────────────────────────────────────────────────────────
    fun start() {
        initWebRTC()
        setupSignaling()
    }

    // ─── WebRTC Initialization ────────────────────────────────────────
    private fun initWebRTC() {
        eglBase = EglBase.create()

        val initOpts = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOpts)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                HardwareVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                HardwareVideoDecoderFactory(eglBase!!.eglBaseContext)
            )
            .createPeerConnectionFactory()

        Log.d(TAG, "PeerConnectionFactory created")
    }

    // ─── Create PeerConnection + Video Track ──────────────────────────
    private fun setupPeerConnectionWithVideo(): Boolean {
        return try {
            // ScreenCapturerAndroid handles getMediaProjection() internally
            capturer = ScreenCapturerAndroid(
                projectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped")
                        signaling.send("status", JSONObject().apply { put("msg", "projection stopped") })
                    }
                }
            )

            val videoSource = factory!!.createVideoSource(true /* isScreencast */)
            surfaceTextureHelper = SurfaceTextureHelper.create("CapThread", eglBase!!.eglBaseContext)
            capturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer!!.startCapture(720, 1280, 30)
            Log.d(TAG, "Screen capture started")

            val videoTrack = factory!!.createVideoTrack("screen0", videoSource)

            // Create PeerConnection
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
            Log.d(TAG, "PeerConnection created with video track")
            true
        } catch (e: Exception) {
            Log.e(TAG, "setupPeerConnection failed: ${e.message}", e)
            false
        }
    }

    // ─── Create & send WebRTC offer ───────────────────────────────────
    private fun createAndSendOffer() {
        // Reset pending ICE candidates for new session
        pendingRemoteIce.clear()

        if (!setupPeerConnectionWithVideo()) {
            Log.e(TAG, "Cannot create offer — PeerConnection setup failed")
            return
        }

        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signaling.send("offer", JSONObject().apply { put("sdp", sdp.description) })
                        Log.d(TAG, "Offer sent to parent")
                    }
                    override fun onCreateSuccess(s: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) { Log.e(TAG, "setLocal create fail: $e") }
                    override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                }, sdp)
            }
            override fun onCreateFailure(e: String?) { Log.e(TAG, "createOffer fail: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        }, MediaConstraints())
    }

    // ─── Signaling setup ──────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "request" -> {
                    Log.d(TAG, "Parent requested stream — creating offer")
                    // Close existing session if any
                    pc?.close()
                    capturer?.stopCapture()
                    capturer?.dispose()
                    pc = null
                    capturer = null
                    createAndSendOffer()
                }
                "answer" -> {
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotEmpty() && pc != null) {
                        pc!!.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Remote answer set ✓")
                                // Flush any ICE candidates received before answer
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
                    if (pc?.remoteDescription != null) {
                        pc?.addIceCandidate(candidate)
                    } else {
                        pendingRemoteIce.add(candidate) // buffer until remote desc is set
                    }
                }
            }
        }

        signaling.onConnected = {
            Log.d(TAG, "Signaling ready — waiting for parent")
        }

        signaling.connect()
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        try {
            capturer?.stopCapture()
            capturer?.dispose()
        } catch (e: Exception) { Log.w(TAG, "capturer stop: ${e.message}") }
        surfaceTextureHelper?.dispose()
        pc?.close()
        factory?.dispose()
        eglBase?.release()
        signaling.disconnect()
        Log.d(TAG, "WebRTCKidClient stopped")
    }

    companion object {
        private const val TAG = "WebRTCKidClient"
    }
}
