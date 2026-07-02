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
            // dipanggil sistem kalau user cabut izin screen-share dari notifikasi/quick-settings
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

            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(projectionCallback, captureHandler)
        startCropCapture()

        return START_STICKY
    }

    /**
     * Capture FULL screen resolusi asli (1:1, tanpa scaling), lalu crop baris bar secara
     * software di processFrame(). VirtualDisplay selalu mirror SELURUH display — bikin surface
     * kecil malah men-scale seluruh layar dan koordinat jadi ngawur. Karena itu capture penuh,
     * tapi cuma scan 1 baris di barY (tetap ringan).
     */
    private fun startCropCapture() {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        // getRealMetrics kasih orientasi natural (portrait). Game selalu landscape, jadi paksa
        // sisi panjang = lebar biar 1:1 dengan framebuffer landscape (sama seperti `adb screencap`).
        // ponytail: hardcode landscape; kalau nanti perlu portrait, baca display.rotation.
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
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // Koordinat sudah absolut (buffer = full screen), tidak perlu + barLeftX lagi.
        val row = MacroController.barY.coerceIn(0, image.height - 1)
        val left = MacroController.barLeftX.coerceIn(0, image.width - 1)
        val right = MacroController.barRightX.coerceIn(left, image.width - 1)

        var foundX = -1
        var x = left
        while (x <= right) {
            val offset = row * rowStride + x * pixelStride
            val r = buffer.get(offset).toInt() and 0xFF
            val g = buffer.get(offset + 1).toInt() and 0xFF
            val b = buffer.get(offset + 2).toInt() and 0xFF
            if (isMarkerColor(r, g, b)) {
                foundX = x
                break
            }
            x++
        }

        // DEBUG kalibrasi (Fase 3): dump profil warna sepanjang baris bar tiap ~60px, throttle 500ms.
        // Dari sini kita lihat di mana putih/hitam/marker biar set markerColorMin/Max & targetX akurat.
        val now = System.currentTimeMillis()
        if (now - lastLogMs > 500) {
            lastLogMs = now
            val sb = StringBuilder("row=$row scan:")
            var sx = left
            while (sx <= right) {
                val o = row * rowStride + sx * pixelStride
                val rr = buffer.get(o).toInt() and 0xFF
                val gg = buffer.get(o + 1).toInt() and 0xFF
                val bb = buffer.get(o + 2).toInt() and 0xFF
                sb.append(" $sx=($rr,$gg,$bb)")
                sx += 60
            }
            android.util.Log.d("MacroController", sb.toString())
        }

        if (foundX >= 0) {
            MacroController.onMarkerDetected(foundX)
        }
    }

    private fun isMarkerColor(r: Int, g: Int, b: Int): Boolean {
        val min = MacroController.markerColorMin
        val max = MacroController.markerColorMax
        return r in min[0]..max[0] && g in min[1]..max[1] && b in min[2]..max[2]
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Macro aktif")
            .setContentText("Membaca layar untuk sinkronisasi bar")
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
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Kotlin tidak punya Activity.RESULT_CANCELED langsung di sini, definisikan konstanta lokal
private const val Activity_RESULT_CANCELED = 0