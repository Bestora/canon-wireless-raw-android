# Canon Wireless RAW für Android — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-App, die ohne Kamera-Freischaltung per WLAN (PTP/IP, Port 15740) Bilder einer Canon EOS R5 auflistet, nach Kamera-Rating filtert, CR3 herunterlädt und per Share-Sheet weitergibt.

**Architecture:** Kotlin/Compose-App mit einer schmalen JNI-Bridge auf die vendored C-Bibliothek libpict (PTP/IP-Transport). Alle Logik oberhalb der rohen PTP-Operationen (Chunked Download, CR3-Parsing, Caching, Sortierung) lebt in Kotlin. Milestone 0 = Debug-Screen, der jeden Protokollschritt einzeln gegen die echte Kamera verifiziert.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), AGP 8.7.3, Gradle 8.10.2, minSdk 29, compileSdk 35, NDK 27.x, CMake 3.22.1, libpict (Apache-2.0, vendored).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-19-canon-wireless-raw-android-design.md` — gilt vollständig
- Package: `io.github.canonwirelessraw`, App-Label: „Canon Wireless RAW"
- minSdk 29, compileSdk 35, nur `arm64-v8a` und `x86_64` ABIs
- KEINE zusätzlichen Dependencies ohne Not: kein Hilt, kein Coil, keine Navigation-Lib, kein DataStore (SharedPreferences reicht), kein Retrofit. Compose + Coroutines + androidx-Basics.
- libpict wird VENDORED (Quellcode-Kopie unter `app/src/main/cpp/libpict/`), kein Git-Submodule. LICENSE-Datei von libpict bleibt erhalten.
- libpict-Quelltext wird NICHT modifiziert — Canon-/GUID-Sonderlocken leben in unserer eigenen `ptp_jni.c`
- Alle PTP-Aufrufe von Kotlin aus laufen über `Dispatchers.IO`; die JNI-Schicht enthält keine Businesslogik
- Jeder Task endet mit grünem Build (`./gradlew assembleDebug` bzw. `testDebugUnitTest`) und einem Commit
- Build-Umgebung auf diesem Mac: `export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"` vor jedem Gradle-Aufruf; `local.properties` mit `sdk.dir=/Users/dennis.rehbehn/Library/Android/sdk`
- Referenzquellen für Implementierer: libpict-Quelldateien liegen vendored im Repo; zusätzliche Analysen unter `/private/tmp/claude-502/-Users-dennis-rehbehn-Projekte-canon-wireless-raw-android/0c81d446-e183-469c-a7ff-9121532e4c48/scratchpad/lp/`

## Phasen-Übersicht

| Phase | Tasks | Parallelisierbar |
|-------|-------|------------------|
| A Toolchain | Task 0 | – (läuft vorab) |
| B Fundament | Task 1 → Task 2 | sequenziell |
| C Kern | Task 3→4 (Kette), Task 5, Task 6, Task 7 | 3/4-Kette, 5, 6, 7 parallel |
| D Integration | Task 8 (braucht 4,5,6), Task 9 (braucht 8) | sequenziell |
| E UI | Task 10 (braucht 8,9), Task 11 (braucht 8), Task 12 (braucht 8,9,10,11) | 10 und 11 parallel |
| F Release | Task 13 (braucht alles) | – |

---

### Task 0: Toolchain (Android SDK/NDK/CMake)

**Files:** keine im Repo (nur `local.properties`, gitignored)

- [ ] **Step 1:** Warten bis `brew install openjdk@21` + `--cask android-commandlinetools` fertig ist; zusätzlich `brew install gradle` (nur für `gradle wrapper`-Bootstrap).
- [ ] **Step 2:** SDK-Pakete installieren:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
SDKM="$(brew --prefix)/share/android-commandlinetools/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKM" --sdk_root="$HOME/Library/Android/sdk" --licenses
"$SDKM" --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
```

- [ ] **Step 3:** Verifizieren: `ls ~/Library/Android/sdk/ndk` zeigt `27.2.12479018`, `ls ~/Library/Android/sdk/platforms` zeigt `android-35`.

---

### Task 1: Gradle-Projektgerüst mit leerer Compose-App

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `local.properties` (gitignored), `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/github/canonwirelessraw/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Wrapper: `gradlew`, `gradle/wrapper/*` (via `gradle wrapper --gradle-version 8.10.2`)

**Interfaces:**
- Produces: baubares App-Modul; `MainActivity` mit `setContent { App() }`; Composable `App()` in `MainActivity.kt` (wird in Task 10/12 ersetzt)

- [ ] **Step 1:** `.gitignore`:

```
.gradle/
build/
local.properties
.DS_Store
*.iml
.idea/
```

- [ ] **Step 2:** `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "canon-wireless-raw"
include(":app")
```

- [ ] **Step 3:** `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 4:** Root-`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 5:** `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.canonwirelessraw"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.canonwirelessraw"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // externalNativeBuild kommt in Task 2
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 6:** `AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.CanonWirelessRaw"
        android:usesCleartextTraffic="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`strings.xml`: `<string name="app_name">Canon Wireless RAW</string>`.
`themes.xml`: leeres Material-Theme `Theme.CanonWirelessRaw` mit Parent `android:Theme.Material.Light.NoActionBar`.

- [ ] **Step 7:** `MainActivity.kt`:

```kotlin
package io.github.canonwirelessraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme { Surface { Text("Canon Wireless RAW") } }
}
```

- [ ] **Step 8:** `local.properties` mit `sdk.dir=/Users/dennis.rehbehn/Library/Android/sdk`; Wrapper erzeugen: `gradle wrapper --gradle-version 8.10.2`.
- [ ] **Step 9:** Build: `JAVA_HOME=… ./gradlew assembleDebug` → `BUILD SUCCESSFUL`, APK unter `app/build/outputs/apk/debug/`.
- [ ] **Step 10:** Commit: `chore: bootstrap Android Compose project`.

---

### Task 2: libpict vendoren + CMake + JNI-Smoke-Test

**Files:**
- Create: `app/src/main/cpp/libpict/` (Kopie von github.com/petabyt/libpict, master: `src/`, `CMakeLists.txt`, `LICENSE`, README), `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/ptp_jni.c`, `app/src/main/java/io/github/canonwirelessraw/ptp/PtpNative.kt`
- Modify: `app/build.gradle.kts` (externalNativeBuild)

**Interfaces:**
- Produces: `object PtpNative` mit `init { System.loadLibrary("ptpjni") }` und `external fun create(): Long`, `external fun destroy(rt: Long)` — Task 3 erweitert dieselben Dateien.

- [ ] **Step 1:** libpict clonen und vendoren (nur Nötiges):

```bash
git clone --depth 1 https://github.com/petabyt/libpict /tmp/libpict-src
mkdir -p app/src/main/cpp/libpict
cp -R /tmp/libpict-src/src /tmp/libpict-src/CMakeLists.txt /tmp/libpict-src/LICENSE /tmp/libpict-src/README.md app/src/main/cpp/libpict/
```

Prüfen, ob libpicts CMakeLists weitere Dateien referenziert (z. B. `cmake/`-Ordner) — falls ja, mitkopieren. `examples/` NICHT kopieren, aber `examples/wifi.c` und `examples/storage.c` als Referenz nach `app/src/main/cpp/libpict/examples/` legen (kompilieren nicht mit).

- [ ] **Step 2:** `app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22)
project(canonraw C)

set(PTP_USE_LIBUSB OFF CACHE BOOL "" FORCE)
set(PTP_NO_USB ON CACHE BOOL "" FORCE)
add_subdirectory(libpict)

add_library(ptpjni SHARED ptp_jni.c)
target_include_directories(ptpjni PRIVATE libpict/src)
target_link_libraries(ptpjni libpict log)
```

Falls libpicts CMake auf Android doch libusb sucht oder Targets anders heißen: die vendored `libpict/CMakeLists.txt` LESEN und die Flags/Targetnamen anpassen (Target heißt laut Analyse `libpict`, Output `pict`).

- [ ] **Step 3:** `ptp_jni.c` (Smoke-Version):

```c
#include <jni.h>
#include <libpict.h>

JNIEXPORT jlong JNICALL
Java_io_github_canonwirelessraw_ptp_PtpNative_create(JNIEnv *env, jobject thiz) {
    return (jlong)(intptr_t)ptp_new(PTP_IP);
}

JNIEXPORT void JNICALL
Java_io_github_canonwirelessraw_ptp_PtpNative_destroy(JNIEnv *env, jobject thiz, jlong rt) {
    ptp_close((struct PtpRuntime *)(intptr_t)rt);
}
```

(Exakten Free-Funktionsnamen in `libpict/src/libpict.h` nachschlagen — `ptp_close` vorhanden? sonst dokumentierten Cleanup nutzen.)

- [ ] **Step 4:** `PtpNative.kt`:

```kotlin
package io.github.canonwirelessraw.ptp

object PtpNative {
    init { System.loadLibrary("ptpjni") }
    external fun create(): Long
    external fun destroy(rt: Long)
}
```

- [ ] **Step 5:** In `app/build.gradle.kts` innerhalb `android {}`:

```kotlin
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

- [ ] **Step 6:** Build: `./gradlew assembleDebug` → SUCCESS; prüfen: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libptpjni` zeigt `lib/arm64-v8a/libptpjni.so` und `lib/x86_64/libptpjni.so`.
- [ ] **Step 7:** Commit: `feat: vendor libpict and wire NDK build with JNI smoke functions`.

---

### Task 3: Vollständige JNI-Bridge (Handshake mit eigener GUID, PTP-Operationen)

**Files:**
- Modify: `app/src/main/cpp/ptp_jni.c`, `app/src/main/java/io/github/canonwirelessraw/ptp/PtpNative.kt`

**Interfaces:**
- Produces (Kotlin, alle in `object PtpNative`):

```kotlin
external fun create(): Long
external fun destroy(rt: Long)
/** 0 = OK, sonst negativer Fehlercode. Führt aus: ptpip_connect + eigener
 *  Init-Command-Request mit guid (16 Bytes) + name, ptpip_connect_events + init_events. */
external fun connect(rt: Long, ip: String, port: Int, guid: ByteArray, name: String): Int
external fun openSession(rt: Long): Int
external fun eosSetRemoteMode(rt: Long, mode: Int): Int
external fun eosSetEventMode(rt: Long, mode: Int): Int
external fun getStorageIds(rt: Long): IntArray?          // null bei Fehler
external fun getObjectHandles(rt: Long, storageId: Int, format: Int, parent: Int): IntArray?
/** [0]=filename, [1]=compressedSize (dezimal), [2]=dateCreated (PTP-String),
 *  [3]=objFormat (dezimal), [4]=parentObj (dezimal), [5]=assocType (dezimal) */
external fun getObjectInfo(rt: Long, handle: Int): Array<String>?
external fun getPartialObject(rt: Long, handle: Int, offset: Long, maxLen: Int): ByteArray?
external fun cancel(rt: Long)        // setzt io_kill_switch
external fun disconnect(rt: Long)    // close session + sockets
external fun lastError(rt: Long): Int
```

- [ ] **Step 1:** In `ptp_jni.c` eine Funktion `static int init_command_request_guid(struct PtpRuntime *r, const uint8_t *guid, const char *name)` schreiben. Vorlage: `libpict/src/operations.c` → `ptpip_init_command_request` (vendored, lesbar). Identischer Packet-Aufbau (PtpIpHeader, Typ 0x01, danach 16 Byte GUID statt der hartkodierten `0xffffffff`-Werte, dann `ptp_write_unicode_string(name)`, dann Version 1.0) und identisches ACK-Handling. libpict-Code NICHT ändern — Funktion lebt komplett in `ptp_jni.c` und nutzt nur öffentliche libpict-Symbole (`ptpip_cmd_write`, `ptpip_cmd_read`, Strukturen aus den Headern). Falls benötigte Symbole `static` sind: minimal nötige Hilfslogik (z. B. Unicode-Write) in `ptp_jni.c` duplizieren, mit Kommentar `/* kopiert aus libpict operations.c, dort static */`.
- [ ] **Step 2:** `connect()` implementieren: `ptpip_connect(r, ip, port, 0)` → `init_command_request_guid(...)` → `ptpip_connect_events(r, ip, port)` → `ptpip_init_events(r)`. Jeder Schritt mit eigenem Fehlercode (-1, -2, -3, -4), per `__android_log_print` loggen.
- [ ] **Step 3:** Restliche Wrapper 1:1 auf libpict-Funktionen mappen (`ptp_open_session`, `ptp_eos_set_remote_mode`, `ptp_eos_set_event_mode`, `ptp_get_storage_ids`, `ptp_get_object_handles`, `ptp_get_object_info`, `ptp_get_partial_object` + `ptp_get_payload`/`ptp_get_payload_length`). `cancel()` setzt `r->io_kill_switch = 1` (Feldname in `libpict.h` verifizieren). Rückgabecodes: libpict-Returncode durchreichen; bei Payload-Funktionen `NULL` bei Fehler.
- [ ] **Step 4:** `getPartialObject`: `offset` als `jlong` annehmen, aber libpict nimmt `unsigned int` — bei > UINT32_MAX `NULL` returnen (CR3s sind < 4 GB, ObjectInfo-Size ist eh 32-bit).
- [ ] **Step 5:** Build `./gradlew assembleDebug` → SUCCESS (Logik-Verifikation erfolgt am Gerät via Task 9).
- [ ] **Step 6:** Commit: `feat: full JNI bridge with custom pairing GUID handshake`.

---

### Task 4: PtpClient (Kotlin-Wrapper) + GUID-Persistenz

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ptp/PtpClient.kt`, `app/src/main/java/io/github/canonwirelessraw/ptp/PtpModels.kt`, `app/src/main/java/io/github/canonwirelessraw/data/Prefs.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/ptp/PtpDateParserTest.kt`

**Interfaces:**
- Consumes: `PtpNative` (Task 3)
- Produces:

```kotlin
// PtpModels.kt
enum class FileKind { CR3, JPEG, HEIF, OTHER }
data class CameraObject(
    val handle: Int, val name: String, val size: Long,
    val takenAtMillis: Long, val kind: FileKind, val format: Int,
)
fun parsePtpDate(s: String): Long   // "yyyyMMdd'T'HHmmss" (+ optionale .Z-Suffixe) -> epoch millis, 0 bei Parse-Fehler
fun kindFromName(name: String): FileKind  // Endung case-insensitive: .cr3 / .jpg .jpeg / .hif .heif

// PtpClient.kt — alle Methoden suspend, intern withContext(Dispatchers.IO) + Mutex.
// Deklariert AUCH das Interface PtpPort (exakte Signaturen siehe Task 8) und
// implementiert es: class PtpClient : PtpPort
class PtpClient {
    val connected: StateFlow<Boolean>
    suspend fun connect(ip: String, port: Int = 15740, name: String = "Canon Wireless RAW", guid: ByteArray, eosMode: Boolean = true)  // wirft PtpException(step, code)
    suspend fun listObjects(): List<CameraObject>     // alle Storages, alle Handles (format=0, parent=0), ObjectInfo pro Handle; Ordner (assocType!=0 bzw. format 0x3001) übersprungen; bei leerem Flat-Listing Fallback: rekursiv über parent-Ordner
    suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray
    suspend fun downloadTo(obj: CameraObject, sink: OutputStream, chunk: Int = 1 shl 20, onProgress: (Long) -> Unit)
    fun cancelIo()
    suspend fun disconnect()
}
class PtpException(val step: String, val code: Int) : Exception("$step failed: $code")

// Prefs.kt
class Prefs(context: Context) {
    fun pairingGuid(): ByteArray  // 16 Bytes; beim ersten Aufruf UUID.randomUUID() erzeugen und in SharedPreferences (hex) persistieren
    var lastIp: String?
}
```

- [ ] **Step 1 (TDD):** `PtpDateParserTest`: `"20260719T143502" → korrektes epoch millis (lokale TZ)`, ungültiger String → 0, `kindFromName("IMG_0001.CR3") == CR3`, `"x.JPG" == JPEG`, `"mov.MP4" == OTHER`. Test läuft rot (Funktionen fehlen).
- [ ] **Step 2:** `PtpModels.kt` implementieren (`SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)`, Suffix ab `.`/`Z` abschneiden). Test grün: `./gradlew testDebugUnitTest`.
- [ ] **Step 3:** `PtpClient` implementieren. `downloadTo`: Schleife `readPartial(handle, offset, chunk)` bis `offset >= obj.size` oder Kurz-Read; nach jedem Chunk `sink.write` + `onProgress(offset)`. Abbruch (cancel) → `PtpException("download", -99)`, Aufrufer räumt Datei weg. Mutex verhindert parallele PTP-Transaktionen.
- [ ] **Step 4:** `Prefs` implementieren (SharedPreferences `prefs`, Keys `pairing_guid_hex`, `last_ip`).
- [ ] **Step 5:** Build + Tests grün, Commit: `feat: PtpClient coroutine wrapper, models, pairing GUID persistence`.

---

### Task 5: CR3/JPEG-Metadaten-Parser (Rating + Preview) — reine Kotlin-Logik, TDD

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/cr3/ImageHeaderParser.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/cr3/ImageHeaderParserTest.kt`

**Interfaces:**
- Consumes: nichts (pure Funktionen über `ByteArray`)
- Produces:

```kotlin
object ImageHeaderParser {
    /** CR3 (ISO-BMFF): Top-Level-Box-Scan nach uuid be7acfcb-97a9-42e8-9c71-999491e3afac,
     *  darin xmp:Rating als Attribut ODER Element. null wenn nicht gefunden/kein CR3. */
    fun cr3Rating(header: ByteArray): Int?
    /** JPEG: EXIF IFD0 Tag 0x4746 (Rating, SHORT). null wenn nicht vorhanden. */
    fun jpegRating(header: ByteArray): Int?
    /** Erstes eingebettetes JPEG (SOI FFD8FF … EOI FFD9) ab byteOffset minStart. */
    fun embeddedJpeg(header: ByteArray, minStart: Int = 0): ByteArray?
    /** Dispatch nach FileKind: CR3→cr3Rating, JPEG→jpegRating,
     *  HEIF→cr3Rating (auch ISO-BMFF; Box-Scan als Best-Effort), OTHER→null. */
    fun rating(kind: FileKind, header: ByteArray): Int?
}
```

- [ ] **Step 1 (TDD, alle Tests zuerst, rot):** Testhelfer im Test schreiben, der synthetische Strukturen baut:
  - `bmffBox(type: String, payload: ByteArray): ByteArray` — `[size:Int32BE][type:4ASCII][payload]`
  - `xmpUuidBox(xml: String)` — uuid-Box: Payload = 16-Byte-XMP-GUID (`BE7ACFCB97A942E89C71999491E3AFAC`) + XML-Bytes
  - Tests:
    - ftyp + xmpUuidBox mit `xmp:Rating="3"` (Attribut-Form) → 3
    - Element-Form `<xmp:Rating>5</xmp:Rating>` → 5
    - Rating 0 → 0 (nicht null!); uuid-Box mit fremder GUID → null; kein BMFF (JPEG-Bytes) → null
    - 64-bit-Größe: Box mit size==1 + largesize korrekt übersprungen
    - abgeschnittener Header (Box ragt über Array-Ende) → kein Crash, null
    - `embeddedJpeg`: Puffer `garbage + FFD8FFE1…FFD9 + garbage` → exakt der JPEG-Teil; ohne EOI → null
    - `jpegRating`: synthetisches Minimal-EXIF (siehe Step 3) mit Tag 0x4746=2 → 2, big-endian (MM) Variante → 2, ohne Tag → null
- [ ] **Step 2:** CR3-Teil implementieren: Sequentieller Top-Level-Scan (`offset += boxSize`; size==1 → 8-Byte-largesize lesen; size<8 bzw. Überlauf → Abbruch). In der XMP-uuid-Box: Payload als ISO-8859-1-String, `Regex("""xmp:Rating\s*=\s*["'](-?\d+)["']""")` und `Regex("""<xmp:Rating>\s*(-?\d+)""")`. Werte auf -1..5 klemmen, -1 (rejected) als 0 behandeln.
- [ ] **Step 3:** JPEG-EXIF-Teil: Marker-Scan ab SOI: Segmente `FF xx [len:2BE]`; bei APP1 (`FFE1`) mit Prefix `Exif\0\0`: TIFF-Header (II/MM, Magic 42, IFD0-Offset), IFD0-Entries (Count:2, je 12 Bytes: tag:2, type:2, count:4, value:4), Tag `0x4746` Type SHORT → Wert. Alles mit expliziten Bounds-Checks.
- [ ] **Step 4:** `embeddedJpeg`: `indexOf(FFD8FF)` ab minStart, dann ab dort `FFD9` suchen (inklusive), Kopie zurück.
- [ ] **Step 5:** `./gradlew testDebugUnitTest` → alle grün. Commit: `feat: CR3/JPEG rating and preview extraction with unit tests`.

---

### Task 6: MetaCache (Disk-Cache für Rating + Thumbnail)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/data/MetaCache.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/data/MetaCacheTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class ImageMeta(val rating: Int?, val thumbFile: File?)  // thumbFile: JPEG auf Disk oder null
class MetaCache(private val dir: File) {   // dir = context.cacheDir/meta — Konstruktor nimmt File, damit JVM-testbar
    fun get(handle: Int, size: Long): ImageMeta?          // null = nicht im Cache
    fun put(handle: Int, size: Long, rating: Int?, thumb: ByteArray?): ImageMeta
    fun clear()
}
```

Format: pro Bild `"$handle-$size.meta"` (eine Zeile: Rating-Zahl oder `-`) + optional `"$handle-$size.jpg"`. Kein JSON, keine Lib.

- [ ] **Step 1 (TDD):** Tests mit `@get:Rule TemporaryFolder` (JUnit): put→get Roundtrip (Rating 4 + Thumb-Bytes), Rating null → `-` → get liefert null-Rating, get für unbekanntes Handle → null, clear leert Verzeichnis.
- [ ] **Step 2:** Implementieren (max. ~50 Zeilen), Tests grün.
- [ ] **Step 3:** Commit: `feat: disk cache for per-image rating and thumbnail`.

---

### Task 7: MediaStore-Downloader + Share-Helfer

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/data/Saver.kt`

**Interfaces:**
- Consumes: nichts von anderen Tasks (nur Android-APIs); wird von Task 8/12 benutzt
- Produces:

```kotlin
object Saver {
    /** Legt Pictures/CanonRAW/<name> via MediaStore an (IS_PENDING-Muster).
     *  Ruft body mit dem OutputStream auf; bei Exception wird der Eintrag gelöscht.
     *  Rückgabe: content-Uri der fertigen Datei. */
    fun saveToPictures(context: Context, name: String, mime: String, body: (OutputStream) -> Unit): Uri
    fun mimeFor(kind: FileKind): String  // CR3→"image/x-canon-cr3", JPEG→"image/jpeg", HEIF→"image/heif", OTHER→"application/octet-stream"
    /** ACTION_SEND_MULTIPLE mit FLAG_GRANT_READ_URI_PERMISSION, als Chooser. */
    fun shareIntent(uris: List<Uri>): Intent
}
```

- [ ] **Step 1:** Implementieren. `saveToPictures`: `MediaStore.Images.Media` insert mit `RELATIVE_PATH="Pictures/CanonRAW"`, `IS_PENDING=1`; Stream öffnen, `body` ausführen, `IS_PENDING=0`; catch → `contentResolver.delete(uri)` + rethrow.
- [ ] **Step 2:** Build grün (`assembleDebug`). Logik ist dünner Android-API-Glue — Verifikation am Gerät in Task 9/12. Commit: `feat: MediaStore saver and share intent helper`.

---

### Task 8: CameraRepository (Verbinden, Listing, Meta, Download — der Kern)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/data/CameraRepository.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/data/CameraRepositoryTest.kt`

**Interfaces:**
- Consumes: `PtpClient` (Task 4), `ImageHeaderParser` (Task 5), `MetaCache` (Task 6), `Saver` (Task 7), `Prefs` (Task 4)
- Produces:

```kotlin
data class GalleryItem(val obj: CameraObject, val rating: Int?, val thumbFile: File?) // rating==null → noch nicht gescannt
class CameraRepository(
    private val ptp: PtpClient, private val cache: MetaCache, private val prefs: Prefs,
) {
    val items: StateFlow<List<GalleryItem>>          // sortiert takenAtMillis DESC
    suspend fun connect(ip: String)                  // nutzt prefs.pairingGuid(), merkt lastIp
    suspend fun refreshList()                        // listObjects → nur CR3/JPEG/HEIF → sortieren → items; Cache-Treffer sofort einfüllen
    suspend fun ensureMeta(handle: Int)              // falls im items-Eintrag rating==null&&thumb==null: readPartial(handle, 0, 128*1024) → Parser → cache.put → items aktualisieren
    suspend fun scanAllMeta(onProgress: (done: Int, total: Int) -> Unit)  // ensureMeta für alle ohne Meta, sequentiell
    suspend fun download(context: Context, item: GalleryItem, onProgress: (Long) -> Unit): Uri  // Saver.saveToPictures + ptp.downloadTo
    fun cancel(); suspend fun disconnect()
}
```

Konstruktor von `PtpClient` für Tests überschreibbar machen: `PtpClient` bekommt `open`-Members oder — einfacher — `CameraRepository` nimmt ein Interface:

```kotlin
interface PtpPort {  // von PtpClient implementiert
    suspend fun connect(ip: String, port: Int, name: String, guid: ByteArray, eosMode: Boolean)
    suspend fun listObjects(): List<CameraObject>
    suspend fun readPartial(handle: Int, offset: Long, len: Int): ByteArray
    suspend fun downloadTo(obj: CameraObject, sink: OutputStream, chunk: Int, onProgress: (Long) -> Unit)
    fun cancelIo(); suspend fun disconnect()
}
```

(Task 4s `PtpClient` implementiert `PtpPort` — Task-4-Implementierer deklariert das Interface gleich mit, Signaturen exakt wie oben.)

- [ ] **Step 1 (TDD):** `CameraRepositoryTest` mit Fake-`PtpPort` (in-memory, 3 Objekte: CR3 mit Rating-3-Header aus den Task-5-Testhelfern, JPEG ohne Rating, MP4): `refreshList` filtert MP4 raus und sortiert neueste zuerst; `ensureMeta` liefert Rating 3 + Thumb und cached (zweiter Aufruf ruft Fake nicht erneut — Zähler); `scanAllMeta` meldet (1,2),(2,2). Tests rot.
- [ ] **Step 2:** Implementieren, Tests grün (`testDebugUnitTest`).
- [ ] **Step 3:** Commit: `feat: camera repository with lazy meta scan and rating cache`.

---

### Task 9: Debug-Screen = Milestone 0 (Spike-Verifikation an echter R5)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ui/DebugScreen.kt`, `app/src/main/java/io/github/canonwirelessraw/AppContainer.kt`
- Modify: `MainActivity.kt` (Debug-Screen als Start, bis Task 12 die Navigation setzt)

**Interfaces:**
- Consumes: `PtpClient`, `CameraRepository`, `ImageHeaderParser`
- Produces: `AppContainer` (Singleton: `prefs`, `ptp`, `cache`, `repo` — lazy, `AppContainer.init(context)` in `MainActivity.onCreate`); `@Composable fun DebugScreen(container: AppContainer)`

- [ ] **Step 1:** `AppContainer.kt`: Objekt mit `lateinit`-Feldern, `init(context)` erzeugt `Prefs`, `PtpClient`, `MetaCache(File(context.cacheDir, "meta"))`, `CameraRepository`.
- [ ] **Step 2:** `DebugScreen`: IP-Textfeld (Default `prefs.lastIp ?: "192.168.1.2"`), Buttons untereinander, darunter scrollbares Log (`LazyColumn` mit monospaced Text-Zeilen, State `SnapshotStateList<String>`):
  1. „1. Connect (Handshake+Pairing)" → `ptp.connect(ip, eosMode=false)` + Erfolg/Fehler loggen
  2. „2. EOS Remote/Event Mode" → `eosSetRemoteMode(1)` + `eosSetEventMode(1)` einzeln loggen (via PtpClient-Durchreichen oder eigenem Debug-Pfad in PtpClient: `suspend fun eosMode()`)
  3. „3. Liste laden" → `listObjects()`, loggt Anzahl + erste 5 Namen/Daten
  4. „4. 128 KB Partial + Rating" → erstes CR3 aus Liste, `readPartial(h,0,131072)`, `cr3Rating` + `embeddedJpeg`-Größe loggen
  5. „5. Voll-Download erste CR3" → `download(...)`, Fortschritt alle 10 % loggen, Uri loggen
  6. „Disconnect"
  Jeder Button-Handler: `viewModelScope`-frei, einfacher `rememberCoroutineScope().launch { runCatching{...}.onFailure{ log("FEHLER: $it") } }`.
- [ ] **Step 3:** `MainActivity`: `App()` rendert vorerst `DebugScreen(AppContainer)`.
- [ ] **Step 4:** Build grün, Commit: `feat: milestone-0 debug screen for on-camera protocol verification`.
- [ ] **Step 5:** **[NUTZER + KAMERA ERFORDERLICH]** Testprotokoll (auch in README, Task 13): R5: WLAN-Funktion „Mit Smartphone verbinden" → APK installieren → S25 mit Kamera-WLAN verbinden → Buttons 1–6 der Reihe nach; Erwartung je Schritt dokumentiert. Ergebnis entscheidet über Fallback (EOS-Vendor-Opcodes, siehe Spec).

---

### Task 10: Connect-Screen

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ui/ConnectScreen.kt`

**Interfaces:**
- Consumes: `CameraRepository.connect`, `Prefs.lastIp`
- Produces: `@Composable fun ConnectScreen(container: AppContainer, onConnected: () -> Unit, onDebug: () -> Unit)`

- [ ] **Step 1:** UI: Titel, dreizeilige Anleitung (Kamera: Menü → WLAN → „Mit Smartphone verbinden"; S25 mit Kamera-WLAN verbinden; hier IP bestätigen), `OutlinedTextField` IP (Default `prefs.lastIp ?: "192.168.1.2"`), Button „Verbinden" (disabled während Connect, `CircularProgressIndicator`), Fehlertext darunter — bei Timeout Hinweis: „Bestätige die Verbindung an der Kamera und versuche es sofort erneut (Zeitfenster nur wenige Sekunden)." Unauffälliger TextButton „Debug" → `onDebug()`.
- [ ] **Step 2:** Erfolg → `onConnected()`. Build grün, Commit: `feat: connect screen`.

---

### Task 11: Gallery-Screen (Grid, Rating-Filter, Auswahl)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ui/GalleryScreen.kt`

**Interfaces:**
- Consumes: `CameraRepository` (`items`, `refreshList`, `ensureMeta`, `scanAllMeta`)
- Produces: `@Composable fun GalleryScreen(container: AppContainer, onDownload: (List<GalleryItem>) -> Unit)`

- [ ] **Step 1:** `LazyVerticalGrid(GridCells.Adaptive(96.dp))` über `items`-StateFlow (`collectAsState`). Pro Zelle: Thumb (BitmapFactory.decodeFile auf `Dispatchers.IO` via `produceState`, sonst graue Fläche mit Dateityp-Text), Sterne-Badge falls rating>0, CR3/JPG-Badge, Auswahl-Overlay bei Selektion (Set<Int> im State). `LaunchedEffect(item.handle)`: `repo.ensureMeta(handle)` (lazy Meta für sichtbare Items — LazyGrid composed nur Sichtbares).
- [ ] **Step 2:** Top-Bar: FilterChips „Alle, ★1+, ★2+, ★3+, ★4+, ★5" (Filter: `rating != null && rating >= n`; Items mit `rating == null` erscheinen nur bei „Alle"), Button „Ratings scannen" → `scanAllMeta` mit LinearProgress + Zähler, Refresh-Icon → `refreshList()`. Bottom-Bar erscheint bei Auswahl: „N herunterladen" → `onDownload(selected)`.
- [ ] **Step 3:** Build grün, Commit: `feat: gallery grid with rating filter and multi-select`.

---

### Task 12: Download-Flow + Navigation zusammenstecken

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ui/DownloadScreen.kt`
- Modify: `MainActivity.kt`

**Interfaces:**
- Consumes: alles Vorherige
- Produces: `@Composable fun DownloadScreen(container: AppContainer, items: List<GalleryItem>, onDone: () -> Unit)`; `App()` mit State-Navigation

- [ ] **Step 1:** `DownloadScreen`: startet in `LaunchedEffect` sequentiellen Download aller `items` (`repo.download`), UI: Liste mit je Name + LinearProgressIndicator (bytes/size) + Häkchen, Abbrechen-Button (`repo.cancel()`, halbe Datei wird von `Saver` weggeräumt), nach Abschluss zwei Buttons: „Teilen…" (`Saver.shareIntent(uris)` via `context.startActivity`) und „Fertig" → `onDone()`.
- [ ] **Step 2:** `MainActivity.App()`: `var screen by remember { mutableStateOf<Screen>(Screen.Connect) }` mit `sealed interface Screen { Connect; Gallery; Download(items); Debug }` — Connect→Gallery bei Erfolg, Gallery→Download bei Auswahl, Download→Gallery bei Fertig, Connect→Debug über TextButton. System-Back: Gallery→Connect (disconnect), Download nur nach Abbruch/Fertig.
- [ ] **Step 3:** Voller Durchstich baubar: `./gradlew assembleDebug testDebugUnitTest` grün. Commit: `feat: download flow with share sheet and screen navigation`.

---

### Task 13: README, Lizenz, Release-Build

**Files:**
- Create: `README.md`, `LICENSE` (Apache-2.0, Copyright-Zeile Projektname), `docs/MILESTONE-0-PROTOKOLL.md`

**Interfaces:** keine

- [ ] **Step 1:** `README.md` (englisch, GitHub-Zielgruppe): Was die App kann (RAW über WLAN ohne CCAPI-Aktivierung, Rating-Filter, Share zu Lightroom/…), unterstützte Kameras (R5 getestet, andere EOS wahrscheinlich), Verbindungsanleitung mit dem Kamera-Menüpfad, Build-Anleitung (`./gradlew assembleDebug`, benötigt SDK+NDK), Architektur-Kurzabsatz (libpict vendored, JNI, PTP/IP 15740), Credits (libpict, libgphoto2-Doku, julianschroden-Blog, lclevy/canon_cr3), Lizenzhinweis.
- [ ] **Step 2:** `docs/MILESTONE-0-PROTOKOLL.md`: die 6 Debug-Schritte aus Task 9 als Checkliste mit Erwartung + Feld für Ergebnis, plus Fallback-Hinweis (EOS-Vendor-Opcodes) bei Fehlschlag von Schritt 3/4.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` final grün; APK-Pfad ins README. Commit: `docs: README, license, milestone-0 test protocol`.

---

## Verifikation, die nur der Nutzer machen kann

Tasks 0–13 sind ohne Kamera baubar und (wo Logik steckt) unit-getestet. Die Protokoll-Schritte gegen die echte R5 (Task 9 Step 5 / MILESTONE-0-PROTOKOLL) erfordern Kamera + S25 in Reichweite des Nutzers. Erst deren Ergebnis entscheidet, ob der Standard-PTP-Pfad reicht oder der EOS-Vendor-Fallback (eigener Folgeplan) nötig wird.
