package com.parentalcontrol.kidmonitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages screen capture via Android's MediaProjection API.
 * Uses OnImageAvailableListener for reliable frame capture
 * instead of polling acquireLatestImage() which returns null when buffer is empty.
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null

    private val lock = ReentrantLock()
    private var latestBitmap: Bitmap? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val isCapturing = AtomicBoolean(false)

    var lastError: String = "Not started"
        private set

    // ─── Init ─────────────────────────────────────────────────────────
    fun initialize(projection: MediaProjection) {
        mediaProjection = projection
        lastError = "Initializing..."

        val (w, h, dpi) = getScreenMetrics()
        // Use 1/3 resolution: ~360x640 for a 1080x1920 screen
        val captureWidth  = (w / 3).coerceAtLeast(320)
        val captureHeight = (h / 3).coerceAtLeast(568)

        // Background thread for image processing
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureWidth, captureHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // Listener fires whenever a new frame is available
        imageReader!!.setOnImageAvailableListener({ reader ->
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                // Cache dimensions BEFORE closing image
                val imgWidth  = image.width
                val imgHeight = image.height
                val planes      = image.planes
                val buffer      = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride   = planes[0].rowStride
                val rowPadding  = rowStride - pixelStride * imgWidth

                val raw = Bitmap.createBitmap(
                    imgWidth + rowPadding / pixelStride,
                    imgHeight,
                    Bitmap.Config.ARGB_8888
                )
                raw.copyPixelsFromBuffer(buffer)
                image.close()  // Safe to close now — buffer already copied

                val cropped = Bitmap.createBitmap(raw, 0, 0, imgWidth, imgHeight)
                if (cropped !== raw) raw.recycle()

                lock.withLock {
                    latestBitmap?.recycle()
                    latestBitmap = cropped
                    lastError = "OK"
                }
                Log.d(TAG, "Frame captured: ${imgWidth}x${imgHeight}")
            } catch (e: Exception) {
                lastError = "Capture error: ${e.message}"
                Log.e(TAG, "Frame capture failed", e)
            }
        }, handler)

        projection.createVirtualDisplay(
            "MonitorCapture",
            captureWidth, captureHeight, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )

        isCapturing.set(true)
        lastError = "Waiting for first frame..."
        Log.d(TAG, "VirtualDisplay created: ${captureWidth}x${captureHeight}")
    }

    // ─── Get latest frame ─────────────────────────────────────────────
    // Returns a COPY of the latest bitmap so caller can safely recycle it
    fun getLatestFrame(): Bitmap? {
        if (!isCapturing.get()) return null
        return lock.withLock {
            latestBitmap?.copy(latestBitmap!!.config ?: Bitmap.Config.ARGB_8888, false)
        }
    }

    fun hasFrame(): Boolean = lock.withLock { latestBitmap != null }

    // ─── Teardown ─────────────────────────────────────────────────────
    fun teardown() {
        isCapturing.set(false)
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        lock.withLock {
            latestBitmap?.recycle()
            latestBitmap = null
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────
    private data class Metrics(val width: Int, val height: Int, val dpi: Int)

    private fun getScreenMetrics(): Metrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds  = wm.currentWindowMetrics.bounds
            val density = context.resources.displayMetrics.densityDpi
            Metrics(bounds.width(), bounds.height(), density)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Metrics(m.widthPixels, m.heightPixels, m.densityDpi)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
