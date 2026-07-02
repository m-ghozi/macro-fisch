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

    /** Cuma capture area sekecil mungkin di sekitar bar, bukan full screen -> jauh lebih cepat & ringan. */
    private fun startCropCapture() {
        val width = (MacroController.barRightX - MacroController.barLeftX).coerceAtLeast(2)
        val height = 4 // cukup beberapa baris vertikal di sekitar barY

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MacroCapture",
            width, height, resources.displayMetrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, captureHandler
        )
        // CATATAN: createVirtualDisplay dengan surface sekecil ini otomatis men-scale konten
        // dari layar penuh ke ukuran surface. Supaya crop-nya tepat pada koordinat bar asli,
        // pendekatan yang lebih presisi adalah capture FULL screen lalu crop buffer secara
        // software (lihat catatan di README). Versi ini contoh paling sederhana/cepat untuk
        // kasus di mana bar sudah mendekati lebar layar.

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processFrame(image)
            } finally {
                image.close()
            }
        }, captureHandler)
    }

    private fun processFrame(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width

        // scan 1 baris tengah, cari piksel pertama yang match warna marker
        val row = image.height / 2
        var foundX = -1
        var x = 0
        while (x < width) {
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
        buffer.rewind()

        if (foundX >= 0) {
            val absoluteX = MacroController.barLeftX + foundX
            MacroController.onMarkerDetected(absoluteX)
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