package com.parentalcontrol.parentview

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Parent-side WebRTC client.
 * - Connects to Supabase Realtime signaling channel
 * - Sends "request" to kid device to start streaming
 * - Receives offer → creates answer → exchanges ICE candidates
 * - Renders received video on SurfaceViewRenderer
 *
 * Usage:
 *   val client = WebRTCParentClient(context, deviceId, surfaceViewRenderer)
 *   client.onStatusChanged = { status -> updateUI(status) }
 *   client.start()
 *   // later:
 *   client.stop()
 */
class WebRTCParentClient(
    private val context: Context,
    private val deviceId: String,
    private val pairingKey: String,
    private val renderer: SurfaceViewRenderer
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var eglBase: EglBase? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()

    private val signaling = SupabaseSignaling(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY,
        channelId   = "screen-$pairingKey-$deviceId"
    )

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
    )

    /** Called on main thread with human-readable status string */
    var onStatusChanged: ((String) -> Unit)? = null

    // ─── Start ────────────────────────────────────────────────────────
    fun start() {
        initWebRTC()
        setupSignaling()
    }

    // ─── WebRTC Initialization ────────────────────────────────────────
    private fun initWebRTC() {
        eglBase = EglBase.create()

        renderer.init(eglBase!!.eglBaseContext, null)
        renderer.setMirror(false)
        renderer.setEnableHardwareScaler(true)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

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

        Log.d(TAG, "PeerConnectionFactory ready")
    }

    // ─── Create PeerConnection (answerer role) ────────────────────────
    private fun createPeerConnection() {
        pendingRemoteIce.clear()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceCandidate(c: IceCandidate) {
                signaling.send("ice-parent", JSONObject().apply {
                    put("candidate",     c.sdp)
                    put("sdpMid",        c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                })
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                if (track is VideoTrack) {
                    Log.d(TAG, "Remote video track received!")
                    track.setEnabled(true)
                    track.addSink(renderer)
                    updateStatus("🟢 Streaming")
                }
            }

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Connection: $s")
                when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED    -> updateStatus("🟢 Streaming")
                    PeerConnection.PeerConnectionState.DISCONNECTED -> updateStatus("🔴 Disconnected — retrying...")
                    PeerConnection.PeerConnectionState.FAILED       -> {
                        updateStatus("❌ Connection failed")
                        // Re-request stream after a delay
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            requestStream()
                        }, 3000)
                    }
                    else -> {}
                }
            }

            override fun onAddStream(s: MediaStream?) {
                // Legacy callback — use onTrack for UNIFIED_PLAN
                s?.videoTracks?.firstOrNull()?.addSink(renderer)
            }

            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<MediaStream>?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
        })

        Log.d(TAG, "PeerConnection created (answerer)")
    }

    // ─── Request stream from kid ──────────────────────────────────────
    private fun requestStream() {
        pc?.close()
        pc = null
        updateStatus("⏳ Requesting stream...")
        signaling.send("request", JSONObject())
    }

    // ─── Signaling setup ──────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "offer" -> {
                    Log.d(TAG, "Offer received — creating answer")
                    updateStatus("⏳ Connecting...")

                    if (pc == null) createPeerConnection()

                    val sdp = payload.optString("sdp")
                    pc?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Remote offer set ✓ — creating answer")
                            // Flush ICE candidates received before the offer was processed
                            pendingRemoteIce.forEach { pc?.addIceCandidate(it) }
                            pendingRemoteIce.clear()

                            pc?.createAnswer(object : SdpObserver {
                                override fun onCreateSuccess(sdp: SessionDescription) {
                                    pc?.setLocalDescription(object : SdpObserver {
                                        override fun onSetSuccess() {
                                            signaling.send("answer", JSONObject().apply {
                                                put("sdp", sdp.description)
                                            })
                                            Log.d(TAG, "Answer sent ✓")
                                        }
                                        override fun onCreateSuccess(s: SessionDescription?) {}
                                        override fun onCreateFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                                        override fun onSetFailure(e: String?) { Log.e(TAG, "setLocal fail: $e") }
                                    }, sdp)
                                }
                                override fun onCreateFailure(e: String?) { Log.e(TAG, "createAnswer fail: $e") }
                                override fun onSetSuccess() {}
                                override fun onSetFailure(e: String?) {}
                            }, MediaConstraints())
                        }
                        override fun onCreateSuccess(s: SessionDescription?) {}
                        override fun onCreateFailure(e: String?) {}
                        override fun onSetFailure(e: String?) { Log.e(TAG, "setRemoteOffer fail: $e") }
                    }, SessionDescription(SessionDescription.Type.OFFER, sdp))
                }

                "ice-kid" -> {
                    val candidate = IceCandidate(
                        payload.optString("sdpMid"),
                        payload.optInt("sdpMLineIndex"),
                        payload.optString("candidate")
                    )
                    if (pc?.remoteDescription != null) {
                        pc?.addIceCandidate(candidate)
                    } else {
                        pendingRemoteIce.add(candidate)
                    }
                }

                "status" -> {
                    val msg = payload.optString("msg")
                    if (msg.isNotEmpty()) updateStatus("📱 $msg")
                }
            }
        }

        signaling.onConnected = {
            Log.d(TAG, "Signaling connected — requesting stream")
            requestStream()
        }

        signaling.onDisconnected = {
            updateStatus("🔌 Signaling disconnected — reconnecting...")
        }

        signaling.connect()
        updateStatus("⏳ Connecting to signaling...")
    }

    // ─── Helpers ──────────────────────────────────────────────────────
    private fun updateStatus(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStatusChanged?.invoke(msg)
        }
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        signaling.disconnect()
        pc?.close()
        factory?.dispose()
        try { renderer.release() } catch (e: Exception) { Log.w(TAG, "renderer release: ${e.message}") }
        eglBase?.release()
        Log.d(TAG, "WebRTCParentClient stopped")
    }

    companion object {
        private const val TAG = "WebRTCParentClient"
    }
}
