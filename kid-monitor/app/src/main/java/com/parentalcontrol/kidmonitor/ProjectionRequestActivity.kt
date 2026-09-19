package com.parentalcontrol.kidmonitor

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent trampoline activity that silently re-requests MediaProjection permission.
 * Called on boot and when service needs to restart.
 * Has a transparent theme so it appears invisible.
 *
 * Also restarts CameraStreamService so BOTH services are always alive.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            MonitorService.startWithProjection(this, result.resultCode, result.data!!)
            // Always restart camera service too — keeps both services in sync
            CameraStreamService.start(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
