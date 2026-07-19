# Canon Wireless RAW

Wireless CR3/RAW download from Canon EOS cameras straight to your Android phone —
over the camera's normal **"Connect to smartphone"** Wi-Fi. No CCAPI activation, no
Canon account, no cloud, no PC in the loop.

> ## ⚠️ Status: Alpha
> The full pipeline (connect → list → rating/preview → download) builds and is
> unit-tested, but it has **not yet been verified against a real camera**. The
> protocol assumptions (in particular: whether the EOS R5 accepts plain PTP/IP
> `GetObjectHandles`/`GetPartialObject` in smartphone-pairing mode) are the open
> risk called out in the design doc. See
> [`docs/MILESTONE-0-PROTOKOLL.md`](docs/MILESTONE-0-PROTOKOLL.md) for the
> hands-on test checklist and its result once run. Until that's checked off,
> treat this as a development build, not a finished product.

## What it does

Most "download RAW over Wi-Fi" apps for Canon require **CCAPI**, which means a
one-time activation per camera body through a Canon PC/Mac tool and a Canon
account. This app skips that entirely: it speaks plain **PTP/IP** (the protocol
gphoto2, gphoto2's `ptpip:` transport, and Canon's own Camera Connect app all use)
over the Wi-Fi access point the camera already opens for "Connect to smartphone" —
the same one the official Canon Camera Connect app uses. Install the APK, join the
camera's Wi-Fi, and go.

## Supported cameras

Developed for and targeting the **Canon EOS R5** — real-hardware verification is
pending (see the Milestone 0 protocol). Any EOS body with the same Wi-Fi
"Connect to smartphone" feature and standard PTP object-transfer support will
likely work too — nothing in the app artificially restricts it to the R5.
If you try it on another model, please open an issue with the result (works /
doesn't work / partially works), especially for the Milestone-0 checklist steps.

## Features

- **Gallery, newest first** — lists everything on the card, sorted by capture
  date descending
- **Star-rating filter** — filter chips (All, ★1+ … ★5) driven by the rating you
  already set in-camera (read straight from the CR3 XMP box / JPEG EXIF, no
  separate tagging step in the app)
- **CR3 / JPEG / HEIF download** — full-resolution originals, not just previews
- **Share sheet** — after download, hand files off to Lightroom, Luminar, or any
  other app that accepts images via `ACTION_SEND_MULTIPLE`

## Connecting

1. On the camera: **Menu → Wi-Fi settings → Connect to smartphone**
2. On the phone: join the Wi-Fi network the camera just opened
3. Open the app, confirm the IP (defaults to `192.168.1.2`, the camera's usual
   gateway address in AP mode), and tap **Verbinden**
4. The camera will show a one-time pairing confirmation dialog with a short
   time window — accept it there. Subsequent connections from the same phone
   are automatic (the pairing GUID is persisted).

A shared home Wi-Fi network also works: put the camera and phone on the same
network and enter the camera's IP on that network manually instead of the
default AP address.

## Building

Requirements:
- JDK 17+
- Android SDK, NDK 27.2 (pinned in `app/build.gradle.kts`), and CMake 3.22
  (installable via Android Studio's SDK Manager, or `sdkmanager` directly)

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"  # or any JDK 17+
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the unit test suite with:

```bash
./gradlew testDebugUnitTest
```

## Architecture

The native protocol layer is [**libpict**](https://github.com/petabyt/libpict)
(Apache-2.0), vendored under `app/src/main/cpp/libpict/` and built as a CMake
subproject (`-DPTP_USE_LIBUSB=OFF -DPTP_NO_USB=ON` — sockets only, no USB
backend). A single, thin JNI bridge (`app/src/main/cpp/ptp_jni.c`) exposes
connect/list/read/download/disconnect to Kotlin; almost no protocol logic lives
in C. On the Kotlin side, `PtpClient` wraps every call as a mutex-guarded
suspend function on `Dispatchers.IO`.

The connection itself is PTP/IP over TCP port 15740 (ISO 15740), the same
handshake used by `gphoto2 --port ptpip:`. Because the camera identifies a
paired client by a GUID, the app patches libpict's hardcoded placeholder GUID
and persists a per-install GUID so re-pairing isn't required on every launch.

Ratings and embedded previews are both read from a single `GetPartialObject`
call over the first ~128 KB of a file — no full download needed just to know a
picture's star rating or see a thumbnail. For CR3 (an ISO-BMFF/MP4-like
container), the rating comes from scanning for the XMP `uuid` box and parsing
`xmp:Rating` out of it (the box offset varies by camera model, hence a scan
rather than a fixed offset); for JPEG/HEIF it's the standard EXIF rating tag.
Embedded preview JPEGs are found via a SOI/EOI marker scan over the same
buffer.

## Credits

- [petabyt/libpict](https://github.com/petabyt/libpict) — the PTP/PTP-IP
  library this app vendors and builds against
- [libgphoto2](https://github.com/gphoto/libgphoto2) documentation and issue
  history — evidence that EOS bodies answer standard PTP over `ptpip:`
- [julianschroden.com](https://julianschroden.com/) PTP/IP blog series — the
  clearest public write-up of Canon's PTP/IP handshake and pairing behavior
- [lclevy/canon_cr3](https://github.com/lclevy/canon_cr3) — CR3/ISO-BMFF
  structure and XMP box location reference
- [Akopmm/canon-mtp-direct](https://github.com/Akopmm/canon-mtp-direct) — the
  embedded-thumbnail-via-marker-scan technique

## License

This project is licensed under the **Apache License 2.0** — see
[`LICENSE`](LICENSE).

It vendors [libpict](https://github.com/petabyt/libpict), also Apache-2.0; its
license is retained unmodified at
[`app/src/main/cpp/libpict/LICENSE`](app/src/main/cpp/libpict/LICENSE).
