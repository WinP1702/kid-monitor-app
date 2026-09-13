package com.parentalcontrol.kidmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.firebase.database.*

/**
 * Background foreground service that streams the kid's camera to the parent.
 * Started by MonitorService and controlled via Firebase commands:
 *
 *   users/{key}/devices/{id}/cameraControl/
 *     requested : Boolean  — true = start streaming, false = stop
 *     front     : Boolean  — true = front cam, false = back cam
 *
 * Reports status to:
 *   users/{key}/devices/{id}/cameraStatus/
 *     streaming     : Boolean
 *     currentFront  : Boolean
 */
class CameraStreamService : Service() {

    private val prefs by lazy { getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE) }
    private var pairingKey = ""
    private var deviceId   = ""

    private var cameraClient: WebRTCCameraKidClient? = null
    private var controlRef: DatabaseReference? = null
    private var controlListener: ValueEventListener? = null

    private var currentFront = false
    private var streaming = false

    override fun onCreate() {
        super.onCreate()
        pairingKey = prefs.getString(SetupActivity.KEY_PAIRING_KEY, "") ?: ""
        deviceId   = prefs.getString(SetupActivity.KEY_DEVICE_ID,   "") ?: ""
        startAsForeground()
        listenForCommands()
    }

    // ─── Foreground notification ───────────────────────────────────────
    private fun startAsForeground() {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Camera Monitor", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Used for background camera streaming"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Camera active")
                .setContentText("Camera stream is running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Camera active")
                .setContentText("Camera stream is running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ─── Firebase listener for commands ───────────────────────────────
    private fun listenForCommands() {
        if (pairingKey.isEmpty() || deviceId.isEmpty()) return

        controlRef = FirebaseDatabase.getInstance()
            .getReference("users/$pairingKey/devices/$deviceId/cameraControl")

        controlListener = controlRef!!.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requested = snapshot.child("requested").getValue(Boolean::class.java) ?: false
                val front     = snapshot.child("front").getValue(Boolean::class.java) ?: false

                when {
                    // Start streaming or switch camera
                    requested && !streaming -> {
                        startStream(front)
                    }
                    requested && streaming && front != currentFront -> {
                        // Just switch camera, no full reconnect
                        cameraClient?.switchCamera(front)
                        currentFront = front
                        reportStatus()
                    }
                    !requested && streaming -> {
                        stopStream()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase error: ${error.message}")
            }
        })
    }

    private fun startStream(front: Boolean) {
        Log.d(TAG, "Starting camera stream (front=$front)")
        currentFront = front
        streaming = true

        cameraClient = WebRTCCameraKidClient(
            context    = this,
            deviceId   = deviceId,
            pairingKey = pairingKey,
            facingFront = front
        )
        cameraClient!!.start()
        reportStatus()
    }

    private fun stopStream() {
        Log.d(TAG, "Stopping camera stream")
        streaming = false
        cameraClient?.stop()
        cameraClient = null
        reportStatus()
    }

    private fun reportStatus() {
        val statusRef = FirebaseDatabase.getInstance()
            .getReference("users/$pairingKey/devices/$deviceId/cameraStatus")
        statusRef.setValue(mapOf(
            "streaming"    to streaming,
            "currentFront" to currentFront
        ))
    }

    override fun onDestroy() {
        controlListener?.let { controlRef?.removeEventListener(it) }
        stopStream()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG       = "CameraStreamService"
        private const val CHANNEL_ID = "camera_stream_ch"
        private const val NOTIF_ID  = 77

        fun start(context: Context) {
            val i = Intent(context, CameraStreamService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else
                context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraStreamService::class.java))
        }
    }
}
