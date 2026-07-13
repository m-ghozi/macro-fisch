package com.example.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TapMacroAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanningRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun startScanLoop() {
        MacroController.startScanning()
        scanLoop()   // polling for pendingTap
    }

    fun stopScanLoop() {
        MacroController.stopScanning()
        scanningRunnable?.let { mainHandler.removeCallbacks(it) }
        scanningRunnable = null
    }

    private fun scanLoop() {
        scanningRunnable = object : Runnable {
            override fun run() {
                val c = MacroController
                if (!c.isScanning) return

                val tx = c.pendingTapX
                val ty = c.pendingTapY
                if (tx >= 0 && ty >= 0) {
                    dispatchSingleTap(tx.toFloat(), ty.toFloat())
                    // cooldown ~400ms setelah tap — kasih waktu lingkaran menghilang
                    // ponytail: hardco, nanti kalau perlu adjustable via overlay.
                    c.clearPendingTap()
                    c.nextScanAtMs = System.currentTimeMillis() + 400
                }

                mainHandler.postDelayed(this, 30) // ~33fps polling
            }
        }
        mainHandler.post(scanningRunnable!!)
    }

    /**
     * Single tap di (x, y) via dispatchGesture.
     * Path: moveTo(x,y) -> lineTo(x,y) (non-empty) -> durasi 50ms -> willContinue=false.
     */
    private fun dispatchSingleTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)   // non-empty
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        android.util.Log.d("TapMacro", "tap $x,$y")
    }

    companion object {
        var instance: TapMacroAccessibilityService? = null
    }
}
