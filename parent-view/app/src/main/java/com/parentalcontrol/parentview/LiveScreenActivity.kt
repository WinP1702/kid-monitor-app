package com.parentalcontrol.parentview

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.parentalcontrol.parentview.databinding.ActivityLiveScreenBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Live Screen activity — shows real-time WebRTC video stream from the child device.
 * Architecture: Supabase Realtime signaling → WebRTC P2P video (no server relay).
 * Latency: ~100–500ms  |  Quality: 1080p@30fps  |  Cost: $0 (P2P, no server bandwidth)
 */
class LiveScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveScreenBinding
    private var webRTCClient: WebRTCParentClient? = null
    private var isStreaming = false

    private var deviceId: String = ""
    private var deviceName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId   = intent.getStringExtra("deviceId")   ?: return
        deviceName = intent.getStringExtra("deviceName") ?: deviceId

        setupUI()
        startWebRTC()
    }

    private fun setupUI() {
        supportActionBar?.apply {
            title = "📱 ${deviceName}"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.tvDeviceLabel.text = deviceName
        binding.tvStatus.text = "⏳ Connecting to signaling..."

        // Screenshot FAB
        binding.fabScreenshot.setOnClickListener { takeScreenshot() }
    }

    private fun startWebRTC() {
        webRTCClient = WebRTCParentClient(
            context  = this,
            deviceId = deviceId,
            renderer = binding.surfaceViewRenderer
        )

        webRTCClient!!.onStatusChanged = { status ->
            runOnUiThread {
                binding.tvStatus.text = status
                updateStatusDot(status)

                val nowStreaming = status.contains("Streaming", ignoreCase = true)
                if (nowStreaming && !isStreaming) {
                    isStreaming = true
                    // Fade out the waiting overlay
                    binding.waitingOverlay.animate()
                        .alpha(0f).setDuration(500)
                        .withEndAction { binding.waitingOverlay.visibility = View.GONE }
                        .start()
                    // Show screenshot button with a pop animation
                    binding.fabScreenshot.visibility = View.VISIBLE
                    binding.fabScreenshot.scaleX = 0f
                    binding.fabScreenshot.scaleY = 0f
                    binding.fabScreenshot.animate().scaleX(1f).scaleY(1f).setDuration(300).start()

                } else if (!nowStreaming && isStreaming &&
                    (status.contains("Disconnected") || status.contains("failed"))) {
                    isStreaming = false
                    // Show overlay again on disconnect
                    binding.waitingOverlay.visibility = View.VISIBLE
                    binding.waitingOverlay.alpha = 1f
                    binding.tvWaitingTitle.text = status
                    binding.tvWaitingSubtitle.text = "Attempting to reconnect..."
                    binding.progressBar.visibility = View.VISIBLE
                    binding.fabScreenshot.visibility = View.GONE
                }
            }
        }

        webRTCClient!!.start()
    }

    // ─── Screenshot ───────────────────────────────────────────────────
    private fun takeScreenshot() {
        val renderer = binding.surfaceViewRenderer
        if (renderer.width == 0 || renderer.height == 0 || !isStreaming) {
            Toast.makeText(this, "No live video to capture", Toast.LENGTH_SHORT).show()
            return
        }

        // Animate the FAB to give feedback
        binding.fabScreenshot.animate()
            .scaleX(0.75f).scaleY(0.75f).setDuration(100)
            .withEndAction {
                binding.fabScreenshot.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

        val bitmap = Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888)

        // PixelCopy reads directly from the GPU surface — works perfectly with SurfaceView
        PixelCopy.request(renderer, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                saveScreenshot(bitmap)
            } else {
                bitmap.recycle()
                runOnUiThread {
                    Toast.makeText(this, "Screenshot failed (code $result)", Toast.LENGTH_SHORT).show()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveScreenshot(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename  = "KidScreen_$timestamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KidMonitor")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            bitmap.recycle()
            runOnUiThread {
                Toast.makeText(this, "Cannot save screenshot", Toast.LENGTH_SHORT).show()
            }
            return
        }

        contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        bitmap.recycle()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }

        runOnUiThread {
            Toast.makeText(this, "📸 Screenshot saved to Pictures/KidMonitor", Toast.LENGTH_LONG).show()
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────
    private fun updateStatusDot(status: String) {
        val color = when {
            status.contains("Streaming") -> Color.parseColor("#4CAF50") // green
            status.contains("failed")    -> Color.parseColor("#F44336") // red
            else                         -> Color.parseColor("#FF9800") // orange
        }
        binding.statusDot.setBackgroundColor(color)
        val shouldPulse = !status.contains("Streaming") && !status.contains("failed")
        if (shouldPulse) {
            binding.statusDot.animate().alpha(0.2f).setDuration(600)
                .withEndAction {
                    binding.statusDot.animate().alpha(1f).setDuration(600).start()
                }.start()
        } else {
            binding.statusDot.alpha = 1f
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onDestroy() {
        webRTCClient?.stop()
        webRTCClient = null
        super.onDestroy()
    }
}
