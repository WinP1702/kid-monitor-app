package com.parentalcontrol.parentview

import android.content.Context
import android.media.AudioManager
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

    // Remote audio track received from the kid device.
    // Enabled/disabled in response to sendMicCommand(); the parent's own mic is never used.
    private var remoteAudioTrack: AudioTrack? = null

    // Stores mic state if sendMicCommand() was called before onTrack() fired.
    // Applied immediately when the remote audio track arrives.
    @Volatile private var pendingMicEnabled = false

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

        // Clear any stale remote audio track from a previous (now-closed) PeerConnection.
        // If sendMicCommand() fires after reconnect, it must reference the new track.
        remoteAudioTrack = null
        // Do NOT reset pendingMicEnabled — parent may have toggled mic before stream reconnected

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
                val track = transceiver?.receiver?.track() ?: return
                when (track) {
                    is VideoTrack -> {
                        mainHandler.post {
                            track.addSink(renderer)
                            onStatusChanged?.invoke("Camera live")
                        }
                    }
                    is AudioTrack -> {
                        // This is the kid's microphone audio stream.
                        // Apply whatever state the parent already requested (may have
                        // pressed mic-on before the WebRTC connection finished).
                        Log.d(TAG, "Remote audio track received — pendingMicEnabled=$pendingMicEnabled")
                        remoteAudioTrack = track
                        track.setEnabled(pendingMicEnabled)
                        if (pendingMicEnabled) {
                            // Also ensure AudioManager is in the right mode
                            @Suppress("DEPRECATION")
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                            am.isSpeakerphoneOn = true
                        }
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
                // IMPORTANT: explicitly request audio reception so the answer SDP contains
                // a recvonly audio m= line — without this the kid's mic track is never delivered.
                val answerConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
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
                }, answerConstraints)
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

    // ─── Remote microphone control ────────────────────────────────────
    /**
     * Sends mic-on / mic-off to the kid device AND enables/disables the remote AudioTrack
     * on the parent side so the parent actually hears (or stops hearing) the kid's mic.
     *
     * The parent's own microphone is NEVER activated — only the kid's mic AudioTrack
     * that was received over the WebRTC PeerConnection is toggled here.
     */
    @Suppress("DEPRECATION")
    fun sendMicCommand(enable: Boolean) {
        // Always persist the desired state — onTrack() will apply it if the track
        // has not arrived yet (race between button press and WebRTC negotiation).
        pendingMicEnabled = enable

        val event = if (enable) "mic-on" else "mic-off"
        // 1. Tell the kid device to start/stop capturing microphone audio
        signaling.send(event, JSONObject())
        // 2. Configure AudioManager so remote audio routes to speaker (not earpiece)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (enable) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
        } else {
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
        // 3. Enable/disable the incoming remote audio track on the parent's receiver
        //    so audio actually flows through to the parent's speaker
        remoteAudioTrack?.setEnabled(enable)
        Log.d(TAG, "Sent mic command: $event | remoteAudioTrack=${remoteAudioTrack != null} enabled=$enable")
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    fun stop() {
        isConnected = false     // stops the request-retry loop cleanly
        // Mute and release the kid's remote audio track before tearing down
        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null
        // Restore audio manager to normal mode
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
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
