# macro-fisch

Macro Android (Kotlin, **no-root**) untuk otomatisasi minigame *hold & release*:
tahan sentuhan di layar, ada marker abu-abu yang bergerak di sepanjang bar horizontal,
dan sentuhan harus dilepas tepat saat marker mencapai posisi target.

Berjalan 100% di HP fisik tanpa root, memanfaatkan API bawaan Android
(MediaProjection + AccessibilityService).

> ⚠️ Status: **work in progress**. Mekanisme hold–release sudah jalan (Fase 1 selesai).
> Kalibrasi koordinat/warna dan integrasi mata+tangan (Fase 2–5) belum selesai.
> Nilai koordinat & warna di `MacroController.kt` masih placeholder — **wajib dikalibrasi per device**.

## Cara kerja

1. **MediaProjection API** — capture layar, hanya crop kecil di area bar (bukan full screen) demi performa.
2. **AccessibilityService + `dispatchGesture()`** — kirim sentuhan tahan–lepas tanpa root.
3. Baca warna piksel langsung dari `ByteBuffer` (`Image.planes[0]`), tanpa konversi ke Bitmap.
4. **Hold dinamis** pakai `continueStroke()` — stroke dipecah jadi segmen ~150ms yang disambung terus
   sampai kondisi release terpenuhi. Tiap segmen wajib mulai tepat di titik akhir segmen sebelumnya,
   kalau tidak sistem membatalkan gesture (`CANCELLED`).

## Struktur

```
app/src/main/java/com/example/macro/
├── MainActivity.kt                   — UI + trigger izin (MediaProjection, overlay)
├── MacroController.kt                — state singleton + nilai kalibrasi (koordinat, warna marker)
├── ScreenCaptureService.kt           — foreground service + VirtualDisplay + ImageReader
├── TapMacroAccessibilityService.kt   — gesture hold/release via dispatchGesture()
└── FloatingOverlayService.kt         — overlay status (capture/accessibility ON-OFF) + tombol start/stop
```

## Build & install (tanpa Android Studio)

Dev di command line (Linux) + device fisik via adb:

```bash
./gradlew assembleDebug     # build APK debug
./gradlew installDebug      # install ke device yang tersambung
```

Prasyarat: JDK 17, Android SDK (set `local.properties` → `sdk.dir=/path/ke/Android/Sdk`),
device dengan USB debugging aktif.

## Cara pakai di device

1. Buka app → **Buka Pengaturan Accessibility** → aktifkan *Macro Skill Check*.
2. **Izinkan Capture Layar** (MediaProjection).
3. **Show Overlay** → izinkan *Display over other apps* bila diminta, lalu tekan lagi.
4. Buka game, tekan **Mulai 1 Siklus** / **START** saat bar muncul.

Untuk uji mekanisme hold–release saja: tombol **TEST: Hold 1 Detik lalu Release**.

## Debugging

```bash
adb logcat -c && adb logcat | grep -E "TapMacro|MacroController|FloatingOverlay|AndroidRuntime"
```

Aktifkan **Developer Options → Show taps / Pointer location** untuk melihat sentuhan yang diinject.

## Kalibrasi (`MacroController.kt`)

Sesuaikan per device/resolusi (dalam pixel resolusi asli, bukan dp):

- `barLeftX`, `barRightX`, `barY` — area bar.
- `targetX` — posisi target yang dituju.
- `tapX`, `tapY` — titik sentuh saat hold.
- `markerColorMin` / `markerColorMax` — rentang RGB marker abu-abu.
- `toleranceStopPx` — toleransi jarak marker ke target sebelum release ditembak.

## Catatan

- `minSdk = 26` (butuh `continueStroke()`), `targetSdk = 34`.
- Package: `com.example.macro`.
