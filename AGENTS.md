# AGENTS.md

Guidance for AI coding agents working in this repo. Humans: see [`README.md`](README.md).

## What this is

An Android app (Kotlin + Jetpack Compose) that downloads RAW (CR3) images from Canon
EOS cameras. It pairs over Bluetooth LE the way Canon's Camera Connect does, gets the
camera's Wi-Fi over BLE, joins it, and talks **PTP/IP** (TCP 15740). No CCAPI, no Canon
account. The native protocol layer is vendored **libpict** (C) behind a thin JNI bridge;
all higher-level logic (BLE, listing, CR3 parsing, caching, UI) is Kotlin.

## Build, test, run

Always export a JDK 17+ first; the wrapper is Gradle 8.10.2.

```bash
export JAVA_HOME=/path/to/jdk17+        # macOS/brew: "$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug                 # builds APK incl. the NDK/libpict native lib
./gradlew testDebugUnitTest             # JVM unit tests — keep green
tools/ptpip-harness/run.sh              # native PTP/IP code (libpict + ptp_jni.c) against a fake camera
```

The harness compiles the real `ptp_jni.c` and libpict for the host and runs scenarios (camera
closes mid-packet, goes silent, answers slowly, refuses the handshake) against
`tools/ptpip-harness/fake_camera.py`. Run it after any change to the native layer.

On-device install & logs (a device must be attached; Wi-Fi debugging works too):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -b main,crash -v time        # native crashes show as SIGABRT/SIGSEGV with a libpict backtrace
```

## Layout

```
app/src/main/cpp/
  libpict/            vendored petabyt/libpict (Apache-2.0). See PATCHES.md (repo root) before editing.
  ptp_jni.c           JNI bridge: pairing-GUID handshake + PTP ops. Camera-specific quirks live HERE.
  CMakeLists.txt      builds libpict (-DPTP_NO_USB=ON) + ptpjni (16 KB page aligned)
app/src/main/java/de/bestora/canonwirelessrawandroid/
  ble/    CanonBleProtocol (UUIDs, byte layouts, parsers), CanonBle (GATT client)
  ptp/    PtpClient (coroutine/mutex wrapper), PtpNative (JNI decls), PtpModels (incl. EOS event parsing)
  cr3/    ImageHeaderParser (rating/JPEG parsing), Cr3Container (ISO-BMFF box walker)
  data/   CameraRepository (session, reconnect, keep-alive, live photos), CameraConnector (BLE → Wi-Fi
          → PTP/IP), ObjectListCache (file list per camera), MetaCache, Saver, Prefs (camera profiles)
  wifi/   WifiConnector (WifiNetworkSpecifier + process binding)
  ui/     Compose screens: Connect, Credentials, Gallery, ImageViewer, Download, Debug; Theme (tokens,
          buttons), EditorLauncher ("Open in Luminar")
  CameraSessionService.kt  foreground service while a session is open
  AppContainer.kt, MainActivity.kt (plain-state navigation)
tools/ptpip-harness/  fake PTP/IP camera + host build of the native layer
docs/superpowers/     design specs + implementation plans
docs/MILESTONE-0-PROTOKOLL.md   on-camera verification checklist
```

## Conventions & constraints (don't break these)

- **libpict is vendored, not modified** — quirks/fixes belong in `ptp_jni.c`. The one
  unavoidable upstream edit (a genuine bug) is documented in `PATCHES.md`; re-apply it
  after any libpict update. Don't add other libpict edits without documenting them there.
- **No new dependencies** without strong reason — Compose + Coroutines + AndroidX only.
- **minSdk 29, compileSdk 35**, ABIs `arm64-v8a`/`x86_64`, package `de.bestora.canonwirelessrawandroid`.
- **All PTP calls go through `PtpClient` on `Dispatchers.IO`, serialized by a `Mutex`** —
  the native runtime is not safe for concurrent transactions. `cancelIo()` is the one
  call outside the mutex: it only sets a flag, and `downloadTo` stops at the next chunk.
  Never cut a transfer mid-packet (see the camera gotchas).
- **Every camera call in `CameraRepository` goes through `session { }`.** On a dead session it
  reconnects once and retries the call; all calls only read, and `transfer` resumes at the byte it
  reached. A failed silent reconnect sets `Link.LOST`, and calls fail fast until the user reconnects.
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

- **Smartphone mode needs the BLE registration first.** PTP/IP to a camera in "Connect to
  smartphone" mode fails with `Err 11` unless the phone's GUID was registered over BLE
  (`CanonBle.register`). The BLE GUID is sent byte-reversed; PTP/IP uses it unreversed.
- **A smartphone-mode session needs exactly what Canon's SDK sends**: session ID `0x41`
  (`ptp_jni.c` openSession), EOS remote mode `0x15` (`REMOTE_MODE_SMARTPHONE`), and the
  `DoNothing` ping (`0x902F`) every 5 s. Without them the camera shows "please wait" and drops
  Wi-Fi and BLE after about a minute.
- **Cutting a transfer mid-packet makes the R5 drop its Wi-Fi.** Cancel at chunk boundaries.
- **A camera keeps its Wi-Fi up for a while after a drop and refuses a new BLE handover then.**
  `CameraConnector.reconnect` rejoins with the last Wi-Fi config first.
- **Android drops a `WifiNetworkSpecifier` network ~8 s after the app leaves the foreground.**
  `CameraSessionService` (foreground service) keeps it; `MainActivity` releases the Wi-Fi after
  5 minutes in the background.
- **Samsung disables STA+STA for local-only networks**: the camera Wi-Fi replaces the phone's
  Wi-Fi, so wireless adb drops during camera tests. Debug over USB.
- **BLE bond broadcasts come from the Bluetooth app, not the system uid**: register the receiver
  `RECEIVER_EXPORTED`, or it never fires.
- **Dual-slot bodies recording to both cards report each photo twice** (one handle per card).
  The gallery dedupes by name and size.
- In EOS Utility mode the pairing confirmation on the camera has a short timeout;
  `CameraRepository.connect` retries every second for a minute to catch it.

## Verifying real changes

Protocol/UI changes that touch the camera path can't be proven by unit tests. Build,
install, and drive the flow against a camera (or the Debug screen's step buttons); for
CR3 parsing you can also pull a `.cr3` off the device and inspect it (`exiftool`, or a
BMFF box walk) — that's how the container offsets in `Cr3Container` were established.
