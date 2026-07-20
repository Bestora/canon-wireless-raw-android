# AGENTS.md

Guidance for AI coding agents working in this repo. Humans: see [`README.md`](README.md).

## What this is

An Android app (Kotlin + Jetpack Compose) that downloads RAW (CR3) images from Canon
EOS cameras over **PTP/IP** (Wi-Fi, TCP 15740) — no CCAPI, no Canon account. The
native protocol layer is vendored **libpict** (C) behind a thin JNI bridge; all
higher-level logic (listing, CR3 parsing, caching, UI) is Kotlin.

## Build, test, run

Always export a JDK 17+ first; the wrapper is Gradle 8.10.2.

```bash
export JAVA_HOME=/path/to/jdk17+        # macOS/brew: "$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug                 # builds APK incl. the NDK/libpict native lib
./gradlew testDebugUnitTest             # JVM unit tests — keep green
```

On-device install & logs (a device must be attached; Wi-Fi debugging works too):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -b main,crash -v time        # native crashes show as SIGABRT/SIGSEGV with a libpict backtrace
```

## Layout

```
app/src/main/cpp/
  libpict/            vendored petabyt/libpict (Apache-2.0). See PATCHES.md before editing.
  ptp_jni.c           JNI bridge: pairing-GUID handshake + PTP ops. Camera-specific quirks live HERE.
  CMakeLists.txt      builds libpict (-DPTP_NO_USB=ON) + ptpjni
app/src/main/java/io/github/canonwirelessraw/
  ptp/    PtpClient (coroutine/mutex wrapper), PtpNative (JNI decls), PtpModels
  cr3/    ImageHeaderParser (rating/JPEG parsing), Cr3Container (ISO-BMFF box walker)
  data/   CameraRepository (the core), MetaCache (disk cache), Saver (MediaStore), WifiConnector, Prefs
  ui/     Compose screens: Connect, Credentials, Gallery, ImageViewer, Download, Debug
  AppContainer.kt, MainActivity.kt (plain-state navigation)
docs/superpowers/     design specs + implementation plans
docs/MILESTONE-0-PROTOKOLL.md   on-camera verification checklist
```

## Conventions & constraints (don't break these)

- **libpict is vendored, not modified** — quirks/fixes belong in `ptp_jni.c`. The one
  unavoidable upstream edit (a genuine bug) is documented in `PATCHES.md`; re-apply it
  after any libpict update. Don't add other libpict edits without documenting them there.
- **No new dependencies** without strong reason — Compose + Coroutines + AndroidX only.
- **minSdk 29, compileSdk 35**, ABIs `arm64-v8a`/`x86_64`, package `io.github.canonwirelessraw`.
- **All PTP calls go through `PtpClient` on `Dispatchers.IO`, serialized by a `Mutex`** —
  the native runtime is not safe for concurrent transactions. `cancelIo()` is the one
  call intentionally outside the mutex (it must interrupt an in-flight transfer).
- **Parse CR3 by walking boxes, not by scanning bytes.** `Cr3Container` navigates
  `moov`→`uuid`→`THMB`/`CMT1` and the top-level `PRVW`/XMP uuid boxes. A naive JPEG
  marker scan false-matches on Canon makernote bytes (e.g. `FFD8FFBF`) → corrupt
  thumbnails. Keep extraction deterministic.
- **`MetaCache` dir is versioned** (`meta-vN` in `AppContainer`). Bump the suffix whenever
  thumbnail/rating/orientation extraction changes, so stale/corrupt entries are abandoned.
- **Embedded JPEGs carry no EXIF** — orientation comes from the CR3's `CMT1` block and is
  applied on display (`ui/BitmapRotation.kt`).
- Keep unit tests JVM-only (no Robolectric). Android-transport classes (`WifiConnector`,
  `BleWaker`, native PTP) are verified on-device via the Debug screen, not in unit tests.
- Deliberate simplifications are marked with `// ponytail:` comments naming the ceiling.

## Camera gotchas (learned the hard way, on real hardware)

- PTP/IP needs the camera's **Remote control (EOS Utility)** mode + "Add a device", **not**
  "Connect to smartphone" (Bluetooth-first, Canon-app only → `Err 11`).
- The pairing confirmation on the camera has a **few-second timeout**; connect must be
  driven right after selecting it.
- Auto Wi-Fi join binds the whole process to the internet-less camera network
  (`bindProcessToNetwork`); it MUST be released on every exit incl. app background, or the
  process is stranded without internet. See `MainActivity` (ON_STOP) and `WifiConnector.release()`.

## Verifying real changes

Protocol/UI changes that touch the camera path can't be proven by unit tests. Build,
install, and drive the flow against a camera (or the Debug screen's step buttons); for
CR3 parsing you can also pull a `.cr3` off the device and inspect it (`exiftool`, or a
BMFF box walk) — that's how the container offsets in `Cr3Container` were established.
