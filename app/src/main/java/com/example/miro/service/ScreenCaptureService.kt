package com.example.miro.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.miro.R
import com.example.miro.webrtc.SignalingClient
import com.example.miro.webrtc.SignalingMessage
import com.example.miro.webrtc.WebRtcEvent
import com.example.miro.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class ScreenCaptureService : Service() {

    private val TAG = "ScreenCaptureService"
    private val CHANNEL_ID = "ScreenCaptureChannel"
    private val NOTIFICATION_ID = 1

    private var mediaProjection: MediaProjection? = null
    private var webRtcManager: WebRtcManager? = null
    private var signalingClient: SignalingClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var videoTrack: VideoTrack? = null

    companion object {
        private var mediaProjectionData: Intent? = null
        fun setMediaProjectionData(data: Intent) {
            mediaProjectionData = data
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra("device_id") ?: return START_NOT_STICKY
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        signalingClient = SignalingClient(deviceId)
        webRtcManager = WebRtcManager(this, serviceScope)

        if (mediaProjectionData == null) {
            Log.e(TAG, "Cannot start capture: mediaProjectionData is null. Service might have been restarted by system.")
            stopSelf()
            return START_NOT_STICKY
        }

        startScreenCapture()
        observeSignals()

        return START_STICKY
    }

    private fun startScreenCapture() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        val capturer = ScreenCapturerAndroid(mediaProjectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        })

        // Capture at actual device resolution but capped at 1080p for performance if needed
        val width = if (metrics.widthPixels > 1080) 1080 else metrics.widthPixels
        val height = (width * (metrics.heightPixels.toFloat() / metrics.widthPixels)).toInt()

        videoTrack = webRtcManager?.createVideoTrack(capturer, width, height, 30)
        webRtcManager?.createPeerConnection(videoTrack)
        
        Log.d(TAG, "Screen capture started ($width x $height) and PeerConnection created")

        // Periodically log that the service is alive and sharing
        serviceScope.launch {
            while (isActive) {
                delay(10.seconds)
                Log.d(TAG, "ScreenCaptureService is active and sharing screen...")
            }
        }

        // Observe connection state on host side for debugging
        serviceScope.launch {
            webRtcManager?.events?.collect { event ->
                if (event is WebRtcEvent.ConnectionStateChanged) {
                    Log.i(TAG, "Host ICE Connection State: ${event.state}")
                }
            }
        }
    }

    private var targetDeviceId: String? = null
    private val iceCandidateQueue = mutableListOf<WebRtcEvent.IceCandidateFound>()

    private fun observeSignals() {
        serviceScope.launch {
            signalingClient?.observeSignals()?.collect { message ->
                when (message) {
                    is SignalingMessage.Offer -> {
                        Log.d(TAG, "Processing OFFER from ${message.sender}")
                        targetDeviceId = message.sender
                        val answer = webRtcManager?.handleOffer(message.sdp)
                        if (answer != null) {
                            signalingClient?.sendAnswer(message.sender, answer)
                            flushIceCandidates()
                        }
                    }
                    is SignalingMessage.Answer -> {
                        Log.d(TAG, "Processing ANSWER from ${message.sender}")
                        targetDeviceId = message.sender
                        webRtcManager?.handleAnswer(message.sdp)
                        flushIceCandidates()
                    }
                    is SignalingMessage.IceCandidate -> {
                        webRtcManager?.addIceCandidate(message.sdp, message.sdpMid, message.sdpMLineIndex)
                    }
                }
            }
        }

        serviceScope.launch {
            webRtcManager?.events?.collect { event ->
                when (event) {
                    is WebRtcEvent.IceCandidateFound -> {
                        val target = targetDeviceId
                        if (target != null) {
                            signalingClient?.sendIceCandidate(
                                target,
                                event.candidate.sdp,
                                event.candidate.sdpMid,
                                event.candidate.sdpMLineIndex
                            )
                        } else {
                            Log.d(TAG, "Queuing ICE candidate (target unknown)")
                            iceCandidateQueue.add(event)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun flushIceCandidates() {
        val target = targetDeviceId ?: return
        if (iceCandidateQueue.isNotEmpty()) {
            Log.d(TAG, "Flushing ${iceCandidateQueue.size} queued ICE candidates to $target")
            val candidates = ArrayList(iceCandidateQueue)
            iceCandidateQueue.clear()
            serviceScope.launch {
                candidates.forEach { event ->
                    signalingClient?.sendIceCandidate(
                        target,
                        event.candidate.sdp,
                        event.candidate.sdpMid,
                        event.candidate.sdpMLineIndex
                    )
                }
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Mirroring")
            .setContentText("Sharing screen in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch {
            signalingClient?.clearSignals()
            serviceScope.cancel()
        }
        webRtcManager?.release()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
