package com.example.macro

/**
 * Singleton penghubung ScreenCaptureService (baca warna) <-> TapMacroAccessibilityService (kirim tap).
 * Sengaja pakai object biasa + callback (bukan LiveData/Flow) supaya overhead minimal.
 */
object MacroController {

    // ====== KALIBRASI - WAJIB DISESUAIKAN per device/resolusi ======
    // Koordinat area bar di layar (dalam pixel resolusi asli device, BUKAN dp).
    // Cara dapetin: buka Developer Options > Pointer Location, atau screenshot lalu ukur pakai editor gambar.
    var barLeftX = 130
    var barRightX = 930
    var barY = 175                 // Y tempat garis horizontal bar (tempat marker abu-abu bergerak)
    var targetX = 526              // X posisi target/garis batas yang mau dituju (kalibrasi manual)
    var tapX = 500                 // Titik X yang disentuh saat hold (biasanya tengah layar, sesuai instruksi "Click & Hold Anywhere")
    var tapY = 600

    // Warna marker abu-abu (dari screenshot kira-kira RGB ~ (140,140,150)) -> kalibrasi pakai tool debug di bawah
    var markerColorMin = intArrayOf(120, 120, 130)
    var markerColorMax = intArrayOf(170, 170, 180)

    var toleranceStopPx = 4        // toleransi jarak (px) marker ke target sebelum release ditembak
    // ================================================================

    @Volatile var isHolding = false
    @Volatile var shouldRelease = false
    @Volatile var lastMarkerX: Int = -1

    // dipanggil dari ScreenCaptureService tiap frame baru
    fun onMarkerDetected(markerX: Int) {
        lastMarkerX = markerX
        if (isHolding && kotlin.math.abs(markerX - targetX) <= toleranceStopPx) {
            shouldRelease = true
        }
    }

    fun startCycle() {
        isHolding = true
        shouldRelease = false
    }

    fun resetCycle() {
        isHolding = false
        shouldRelease = false
        lastMarkerX = -1
    }

    fun forceRelease() {
    shouldRelease = true
}
}