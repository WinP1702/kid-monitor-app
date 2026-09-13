package com.example.miro.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.miro.webrtc.SignalingClient
import com.example.miro.webrtc.SignalingMessage
import com.example.miro.webrtc.WebRtcEvent
import com.example.miro.webrtc.WebRtcManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

class ViewerViewModel(
    application: Application,
    private val myDeviceId: String,
    private val targetDeviceId: String
) : AndroidViewModel(application) {

    private val webRtcManager = WebRtcManager(application, viewModelScope)
    private val signalingClient = SignalingClient(myDeviceId)

    private val _remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteTrack = _remoteTrack.asStateFlow()

    private val _connectionState = MutableStateFlow("Initializing")
    val connectionState = _connectionState.asStateFlow()

    val eglBaseContext = webRtcManager.eglBase.eglBaseContext

    init {
        webRtcManager.createPeerConnection(isReceiver = true)
        observeWebRtcEvents()
        observeSignals()
        startMirroring()
    }

    private fun startMirroring() {
        viewModelScope.launch {
            val offer = webRtcManager.createOffer()
            if (offer != null) {
                signalingClient.sendOffer(targetDeviceId, offer)
            }
        }
    }

    private fun observeWebRtcEvents() {
        viewModelScope.launch {
            webRtcManager.events.collect { event ->
                Log.d("ViewerViewModel", "WebRTC Event: $event")
                when (event) {
                    is WebRtcEvent.IceCandidateFound -> {
                        signalingClient.sendIceCandidate(
                            targetDeviceId,
                            event.candidate.sdp,
                            event.candidate.sdpMid,
                            event.candidate.sdpMLineIndex
                        )
                    }
                    is WebRtcEvent.TrackAdded -> {
                        _remoteTrack.value = event.videoTrack
                        _connectionState.value = "Streaming"
                    }
                    is WebRtcEvent.ConnectionStateChanged -> {
                        val state = event.state?.toString() ?: "Unknown"
                        _connectionState.value = when (state) {
                            "NEW", "CHECKING" -> "Handshaking..."
                            "CONNECTED" -> "Connected"
                            "COMPLETED" -> "Streaming"
                            "FAILED" -> "Connection Blocked"
                            "DISCONNECTED", "CLOSED" -> "Disconnected"
                            else -> state
                        }
                    }
                    is WebRtcEvent.GatheringStateChanged -> {
                        val state = event.state.toString()
                        if (state == "GATHERING" && _connectionState.value != "Streaming") {
                            _connectionState.value = "Finding Network Paths..."
                        }
                    }
                }
            }
        }
    }

    private fun observeSignals() {
        viewModelScope.launch {
            signalingClient.observeSignals().collect { message ->
                Log.d("ViewerViewModel", "Signaling Message: $message")
                when (message) {
                    is SignalingMessage.Answer -> {
                        webRtcManager.handleAnswer(message.sdp)
                    }
                    is SignalingMessage.IceCandidate -> {
                        webRtcManager.addIceCandidate(message.sdp, message.sdpMid, message.sdpMLineIndex)
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        webRtcManager.release()
    }
}
