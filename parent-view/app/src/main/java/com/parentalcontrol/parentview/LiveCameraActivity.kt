package com.parentalcontrol.parentview

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.SurfaceViewRenderer

class LiveCameraActivity : AppCompatActivity() {
    private var webRTCClient: WebRTCCameraParentClient? = null
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_camera)

        val deviceId = intent.getStringExtra("DEVICE_ID") ?: ""
        val pairingKey = intent.getStringExtra("PAIRING_KEY") ?: ""

        if (deviceId.isEmpty() || pairingKey.isEmpty()) {
            Toast.makeText(this, "Missing device info", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        renderer = findViewById(R.id.surfaceViewRenderer)
        tvStatus = findViewById(R.id.tvStatus)
        val btnFront = findViewById<Button>(R.id.btnSwitchFront)
        val btnBack = findViewById<Button>(R.id.btnSwitchBack)

        webRTCClient = WebRTCCameraParentClient(this, deviceId, pairingKey, renderer)
        webRTCClient?.onStatusChanged = { status ->
            tvStatus.text = status
        }

        btnFront.setOnClickListener { webRTCClient?.switchCamera(true) }
        btnBack.setOnClickListener { webRTCClient?.switchCamera(false) }

        webRTCClient?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRTCClient?.stop()
    }
}
