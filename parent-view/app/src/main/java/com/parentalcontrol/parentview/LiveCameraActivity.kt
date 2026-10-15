package com.parentalcontrol.parentview

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.SurfaceViewRenderer

/**
 * Live Camera activity — streams the child device's camera to the parent in real time.
 *
 * Pro feature: Mic button — parent can remotely activate the kid's microphone to listen in
 * while watching the camera feed. Sends "mic-on" / "mic-off" over the signaling channel.
 */
class LiveCameraActivity : AppCompatActivity() {
    private var webRTCClient: WebRTCCameraParentClient? = null
    private lateinit var renderer: SurfaceViewRenderer

    private var isMicActive = false
    private var isCameraLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_camera)

        val deviceId   = intent.getStringExtra("DEVICE_ID")   ?: ""
        val pairingKey = intent.getStringExtra("PAIRING_KEY") ?: ""

        if (deviceId.isEmpty() || pairingKey.isEmpty()) {
            Toast.makeText(this, "Missing device info", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        renderer = findViewById(R.id.surfaceViewRenderer)
        val tvStatus  = findViewById<android.widget.TextView>(R.id.tvStatus)
        val statusDot = findViewById<android.view.View>(R.id.statusDot)
        val fabMic    = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabMic)
        val btnFront  = findViewById<android.widget.Button>(R.id.btnSwitchFront)
        val btnBack   = findViewById<android.widget.Button>(R.id.btnSwitchBack)

        webRTCClient = WebRTCCameraParentClient(this, deviceId, pairingKey, renderer)
        webRTCClient?.onStatusChanged = { status ->
            runOnUiThread {
                tvStatus.text = status
                isCameraLive = status.contains("live", ignoreCase = true) ||
                               status.contains("Camera", ignoreCase = true) && status.contains("CONNECTED", ignoreCase = true)
                // Update status dot
                val dotColor = when {
                    status.contains("live", ignoreCase = true)   -> Color.parseColor("#4CAF50")
                    status.contains("failed", ignoreCase = true) -> Color.parseColor("#F44336")
                    else                                         -> Color.parseColor("#FF9800")
                }
                statusDot.setBackgroundColor(dotColor)
            }
        }

        // Camera switch buttons
        btnFront.setOnClickListener { webRTCClient?.switchCamera(true) }
        btnBack.setOnClickListener  { webRTCClient?.switchCamera(false) }

        // Mic toggle button
        fabMic.setOnClickListener {
            isMicActive = !isMicActive
            updateMicFab(fabMic)

            // Tactile pop animation
            fabMic.animate()
                .scaleX(0.75f).scaleY(0.75f).setDuration(80)
                .withEndAction {
                    fabMic.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()

            if (isMicActive) {
                webRTCClient?.sendMicCommand(true)
                Toast.makeText(this, "🎙️ Microphone ON — listening...", Toast.LENGTH_SHORT).show()
                startMicPulse(fabMic)
            } else {
                webRTCClient?.sendMicCommand(false)
                Toast.makeText(this, "🔇 Microphone OFF", Toast.LENGTH_SHORT).show()
                fabMic.clearAnimation()
            }
        }

        webRTCClient?.start()
    }

    private fun updateMicFab(fab: com.google.android.material.floatingactionbutton.FloatingActionButton) {
        if (isMicActive) {
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#CCE53935"))
        } else {
            fab.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#CC37474F"))
            fab.clearAnimation()
        }
    }

    private fun startMicPulse(fab: com.google.android.material.floatingactionbutton.FloatingActionButton) {
        if (!isMicActive) return
        fab.animate()
            .scaleX(1.2f).scaleY(1.2f).setDuration(600)
            .withEndAction {
                if (isMicActive) {
                    fab.animate()
                        .scaleX(1f).scaleY(1f).setDuration(600)
                        .withEndAction { startMicPulse(fab) }
                        .start()
                }
            }.start()
    }

    override fun onDestroy() {
        if (isMicActive) {
            webRTCClient?.sendMicCommand(false)
        }
        webRTCClient?.stop()
        super.onDestroy()
    }
}
