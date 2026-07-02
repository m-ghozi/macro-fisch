package com.example.macro

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
        }
    }
    private fun startOverlayIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Izinkan 'Display over other apps', lalu tekan tombol overlay lagi", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, FloatingOverlayService::class.java))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI seadanya, ganti dengan layout XML kalau mau
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val info = TextView(this).apply {
            text = "1. Aktifkan Accessibility Service dulu\n" +
                    "2. Tap 'Izinkan Capture Layar'\n" +
                    "3. Buka game, lalu tap 'Mulai 1 Siklus' saat bar muncul"
            setPadding(32, 32, 32, 32)
        }

        val btnAccessibility = Button(this).apply {
            text = "Buka Pengaturan Accessibility"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnCapture = Button(this).apply {
            text = "Izinkan Capture Layar"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        val btnStart = Button(this).apply {
            text = "Mulai 1 Siklus (Hold)"
            setOnClickListener {
                TapMacroAccessibilityService.instance?.startHoldCycle()
                    ?: run {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
            }
        }

        // === SEMENTARA untuk Fase 1: test hold-release TANPA capture warna ===
        val btnTestHold = Button(this).apply {
            text = "TEST: Hold 1 Detik lalu Release"
            setOnClickListener {
                val service = TapMacroAccessibilityService.instance
                if (service == null) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                service.startHoldCycle()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    MacroController.shouldRelease = true
                }, 1000)
            }
        }

        val btnOverlay = Button(this).apply {
            text = "Show Overlay"
            setOnClickListener {
                startOverlayIfPermitted()
            }
        }

        layout.addView(info)
        layout.addView(btnAccessibility)
        layout.addView(btnCapture)
        layout.addView(btnStart)
        layout.addView(btnTestHold)
        layout.addView(btnOverlay)
        setContentView(layout)
    }
}