<img src="assets/logo.svg" alt="App logo" width="96" align="right">

# Canon Wireless RAW for Android

Wireless CR3/RAW download from Canon EOS cameras straight to your Android phone —
over plain **PTP/IP**, the same Wi-Fi protocol `gphoto2` and Canon's own tools use.
No CCAPI activation, no Canon account, no cloud, no PC in the loop.

> ## Status: working alpha
> The full pipeline — connect, list ~1200+ images, read in-camera star ratings,
> extract thumbnails and full-HD previews, and download full-resolution CR3s — has
> been **verified end-to-end against a real Canon EOS R5** over Wi-Fi. It's still
> young (rough edges, R5 is the only tested body), so treat it as a capable alpha.

## What it does

Most "download RAW over Wi-Fi" apps for Canon require **CCAPI**, which means a
one-time activation per camera body through a Canon PC/Mac tool and a Canon
account. This app skips that entirely: it speaks plain **PTP/IP** (the protocol
`gphoto2`'s `ptpip:` transport uses) over the camera's Wi-Fi. Install the APK,
put the camera into its remote-control Wi-Fi mode, and go.

## Supported cameras

Verified on the **Canon EOS R5**. Any EOS body with Wi-Fi remote control and
standard PTP object transfer should work too — nothing in the app restricts it to
the R5. If you try another model, please open an issue with the result (works /
doesn't / partially), especially the [Milestone-0 checklist](docs/MILESTONE-0-PROTOKOLL.md)
steps.

## Features

- **Gallery, newest first** — lists everything on the card, loaded **progressively**
  (first thumbnails appear within ~1 s instead of waiting for all 1000+ objects)
- **Star-rating filter** — chips (All, ★1+ … ★5) driven by the rating you already
  set in-camera (read from the CR3 XMP metadata, no separate tagging step)
- **Full-screen zoom viewer** — tap an image to open the camera's embedded full-HD
  preview with pinch-zoom and double-tap zoom; correctly rotated to the shot's
  orientation
- **CR3 / JPEG / HEIF download** — full-resolution originals, saved to
  `Download/CanonRAW/`
- **Share sheet** — hand downloaded files to Lightroom, Luminar, or any app that
  accepts images via `ACTION_SEND_MULTIPLE`
- **Optional auto Wi-Fi join** — enter the camera's Wi-Fi SSID/password once
  (shown on the camera display); the connect screen then offers a one-tap
  **Auto-connect (Wi-Fi + camera)** that joins the camera's Wi-Fi itself
  (`WifiNetworkSpecifier` + `bindProcessToNetwork`) and connects over PTP/IP — no
  manual trip to Android's Wi-Fi settings
- **Experimental BLE-Wake (unverified)** — a debug-screen tool that looks for a
  camera already paired over Bluetooth and attempts to nudge its Wi-Fi on with a
  `MODE_WAKE` write. Requires a one-time Bluetooth pairing in the camera menu
  first; whether the wake actually turns Wi-Fi on is not confirmed. Best-effort.

## Connecting

Raw PTP/IP uses the camera's **computer / EOS Utility** remote-control mode, **not**
"Connect to smartphone" (which is Bluetooth-first and reserved for Canon's own app —
pointing PTP/IP at it yields `Err 11: Connection target not found`).

1. On the camera: **Menu → Wi-Fi/Bluetooth connection → Remote control (EOS Utility)
   → Add a device to connect to**
2. On the phone: join the Wi-Fi the camera opens (or, on a shared home network,
   just make sure both are on it)
3. Open the app and tap **Connect** — the camera IP defaults to `192.168.1.2` in
   the camera's own AP mode; on a home network enter the camera's IP on that network
4. The camera shows a one-time confirmation — **accept it within a few seconds**
   (short time window). The pairing identity (a persisted GUID) is remembered, so
   later connections from the same phone don't prompt again.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Core PTP/IP connection to the camera over Wi-Fi. |
| `CHANGE_NETWORK_STATE` | Auto Wi-Fi join via `WifiNetworkSpecifier`/`ConnectivityManager`. |
| `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+, runtime) | Experimental BLE-Wake: find and connect to a pre-paired camera over BLE. |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (≤ API 30) | Same BLE-Wake flow on Android 11 and below (install-time permissions there). |
| `ACCESS_FINE_LOCATION` (≤ API 30) | Legacy OS requirement for BLE scanning on Android 11 and below; dropped on API 31+ (`neverForLocation`). |

## Building

Requirements: JDK 17+, Android SDK, NDK 27.2 (pinned in `app/build.gradle.kts`),
CMake 3.22 (via Android Studio's SDK Manager or `sdkmanager`).

```bash
export JAVA_HOME=/path/to/jdk17+   # e.g. "$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # unit tests
```

## Architecture

The native protocol layer is [**libpict**](https://github.com/petabyt/libpict)
(Apache-2.0), vendored under `app/src/main/cpp/libpict/` and built as a CMake
subproject (sockets only, no USB backend). A thin JNI bridge
(`app/src/main/cpp/ptp_jni.c`) exposes connect/list/read/download to Kotlin;
almost no protocol logic lives in C. `PtpClient` wraps every call as a
mutex-guarded suspend function on `Dispatchers.IO`.

The connection is PTP/IP over TCP port 15740 (ISO 15740), the same handshake as
`gphoto2 --port ptpip:`. The camera identifies a paired client by a GUID, so the
app supplies and persists a per-install GUID (see `ptp_jni.c`; also note the one
documented upstream fix in [`PATCHES.md`](PATCHES.md) — libpict's `ptp_send_packet`
was missing the plain-`PTP_IP` case and aborted on the first `OpenSession`).

Listing loads object handles in one call, then pulls per-object info in batches so
the gallery fills progressively. Ratings, thumbnails and previews are read from the
CR3 container structure directly (`Cr3Container`): the `THMB` box for the thumbnail,
the `PRVW` uuid box for the full-HD preview, the XMP uuid box for `xmp:Rating`, and
the `CMT1` EXIF block for orientation — parsed by walking the ISO-BMFF boxes rather
than scanning for JPEG markers (Canon makernotes contain marker-like bytes that a
naive scan mistakes for image starts). Per-image metadata is disk-cached under a
versioned directory.

## Credits

- [petabyt/libpict](https://github.com/petabyt/libpict) — the PTP/PTP-IP library
- [libgphoto2](https://github.com/gphoto/libgphoto2) docs & issues — evidence EOS
  bodies answer standard PTP over `ptpip:`
- [julianschroden.com](https://julianschroden.com/) PTP/IP series — the clearest
  public write-up of Canon's PTP/IP handshake and pairing
- [lclevy/canon_cr3](https://github.com/lclevy/canon_cr3) — CR3/ISO-BMFF structure
- [gkoh/furble](https://github.com/gkoh/furble) — Canon BLE smart-pairing reference

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Vendors
[libpict](https://github.com/petabyt/libpict) (also Apache-2.0), license retained at
[`app/src/main/cpp/libpict/LICENSE`](app/src/main/cpp/libpict/LICENSE).
