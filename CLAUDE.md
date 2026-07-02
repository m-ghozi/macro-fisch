# macro-fisch — Context untuk Claude

## Tentang project ini

Macro Android (Kotlin, **no-root**, harus 100% jalan di HP fisik) untuk otomatisasi
minigame "hold & release": tahan sentuhan di layar, ada marker abu-abu yang bergerak
di sepanjang bar horizontal, sentuhan harus dilepas tepat saat marker mencapai posisi target.

**Environment dev:** Laptop Linux/Arch, command line + VS Code, **TANPA Android Studio**.
Build & install pakai `./gradlew assembleDebug` dan `./gradlew installDebug` via adb ke device fisik.

Developer masih **awam Kotlin/Android** — kalau kasih instruksi edit kode, selalu sebutkan
**path file persis** dan **posisi paste yang jelas** (bukan cuma kasih snippet lepas).
Package name: `com.example.macro`.

## Arsitektur

1. **MediaProjection API** — capture layar, crop kecil di area bar (bukan full screen)
2. **AccessibilityService + dispatchGesture()** — kirim sentuhan tahan-lepas
3. Baca warna piksel langsung dari `ByteBuffer` (`Image.planes[0]`), **TIDAK** convert ke Bitmap (demi performa)
4. Hold dinamis pakai `continueStroke()` — stroke dipecah segmen ~150ms, disambung terus sampai kondisi release terpenuhi

## Struktur file

```
app/src/main/java/com/example/macro/
├── MainActivity.kt                    — UI + trigger izin (MediaProjection, overlay)
├── MacroController.kt                 — state singleton (tapX, tapY, isHolding, shouldRelease, dll)
├── ScreenCaptureService.kt            — foreground service + VirtualDisplay + ImageReader
├── TapMacroAccessibilityService.kt    — gesture hold/release via dispatchGesture()
└── FloatingOverlayService.kt          — overlay status (capture ON/OFF, accessibility ON/OFF, tombol start/stop)
```

## Bug yang SUDAH diperbaiki (jangan diulang / disarankan lagi)

- `MainActivity` harus extends `ComponentActivity` (androidx.activity), **bukan** `android.app.Activity` biasa
  — karena `registerForActivityResult()` cuma ada di `ComponentActivity`.
- `MediaProjection` **wajib** `registerCallback(callback, handler)` **sebelum** `createVirtualDisplay()`,
  kalau tidak crash `IllegalStateException` di Android target SDK 34+. Sudah ada `projectionCallback`
  dengan `onStop()` yang release `virtualDisplay`/`imageReader` dan `stopSelf()`.

## Bug yang teridentifikasi, status fix per sesi terakhir

- [x] **Return value `dispatchGesture()` gak dicek** — kalau `false`, gesture gagal di-queue,
  callback `onCompleted`/`onCancelled` gak pernah terpanggil, chain segmen mati diam-diam.
  Fix: cek `queued = dispatchGesture(...)`, log hasilnya. **Perlu verifikasi apakah root cause
  "nothing happen" di Fase 1 ada di sini** — cek `android:canPerformGestures="true"` di
  `res/xml/accessibility_service_config.xml`.
- [x] **Path cuma `moveTo()` tanpa `lineTo()`** — berpotensi `IllegalArgumentException`
  "zero length path" di beberapa versi Android. Fix: tambah `lineTo()` ke titik sama.
- [ ] **Presisi release dibatasi granularitas segmen 150ms** — bukan bug, tapi keterbatasan
  desain. Release fisik cuma dicek di `onCompleted`, delay worst-case ~150ms dari momen ideal.
  Trade-off: perkecil `holdSegmentMs` → lebih presisi tapi risiko gap antar segmen naik.
  Perlu ditentukan angka optimal secara empiris nanti di Fase 5.
- [ ] `pollRunnable` di `TapMacroAccessibilityService` masih dead code (watchdog gak ngapa-ngapain).
  Rencana: kalau `shouldRelease && isHolding` masih true >300-500ms, panggil `releaseNow()` paksa
  sebagai safety net (buat kasus gesture macet karena `dispatchGesture` gagal).
- [ ] Perlu pastikan `MacroController.shouldRelease` / `isHolding` pakai `@Volatile` atau
  `AtomicBoolean` (diset dari thread capture ImageReader, dibaca di main thread).
- [ ] Typo di `FloatingOverlayService.kt` versi awal: `private lateinit name toggleButton: Button`
  harusnya `private lateinit var toggleButton: Button` — cek udah kefix belum saat lanjut.

## Progress testing

- [x] Build sukses di Arch Linux (command line, no Android Studio)
- [x] Install ke device via adb berhasil
- [x] Izin screen capture (MediaProjection) sudah tidak crash lagi
- [ ] **Fase 1** (validasi mekanisme hold-release, tombol "TEST: Hold 1 Detik lalu Release",
  cek pakai Developer Options > Show taps apakah hold-nya solid) — user report "sudah apply
  fix manual, nothing happen". **Belum dikonfirmasi dari logcat** apakah `queued=false` atau
  ada exception lain. Prioritas sesi berikutnya: minta user paste logcat filter
  `adb logcat | grep -E "TapMacro|MacroController"` dari satu siklus test.
- [ ] Fase 2 (validasi capture warna — baca Logcat apakah RGB yang kebaca match kalibrasi manual) — belum dimulai
- [ ] Fase 3 (kalibrasi koordinat & warna real dari device) — belum dilakukan, semua nilai
  di `MacroController.kt` masih placeholder tebakan
- [ ] Fase 4 (integrasi mata+tangan) — belum
- [ ] Fase 5 (tuning presisi/latency) — belum

## Fitur tambahan sedang dikerjakan

- **Floating overlay** (`SYSTEM_ALERT_WINDOW` + `WindowManager`) — indikator status
  (capture aktif/tidak, accessibility aktif/tidak) + tombol start/stop, draggable.
  Kode sudah dikasih, user sedang proses paste manual (awam Kotlin, perlu panduan lokasi file persis).
  Dependency yang perlu ditambahkan manual:
  - `ScreenCaptureService.isRunning` (companion object, di-set true/false di lifecycle service)
  - `MacroController.forceRelease()` (set `shouldRelease = true`)
  - Manifest: `<uses-permission SYSTEM_ALERT_WINDOW>` + `<service FloatingOverlayService>`
  - `MainActivity`: fungsi `startOverlayIfPermitted()` + tombol trigger

## Preferensi user

- Bahasa Indonesia untuk penjelasan, commit message, dan UI copy.
- Diff scoped/minimal, hindari refactor besar-besar tanpa diminta.
- Debugging iteratif: isolasi layer dulu (capture / gesture / logic) sebelum kasih fix,
  jangan asumsi root cause tanpa lihat log.
- User awam Kotlin — selalu kasih path file eksplisit dan posisi paste yang jelas,
  jangan cuma kasih snippet lepas tanpa konteks penempatan.
