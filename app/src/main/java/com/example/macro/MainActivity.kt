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
            Toast.makeText(this, "Izinkan 'Display over other apps', lalu tekan Show Overlay lagi", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, FloatingOverlayService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val info = TextView(this).apply {
            text = "Setup:\n" +
                    "1. Accessibility Service → nyalakan Macro Skill Check\n" +
                    "2. Capture Layar → izinkan\n" +
                    "3. Show Overlay → atur area scan + start"
            setPadding(32, 32, 32, 32)
        }

        val btnAccessibility = Button(this).apply {
            text = "Buka Pengaturan Accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val btnCapture = Button(this).apply {
            text = "Izinkan Capture Layar"
            setOnClickListener {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        val btnOverlay = Button(this).apply {
            text = "Show Overlay"
            setOnClickListener { startOverlayIfPermitted() }
        }

        layout.addView(info)
        layout.addView(btnAccessibility)
        layout.addView(btnCapture)
        layout.addView(btnOverlay)
        setContentView(layout)
    }
}
