package com.example.miro.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRtcManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val TAG = "WebRtcManager"
    private var peerConnection: PeerConnection? = null
    
    val eglBase: EglBase = EglBase.create()
    
    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private val _events = MutableSharedFlow<WebRtcEvent>()
    val events = _events.asSharedFlow()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.services.mozilla.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.voiparound.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.voipbuster.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.voipstunt.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.voxgratia.org").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.xten.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.ideasip.com").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.schlund.de").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.softjoys.com").createIceServer()
    )

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var frameCount = 0
    private val incomingCandidateBuffer = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    fun createVideoTrack(capturer: VideoCapturer, width: Int, height: Int, fps: Int): VideoTrack {
        Log.d(TAG, "Creating video track: ${width}x${height} @ ${fps}fps")
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        
        val wrappedObserver = object : CapturerObserver {
            override fun onCapturerStarted(p0: Boolean) {
                Log.d(TAG, "Capturer started: $p0")
                videoSource?.capturerObserver?.onCapturerStarted(p0)
            }
            override fun onCapturerStopped() {
                Log.d(TAG, "Capturer stopped")
                videoSource?.capturerObserver?.onCapturerStopped()
            }
            override fun onFrameCaptured(frame: VideoFrame?) {
                frameCount++
                if (frameCount % 100 == 0) {
                    Log.d(TAG, "Host capturing frame: $frameCount")
                }
                videoSource?.capturerObserver?.onFrameCaptured(frame)
            }
        }

        capturer.initialize(surfaceTextureHelper, context, wrappedObserver)
        capturer.startCapture(width, height, fps)
        return peerConnectionFactory.createVideoTrack("video_track", videoSource)
    }

    fun createPeerConnection(videoTrack: VideoTrack? = null, isReceiver: Boolean = false) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 10 
        }
        isRemoteDescriptionSet = false
        incomingCandidateBuffer.clear()
        Log.d(TAG, "Creating PeerConnection (isReceiver=$isReceiver)")
        peerConnection = peerConnectionFactory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "New LOCAL ICE candidate found: ${candidate.sdpMid}")
                scope.launch {
                    _events.emit(WebRtcEvent.IceCandidateFound(candidate))
                }
            }

            override fun onDataChannel(p0: DataChannel?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {
                Log.d(TAG, "ICE connection receiving change: $p0")
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE connection state change: $newState")
                scope.launch {
                    _events.emit(WebRtcEvent.ConnectionStateChanged(newState))
                }
            }
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "ICE gathering state change: $p0")
                scope.launch {
                    _events.emit(WebRtcEvent.GatheringStateChanged(p0))
                }
            }
            override fun onAddStream(p0: MediaStream?) {
                Log.d(TAG, "MediaStream added")
            }
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state change: $p0")
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                Log.i(TAG, "Remote track added: ${track?.kind()} id: ${track?.id()} from streams: ${streams?.size}")
                if (track is VideoTrack) {
                    scope.launch {
                        _events.emit(WebRtcEvent.TrackAdded(track))
                    }
                }
            }
        })

        if (isReceiver) {
            peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, 
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        }

        videoTrack?.let {
            peerConnection?.addTrack(it, listOf("stream1"))
        }
    }

    suspend fun createOffer(): String? = suspendCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Offer created and local description set")
                        cont.resume(desc.description)
                    }
                    override fun onSetFailure(p0: String?) { 
                        Log.e(TAG, "Failed to set local description: $p0")
                        cont.resume(null) 
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onCreateFailure(p0: String?) { 
                Log.e(TAG, "Failed to create offer: $p0")
                cont.resume(null) 
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    suspend fun handleOffer(sdp: String): String? = suspendCoroutine { cont ->
        Log.d(TAG, "Handling received offer")
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description (OFFER) set successfully")
                isRemoteDescriptionSet = true
                drainCandidates()
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        Log.d(TAG, "Answer created successfully")
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { 
                                Log.d(TAG, "Local description (ANSWER) set successfully")
                                cont.resume(answer.description) 
                            }
                            override fun onSetFailure(p0: String?) { 
                                Log.e(TAG, "Failed to set local description (ANSWER): $p0")
                                cont.resume(null) 
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, answer)
                    }
                    override fun onCreateFailure(p0: String?) { 
                        Log.e(TAG, "Failed to create answer: $p0")
                        cont.resume(null) 
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(p0: String?) { 
                Log.e(TAG, "Failed to set remote description (OFFER): $p0")
                cont.resume(null) 
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, desc)
    }

    suspend fun handleAnswer(sdp: String) = suspendCoroutine<Unit> { cont ->
        Log.d(TAG, "Handling received answer")
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description (ANSWER) set successfully")
                isRemoteDescriptionSet = true
                drainCandidates()
                cont.resume(Unit)
            }
            override fun onSetFailure(p0: String?) { 
                Log.e(TAG, "Failed to set remote description (ANSWER): $p0")
                cont.resume(Unit) 
            }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, desc)
    }

    fun addIceCandidate(sdp: String, sdpMid: String, sdpMLineIndex: Int) {
        val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
        if (isRemoteDescriptionSet) {
            Log.d(TAG, "Adding remote ICE candidate immediately")
            peerConnection?.addIceCandidate(candidate)
        } else {
            Log.d(TAG, "Buffering remote ICE candidate (remote description not set)")
            incomingCandidateBuffer.add(candidate)
        }
    }

    private fun drainCandidates() {
        Log.d(TAG, "Draining ${incomingCandidateBuffer.size} buffered remote candidates")
        incomingCandidateBuffer.forEach {
            peerConnection?.addIceCandidate(it)
        }
        incomingCandidateBuffer.clear()
    }

    fun release() {
        peerConnection?.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}

sealed class WebRtcEvent {
    data class IceCandidateFound(val candidate: IceCandidate) : WebRtcEvent()
    data class TrackAdded(val videoTrack: VideoTrack?) : WebRtcEvent()
    data class ConnectionStateChanged(val state: PeerConnection.IceConnectionState?) : WebRtcEvent()
    data class GatheringStateChanged(val state: PeerConnection.IceGatheringState?) : WebRtcEvent()
}
