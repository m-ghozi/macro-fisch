package com.example.macro

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootView: LinearLayout
    private lateinit var tvCapture: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var toggleButton: Button
    private lateinit var params: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusPoller: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlayView()
        startStatusPolling()
    }

    private fun buildOverlayView() {
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.argb(220, 20, 20, 20))
        }

        tvCapture = TextView(this).apply {
            text = "Capture: OFF"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        tvAccessibility = TextView(this).apply {
            text = "Accessibility: OFF"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        toggleButton = Button(this).apply {
            text = "START"
            setOnClickListener {
                if (MacroController.isHolding) {
                    MacroController.forceRelease()
                    text = "START"
                } else {
                    TapMacroAccessibilityService.instance?.startHoldCycle()
                    text = "STOP"
                }
            }
        }

        rootView.addView(tvCapture)
        rootView.addView(tvAccessibility)
        rootView.addView(toggleButton)

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        makeDraggable()
        windowManager.addView(rootView, params)
    }

    /** Drag overlay dengan sentuh-tahan di background root view (bukan di tombol). */
    private fun makeDraggable() {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(rootView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun startStatusPolling() {
        statusPoller = object : Runnable {
            override fun run() {
                tvCapture.text = "Capture: ${if (ScreenCaptureService.isRunning) "ON" else "OFF"}"
                tvAccessibility.text =
                    "Accessibility: ${if (TapMacroAccessibilityService.instance != null) "ON" else "OFF"}"
                toggleButton.text = if (MacroController.isHolding) "STOP" else "START"
                mainHandler.postDelayed(this, 500)
            }
        }
        mainHandler.post(statusPoller!!)
    }

    private fun startForegroundWithNotification() {
        val channelId = "macro_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Macro Overlay", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Macro overlay aktif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(2, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusPoller?.let { mainHandler.removeCallbacks(it) }
        if (::rootView.isInitialized) windowManager.removeView(rootView)
    }
}