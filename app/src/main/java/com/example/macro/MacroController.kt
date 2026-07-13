package com.example.macro

/**
 * Singleton state — ScreenCaptureService (scan) <-> TapMacroAccessibilityService (tap).
 */
object MacroController {

    // ===== AREA SCAN (pixel absolut, resolusi landscape) =====
    // Atur lewat overlay nudge buttons.
    var scanLeft = 400
    var scanTop = 200
    var scanRight = 2000
    var scanBottom = 1000

    // Piksel dengan R, G, B > nilai ini dianggap "putih" (outline lingkaran)
    var brightThreshold = 200
    // ========================================================

    @Volatile var isScanning = false
    @Volatile var pendingTapX = -1
    @Volatile var pendingTapY = -1

    // Cooldown setelah tap (ms) — biar gak ngetap lingkaran yang sama 2×
    @Volatile var nextScanAtMs = 0L

    fun startScanning() {
        isScanning = true
        pendingTapX = -1
        pendingTapY = -1
        nextScanAtMs = 0L
    }

    fun stopScanning() {
        isScanning = false
        pendingTapX = -1
        pendingTapY = -1
    }

    fun setPendingTap(x: Int, y: Int) {
        pendingTapX = x
        pendingTapY = y
    }

    fun clearPendingTap() {
        pendingTapX = -1
        pendingTapY = -1
    }

    /** Dipanggil tiap frame — cek apakah boleh scan sekarang. */
    fun canScan(): Boolean {
        if (!isScanning) return false
        if (pendingTapX >= 0) return false   // udah nemu, tunggu ditao
        if (System.currentTimeMillis() < nextScanAtMs) return false // cooldown
        return true
    }
}
