package com.example.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class TapMacroAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentStroke: GestureDescription.StrokeDescription? = null
    private var pollRunnable: Runnable? = null

    // Posisi "pena" saat ini. continueStroke WAJIB mulai tepat di titik akhir segmen sebelumnya,
    // kalau meleset (walau 1px) sistem membatalkan gesture. Kita wiggle 1px bolak-balik di sekitar tapX.
    private var penX = 0f
    private var penY = 0f

    // Segmen hold dipecah kecil2 (150ms) supaya bisa dihentikan presisi kapan saja
    private val holdSegmentMs = 150L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Dipanggil dari luar (mis. tombol Start di MainActivity / overlay) untuk memulai 1 siklus. */
    fun startHoldCycle() {
        MacroController.startCycle()
        beginHold()
        startPollingForRelease()
    }

    private fun beginHold() {
        penX = MacroController.tapX.toFloat()
        penY = MacroController.tapY.toFloat()
        val stroke = GestureDescription.StrokeDescription(nextHoldPath(), 0, holdSegmentMs, true)
        currentStroke = stroke
        dispatchSegment(stroke)
    }

    /**
     * Path 1 segmen hold: mulai TEPAT di titik akhir segmen sebelumnya (penX,penY),
     * lalu geser 1px ke arah tapX. Non-empty (wajib) + start point nyambung (wajib biar tak di-cancel).
     */
    private fun nextHoldPath(): Path {
        val startX = penX
        val endX = if (penX > MacroController.tapX.toFloat()) penX - 1f else penX + 1f
        penX = endX
        return Path().apply {
            moveTo(startX, penY)
            lineTo(endX, penY)
        }
    }

    private fun dispatchSegment(stroke: GestureDescription.StrokeDescription) {
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    val queued = dispatchGesture(gesture, object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            android.util.Log.d("TapMacro", "Segment completed at ${System.currentTimeMillis()}")
            if (MacroController.shouldRelease) {
                releaseNow()
            } else if (MacroController.isHolding) {
                val next = currentStroke!!.continueStroke(nextHoldPath(), 0, holdSegmentMs, true)
                currentStroke = next
                dispatchSegment(next)
            }
        }
        override fun onCancelled(gestureDescription: GestureDescription?) {
            android.util.Log.e("TapMacro", "Gesture CANCELLED by system!")
            MacroController.resetCycle()
        }
    }, mainHandler)
    android.util.Log.d("TapMacro", "dispatchGesture queued=$queued at ${System.currentTimeMillis()}")
}

    private fun releaseNow() {
        val stroke = currentStroke ?: return
        // Segmen terakhir juga harus mulai tepat di penX (titik akhir segmen sebelumnya) + non-empty.
        val startX = penX
        val endX = if (penX > MacroController.tapX.toFloat()) penX - 1f else penX + 1f
        val path = Path().apply {
            moveTo(startX, penY)
            lineTo(endX, penY)
        }
        val finalStroke = stroke.continueStroke(path, 0, 10, false) // willContinue=false -> lepas sentuhan
        val gesture = GestureDescription.Builder().addStroke(finalStroke).build()
        dispatchGesture(gesture, null, null)
        MacroController.resetCycle()
        stopPolling()
    }

    /**
     * MacroController.onMarkerDetected() sudah men-set shouldRelease dari thread capture,
     * tapi release fisik tetap ditembak dari sini (siklus onCompleted) supaya tidak dispatch
     * gesture baru saat gesture sebelumnya masih berjalan (bisa bikin gesture dibatalkan sistem).
     * Polling ini hanya fallback jaga-jaga kalau segmen hold kepanjangan.
     */
    private fun startPollingForRelease() {
        pollRunnable = object : Runnable {
            override fun run() {
                if (!MacroController.isHolding) return
                if (MacroController.shouldRelease) {
                    // onCompleted pada segmen berjalan akan handle release;
                    // di sini cuma cek watchdog tiap 20ms.
                }
                mainHandler.postDelayed(this, 20)
            }
        }
        mainHandler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    companion object {
        var instance: TapMacroAccessibilityService? = null
    }
}