package com.example.macro

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            imageReader?.close()
            stopSelf()
        }
    }

    companion object {
        const val CHANNEL_ID = "macro_capture_channel"
        const val NOTIF_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("CaptureThread").apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED) ?: return START_NOT_STICKY
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification())
        isRunning = true

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, captureHandler)
        startFullCapture()
        return START_STICKY
    }

    private fun startFullCapture() {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = maxOf(metrics.widthPixels, metrics.heightPixels)
        val height = minOf(metrics.widthPixels, metrics.heightPixels)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MacroCapture",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image)
            } finally {
                image.close()
            }
        }, captureHandler)
    }

    private var lastLogMs = 0L

    private fun processFrame(image: android.media.Image) {
        val c = MacroController
        if (!c.canScan()) return

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val left = c.scanLeft.coerceIn(0, image.width - 1)
        val top = c.scanTop.coerceIn(0, image.height - 1)
        val right = c.scanRight.coerceIn(left + 1, image.width - 1)
        val bottom = c.scanBottom.coerceIn(top + 1, image.height - 1)
        val threshold = c.brightThreshold

        // Stride 6 — cukup untuk lingkaran minimal ~30px diameter.
        // ponytail: hardcode stride, tambah adjust di overlay kalau perlu.
        val stride = 6

        var sumX = 0L
        var sumY = 0L
        var count = 0

        var y = top
        while (y <= bottom) {
            val rowBase = y * rowStride
            var x = left
            while (x <= right) {
                val offset = rowBase + x * pixelStride
                val r = buffer.get(offset).toInt() and 0xFF
                if (r > threshold) {
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    if (g > threshold && b > threshold) {
                        sumX += x; sumY += y; count++
                    }
                }
                x += stride
            }
            y += stride
        }

        if (count > 0) {
            val tapX = (sumX / count).toInt()
            val tapY = (sumY / count).toInt()
            c.setPendingTap(tapX, tapY)
            android.util.Log.d("MacroController", "bright center=$tapX,$tapY count=$count")
        }

        // DEBUG: log crop tiap 1s (3 pixel per baris supaya ringan)
        val now = System.currentTimeMillis()
        if (now - lastLogMs > 1000) {
            lastLogMs = now
            val sb = StringBuilder("scan [$left..$right,$top..$bottom] stride=$stride bright=$threshold:")
            var sx = left
            var sy = top
            while (sx <= right && sy <= bottom) {
                val o = sy * rowStride + sx * pixelStride
                val rr = buffer.get(o).toInt() and 0xFF
                val gg = buffer.get(o + 1).toInt() and 0xFF
                val bb = buffer.get(o + 2).toInt() and 0xFF
                sb.append(" ($sx,$sy)=($rr,$gg,$bb)")
                sx += (right - left) / 3
                sy += (bottom - top) / 3
                if (sb.length > 300) break   // batasi log
            }
            android.util.Log.d("MacroController", sb.toString())
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Macro aktif")
            .setContentText("Scan area untuk lingkaran")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Macro Capture", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private const val Activity_RESULT_CANCELED = 0
