package com.example.macro

import android.app.*
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
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
    private lateinit var panelView: LinearLayout
    private lateinit var params: WindowManager.LayoutParams

    private var guideView: GuideView? = null

    // Panel: status
    private lateinit var tvCapture: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var toggleButton: Button

    // Panel: nudge rows (L R T B threshold)
    private lateinit var tvL: TextView; private lateinit var tvR: TextView
    private lateinit var tvT: TextView; private lateinit var tvB: TextView
    private lateinit var tvThreshold: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusPoller: Runnable? = null

    // Step setiap nudge
    private val nudgeStep = 10

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildGuideOverlay()
        buildControlPanel()
        startStatusPolling()
    }

    // ============ GUIDE OVERLAY ============

    private fun buildGuideOverlay() {
        val view = GuideView(this)
        guideView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        windowManager.addView(view, lp)
    }

    private inner class GuideView(ctx: android.content.Context) : View(ctx) {
        private val rectPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val tapPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
        private val pendingPaint = Paint().apply { color = Color.YELLOW; strokeWidth = 6f }
        private val textPaint = Paint().apply { color = Color.WHITE; textSize = 24f; isAntiAlias = true }

        override fun onDraw(canvas: Canvas) {
            val c = MacroController
            // persegi area scan
            canvas.drawRect(c.scanLeft.toFloat(), c.scanTop.toFloat(),
                c.scanRight.toFloat(), c.scanBottom.toFloat(), rectPaint)
            canvas.drawText("scan area", c.scanLeft.toFloat() + 4f, c.scanTop.toFloat() - 6f, textPaint)

            // titik pending tap
            val px = c.pendingTapX; val py = c.pendingTapY
            if (px >= 0 && py >= 0) {
                canvas.drawCircle(px.toFloat(), py.toFloat(), 16f, pendingPaint)
                canvas.drawText("tap", px.toFloat() + 20f, py.toFloat() + 6f, textPaint)
            }

            // text scan rect coords
            val info = "L=${c.scanLeft} R=${c.scanRight} T=${c.scanTop} B=${c.scanBottom} bright>${c.brightThreshold}"
            canvas.drawText(info, 20f, height - 40f, textPaint)
        }
    }

    // ============ CONTROL PANEL ============

    private fun buildControlPanel() {
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.argb(220, 20, 20, 20))
        }

        // --- Status ---
        tvCapture = makeText("Capture: OFF")
        tvAccessibility = makeText("Accessibility: OFF")

        toggleButton = Button(this).apply {
            text = "START SCAN"
            setOnClickListener {
                val tap = TapMacroAccessibilityService.instance
                if (MacroController.isScanning) {
                    tap?.stopScanLoop()
                    text = "START SCAN"
                } else {
                    if (tap == null) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@setOnClickListener
                    }
                    if (!ScreenCaptureService.isRunning) {
                        android.widget.Toast.makeText(this@FloatingOverlayService, "Capture belum aktif!", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    tap.startScanLoop()
                    text = "STOP"
                }
            }
        }
        panelView.addView(tvCapture)
        panelView.addView(tvAccessibility)
        panelView.addView(toggleButton)

        // --- Nudge rows ---
        panelView.addView(makeDivider())
        tvL = makeNudgeRow("L (kiri)", "scanLeft") { MacroController.scanLeft }
        tvR = makeNudgeRow("R (kanan)", "scanRight") { MacroController.scanRight }
        tvT = makeNudgeRow("T (atas)", "scanTop") { MacroController.scanTop }
        tvB = makeNudgeRow("B (bawah)", "scanBottom") { MacroController.scanBottom }

        panelView.addView(makeDivider())
        tvThreshold = makeNudgeRow("Terang >", "brightThreshold", step = 10, min = 10, max = 255) { MacroController.brightThreshold }

        // --- root wrapper biar bisa drag ---
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootView.addView(panelView)
        makeDraggable()

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }

        windowManager.addView(rootView, params)
    }

    /** Buat 1 baris: [label] [-] [value] [+] */
    private fun makeNudgeRow(
        label: String, field: String, step: Int = nudgeStep,
        min: Int? = null, max: Int? = null,
        getter: () -> Int
    ): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val labelView = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 12f
            setPadding(0, 0, 8, 0)
        }
        val valueView = TextView(this).apply {
            text = "${getter()}"; setTextColor(Color.CYAN); textSize = 12f
            minWidth = 80
        }
        fun updateVal() {
            valueView.text = "${getter()}"
            guideView?.invalidate()
        }
        fun setField(v: Int) = when (field) {
            "scanLeft" -> MacroController.scanLeft = v
            "scanRight" -> MacroController.scanRight = v
            "scanTop" -> MacroController.scanTop = v
            "scanBottom" -> MacroController.scanBottom = v
            "brightThreshold" -> MacroController.brightThreshold = v
            else -> {}
        }
        val btnMinus = Button(this).apply {
            text = "−"; textSize = 12f
            setOnClickListener {
                val new = getter() - step
                if (min == null || new >= min) { setField(new); updateVal() }
            }
        }
        val btnPlus = Button(this).apply {
            text = "+"; textSize = 12f
            setOnClickListener {
                val new = getter() + step
                if (max == null || new <= max) { setField(new); updateVal() }
            }
        }
        row.addView(labelView)
        row.addView(btnMinus)
        row.addView(valueView)
        row.addView(btnPlus)
        panelView.addView(row)
        return valueView
    }

    private fun makeText(s: String) = TextView(this).apply {
        text = s; setTextColor(Color.WHITE); textSize = 12f
    }

    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 2
        ).apply { setMargins(0, 6, 0, 6) }
        setBackgroundColor(Color.GRAY)
    }

    private fun makeDraggable() {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        rootView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = event.rawX; ty = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ix + (event.rawX - tx).toInt()
                    params.y = iy + (event.rawY - ty).toInt()
                    windowManager.updateViewLayout(rootView, params); true
                }
                else -> false
            }
        }
    }

    // ============ POLLING ============

    private fun startStatusPolling() {
        statusPoller = object : Runnable {
            override fun run() {
                tvCapture.text = "Capture: ${if (ScreenCaptureService.isRunning) "ON" else "OFF"}"
                tvAccessibility.text = "Accessibility: ${if (TapMacroAccessibilityService.instance != null) "ON" else "OFF"}"
                toggleButton.text = if (MacroController.isScanning) "STOP" else "START SCAN"
                // refresh value text
                tvL.text = "${MacroController.scanLeft}"
                tvR.text = "${MacroController.scanRight}"
                tvT.text = "${MacroController.scanTop}"
                tvB.text = "${MacroController.scanBottom}"
                tvThreshold.text = "${MacroController.brightThreshold}"
                guideView?.invalidate()
                mainHandler.postDelayed(this, 300)
            }
        }
        mainHandler.post(statusPoller!!)
    }

    private fun startForegroundWithNotification() {
        val channelId = "macro_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Macro Overlay", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Macro aktif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        startForeground(2, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        statusPoller?.let { mainHandler.removeCallbacks(it) }
        if (::rootView.isInitialized) windowManager.removeView(rootView)
        guideView?.let { windowManager.removeView(it) }
    }
}
