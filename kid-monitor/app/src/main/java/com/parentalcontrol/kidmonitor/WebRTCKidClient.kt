package com.parentalcontrol.kidmonitor

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

/**
 * Kid-side WebRTC client for SCREEN sharing.
 *
 * Uses VANILLA ICE (GATHER_ONCE):
 *   - All ICE candidates are gathered BEFORE the offer is sent.
 *   - The offer SDP already contains all candidates.
 *   - Zero individual ice-kid / ice-parent messages over Realtime.
 *   - Avoids Supabase "too many messages per second" rate limit.
 *   - 3 total messages per session: request → offer → answer.
 *
 * Pro feature: Remote mic control — parent sends "mic-on"/"mic-off" via signaling;
 * this client enables/disables the local AudioTrack accordingly.
 *
 * Signaling channel: "screen-{pairingKey}-{deviceId}"
 */
class WebRTCKidClient(
    private val context: Context,
    private val projectionData: Intent,
    private val deviceId: String,
    private val pairingKey: String
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null

    private var capturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    // ─── Audio ────────────────────────────────────────────────────────
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var isMicEnabled = false

    // Guards against sending the offer more than once per gathering cycle
    @Volatile private var offerSent = false

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

    // ─── Start ────────────────────────────────────────────────────────
    fun start() {
        initWebRTC()
        initCapturer()
        initAudio()
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

    // ─── Remote mic control (called from signaling handler) ──────────
    private fun setMicEnabled(enable: Boolean) {
        isMicEnabled = enable
        audioTrack?.setEnabled(enable)
        Log.d(TAG, "Mic ${if (enable) "ENABLED" else "DISABLED"} by parent")
    }

    // ─── Eager screen capturer initialization ────────────────────────
    private fun initCapturer() {
        try {
            capturer = ScreenCapturerAndroid(
                projectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        // Android revoked the MediaProjection (e.g. user dismissed the
                        // "Screen share" notification, OEM battery saver killed it, or
                        // the system's ~24h auto-expiry fired).
                        // Ask MonitorService to request a fresh grant via the trampoline.
                        Log.w(TAG, "MediaProjection stopped — requesting fresh grant")
                        val trampolineIntent = Intent(context, ProjectionRequestActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(trampolineIntent)
                    }
                }
            )
            videoSource = factory!!.createVideoSource(true)
            surfaceTextureHelper = SurfaceTextureHelper.create("CapThread", eglBase!!.eglBaseContext)
            capturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            capturer!!.startCapture(720, 1280, 30)
            videoTrack = factory!!.createVideoTrack("screen0", videoSource!!)
            Log.d(TAG, "Screen capturer started")
        } catch (e: Exception) {
            Log.e(TAG, "initCapturer failed: ${e.message}", e)
        }
    }

    // ─── Create PeerConnection + offer (vanilla ICE) ──────────────────
    private fun createAndSendOffer() {
        offerSent = false
        pc?.close()
        pc = null

        if (videoTrack == null) {
            Log.e(TAG, "VideoTrack not ready — screen capturer may have failed")
            return
        }

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // VANILLA ICE: gather all candidates once, embed them in the offer SDP.
            // This sends zero individual ice-* messages and stays under rate limits.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        pc = factory!!.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                // Vanilla ICE: individual candidates are NOT sent; they are all
                // embedded in localDescription.sdp when gathering completes.
            }
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                // Send the offer only once, after ALL candidates are gathered.
                if (s == PeerConnection.IceGatheringState.COMPLETE && !offerSent) {
                    offerSent = true
                    val sdp = pc?.localDescription?.description ?: return
                    signaling.send("offer", JSONObject().apply { put("sdp", sdp) })
                    Log.d(TAG, "Offer sent (vanilla ICE — candidates embedded in SDP)")
                }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, "Screen connection: $s")
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
        pc?.addTrack(videoTrack!!)

        // Add audio track, then immediately apply whatever mic state the parent last requested.
        // If the parent pressed mic-on before a reconnect, isMicEnabled is still true here.
        audioTrack?.let {
            pc?.addTrack(it)
            it.setEnabled(isMicEnabled)
            Log.d(TAG, "Audio track added to new PC — isMicEnabled=$isMicEnabled")
        }

        Log.d(TAG, "PeerConnection created — creating offer")

        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set — waiting for ICE gathering to complete")
                        // Offer is sent from onIceGatheringChange(COMPLETE), not here.
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

    // ─── Signaling setup ──────────────────────────────────────────────
    private fun setupSignaling() {
        signaling.onMessage = { event, payload ->
            when (event) {
                "request" -> {
                    Log.d(TAG, "Parent requested stream — creating new offer")
                    createAndSendOffer()
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
                // ── Pro feature: remote microphone control ──────────────
                "mic-on"  -> setMicEnabled(true)
                "mic-off" -> setMicEnabled(false)
                // No ice-parent handler needed — vanilla ICE embeds all candidates in SDP
            }
        }

        signaling.onConnected = {
            Log.d(TAG, "Screen signaling (re)connected — resetting stale peer connection")
            // Close any stale PeerConnection from before the signaling drop.
            // This ensures the kid is in a clean state when the parent sends the next request.
            offerSent = false
            pc?.close()
            pc = null
        }

        signaling.connect()
    }

    // ─── Stop ─────────────────────────────────────────────────────────
    fun stop() {
        // Ensure mic is disabled before tearing down
        setMicEnabled(false)
        pc?.close()
        pc = null
        try {
            capturer?.stopCapture()
            capturer?.dispose()
        } catch (e: Exception) { Log.w(TAG, "capturer stop: ${e.message}") }
        videoTrack?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        surfaceTextureHelper?.dispose()
        factory?.dispose()
        eglBase?.release()
        signaling.disconnect()
        Log.d(TAG, "WebRTCKidClient stopped")
    }

    companion object {
        private const val TAG = "WebRTCKidClient"
    }
}
