# BLE-Wake + Auto-WiFi-Join Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Verbindungsablauf der Canon-Wireless-RAW-App komfortabler machen: die App verbindet das Telefon automatisch mit dem Kamera-WLAN (Teil A, garantiert) und versucht, die Kamera per BLE aufzuwecken (Teil B, Best-Effort).

**Architecture:** Zwei neue, voneinander unabhängige Kotlin-Komponenten oberhalb der bestehenden Schichten: `WifiConnector` (WifiNetworkSpecifier + bindProcessToNetwork) und `BleWaker` (Android BLE-GATT mit Canons Smart-Pairing-Sequenz). Zugangsdaten in der bestehenden `Prefs`. Der Connect-Flow bekommt einen optionalen Auto-Pfad; PTP/Parser-Schichten bleiben unangetastet. Logikreiche, offline-testbare Teile (Credential-Validierung, BLE-Pairing-Paketbau) per TDD; die Android-Transport-APIs am Gerät über den Debug-Screen.

**Tech Stack:** Kotlin/Compose (bestehend), Android WifiNetworkSpecifier + ConnectivityManager (API 29+), Android BluetoothLeScanner/BluetoothGatt, SharedPreferences. Keine neuen Dependencies.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-20-ble-wake-and-wifi-autojoin-design.md` — gilt vollständig
- Package `io.github.canonwirelessraw`, minSdk 29, compileSdk 35, keine neuen Dependencies
- Der bestehende manuelle Connect-Pfad bleibt IMMER verfügbar (Auto ist additiv)
- BLE-Wake ist Best-Effort: ein erfolgreicher GATT-Write bedeutet NICHT, dass das WLAN hochkam — nie als „verbunden" melden, nur als „Wake gesendet"
- `bindProcessToNetwork` MUSS bei jedem Verbindungsende zurückgesetzt werden (sonst hängt das Gerät ohne Internet) — nicht wegoptimieren
- Client-GUID für BLE = dieselbe wie PTP/IP: `Prefs.pairingGuid()` (ein Identitätsbegriff)
- Verifizierte BLE-Fakten (aus gkoh/furble): Basis-UUID `xxxxxxxx-0000-1000-0000-d8492fffa821`; `00010006` Name(notify), `0001000a` Pairing/ID, `00030010` Modus; `MODE_WAKE=0x03`, `PAIR_ACCEPT=0x02`, `PAIR_REJECT=0x03`; Writes auf `0001000a`: `0x01`+Name, `0x03`+16-Byte-GUID, `0x04`+Name, `0x05`+`{0x02}`, nach Accept `{0x01}`
- Jeder Task endet mit grünem `assembleDebug` + `testDebugUnitTest` und einem Commit
- Build-Umgebung: `export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"` vor Gradle
- Commit-Trailer an jeden Commit:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01VB1tgQ9YtHzNuvDzGHs21Q`
- 56 Bestandstests bleiben grün

## Phasen-Übersicht

| Phase | Tasks | Abhängigkeit |
|-------|-------|--------------|
| A WiFi-Join | 1 (Prefs+Validierung) → 2 (WifiConnector) → 3 (CredentialsScreen+Connect-Flow) | sequenziell |
| B BLE-Wake | 4 (Pairing-Paketbau TDD) → 5 (BleWaker) → 6 (Manifest+Permissions+Debug-BLE) | 4 parallel zu Phase A möglich; 5/6 danach |
| C Doc | 7 (Milestone-Protokoll + README) | nach allem |

---

### Task 1: Prefs um WLAN-Zugangsdaten + Credential-Validierung erweitern (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/canonwirelessraw/data/Prefs.kt`
- Create: `app/src/main/java/io/github/canonwirelessraw/data/WifiCredentials.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/data/WifiCredentialsTest.kt`

**Interfaces:**
- Consumes: bestehende `Prefs` (SharedPreferences „prefs", hat bereits `pairingGuid()`, `lastIp`)
- Produces:
```kotlin
// WifiCredentials.kt
data class WifiCredentials(val ssid: String, val psk: String)
/** null = gültig; sonst deutscher Fehlertext. WPA2: PSK 8..63 Zeichen, SSID nicht leer/≤32. */
fun validateCredentials(ssid: String, psk: String): String?

// Prefs.kt — neue Members
var cameraSsid: String?   // key "camera_ssid"
var cameraPsk: String?    // key "camera_psk"
fun cameraCredentials(): WifiCredentials?   // beide vorhanden & nicht leer → WifiCredentials, sonst null
```

- [ ] **Step 1 (TDD):** `WifiCredentialsTest`:
```kotlin
class WifiCredentialsTest {
    @Test fun `valid credentials return null`() {
        assertNull(validateCredentials("EOS-R5-abc", "12345678"))
    }
    @Test fun `empty ssid rejected`() {
        assertNotNull(validateCredentials("", "12345678"))
    }
    @Test fun `ssid over 32 chars rejected`() {
        assertNotNull(validateCredentials("x".repeat(33), "12345678"))
    }
    @Test fun `psk under 8 chars rejected`() {
        assertNotNull(validateCredentials("EOS", "1234567"))
    }
    @Test fun `psk over 63 chars rejected`() {
        assertNotNull(validateCredentials("EOS", "x".repeat(64)))
    }
}
```
- [ ] **Step 2:** Lauf rot: `./gradlew testDebugUnitTest --tests '*WifiCredentialsTest'` → FAIL (validateCredentials fehlt).
- [ ] **Step 3:** `WifiCredentials.kt` implementieren:
```kotlin
package io.github.canonwirelessraw.data

data class WifiCredentials(val ssid: String, val psk: String)

fun validateCredentials(ssid: String, psk: String): String? = when {
    ssid.isBlank() -> "SSID darf nicht leer sein"
    ssid.length > 32 -> "SSID zu lang (max. 32 Zeichen)"
    psk.length < 8 -> "Passwort zu kurz (mind. 8 Zeichen)"
    psk.length > 63 -> "Passwort zu lang (max. 63 Zeichen)"
    else -> null
}
```
- [ ] **Step 4:** In `Prefs.kt` die drei Members ergänzen (Muster wie `lastIp`):
```kotlin
var cameraSsid: String?
    get() = prefs.getString("camera_ssid", null)
    set(v) = prefs.edit().putString("camera_ssid", v).apply()

var cameraPsk: String?
    get() = prefs.getString("camera_psk", null)
    set(v) = prefs.edit().putString("camera_psk", v).apply()

fun cameraCredentials(): WifiCredentials? {
    val s = cameraSsid; val p = cameraPsk
    return if (!s.isNullOrBlank() && !p.isNullOrBlank()) WifiCredentials(s, p) else null
}
```
- [ ] **Step 5:** Grün: `./gradlew testDebugUnitTest` (60 Tests). Commit: `feat: wifi credential storage and validation`.

---

### Task 2: WifiConnector (WifiNetworkSpecifier + Prozess-Bindung)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/wifi/WifiConnector.kt`
- Modify: `app/src/main/AndroidManifest.xml` (Permissions)

**Interfaces:**
- Consumes: `WifiCredentials` (Task 1)
- Produces:
```kotlin
class WifiConnector(private val context: Context) {
    /** Fordert das Kamera-WLAN an (System-Dialog), bindet den Prozess bei Erfolg an das Netz.
     *  Wirft WifiJoinException bei Timeout/Ablehnung/Fehler. */
    suspend fun connectToCamera(creds: WifiCredentials, timeoutMs: Long = 30_000): Unit
    /** Prozess-Bindung lösen + Callback abmelden. Idempotent. Immer im finally/onDispose aufrufen. */
    fun release()
}
class WifiJoinException(message: String) : Exception(message)
```

- [ ] **Step 1:** Manifest um Permissions ergänzen (innerhalb `<manifest>`, vor `<application>`):
```xml
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```
(INTERNET/ACCESS_NETWORK_STATE/ACCESS_WIFI_STATE sind bereits da.)
- [ ] **Step 2:** `WifiConnector.kt` implementieren. Kern (Coroutine-Bridge über `suspendCancellableCoroutine`):
```kotlin
package io.github.canonwirelessraw.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import io.github.canonwirelessraw.data.WifiCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiJoinException(message: String) : Exception(message)

class WifiConnector(private val context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    suspend fun connectToCamera(creds: WifiCredentials, timeoutMs: Long = 30_000) {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(creds.ssid)
            .setWpa2Passphrase(creds.psk)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val ok = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)   // Traffic (auch libpicts C-Socket) übers Kamera-Netz
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resumeWithException(
                            WifiJoinException("Kamera-WLAN nicht verfügbar (Dialog abgebrochen oder falsches Passwort?)"))
                    }
                }
                callback = cb
                cm.requestNetwork(request, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }
        if (ok != true) { release(); throw WifiJoinException("Zeitüberschreitung beim Verbinden mit dem Kamera-WLAN") }
    }

    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { cb -> runCatching { cm.unregisterNetworkCallback(cb) } }
        callback = null
    }
}
```
- [ ] **Step 3:** Build `./gradlew assembleDebug` → SUCCESS. Kein Unit-Test (reiner Android-Netz-API-Glue; Verifikation am Gerät in Task 3/Debug). `testDebugUnitTest` bleibt grün (60).
- [ ] **Step 4:** Commit: `feat: WifiConnector via WifiNetworkSpecifier with process binding`.

---

### Task 3: CredentialsScreen + Auto-Pfad im ConnectScreen

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ui/CredentialsScreen.kt`
- Modify: `app/src/main/java/io/github/canonwirelessraw/ui/ConnectScreen.kt`, `app/src/main/java/io/github/canonwirelessraw/MainActivity.kt`, `app/src/main/java/io/github/canonwirelessraw/AppContainer.kt`

**Interfaces:**
- Consumes: `Prefs.cameraCredentials()`/`cameraSsid`/`cameraPsk`, `validateCredentials`, `WifiConnector`, `CameraRepository.connect`
- Produces: `@Composable fun CredentialsScreen(container, onSaved, onBack)`; `AppContainer.wifi: WifiConnector`

- [ ] **Step 1:** `AppContainer` um `lateinit var wifi: WifiConnector` erweitern; in `init(context)` `wifi = WifiConnector(context.applicationContext)` erzeugen.
- [ ] **Step 2:** `CredentialsScreen.kt`: zwei `OutlinedTextField` (SSID, Passwort mit `PasswordVisualTransformation` + Sichtbar-Toggle), Default aus `prefs.cameraSsid`/`cameraPsk`. „Speichern": `validateCredentials` → bei Fehler roten Text zeigen, sonst `prefs.cameraSsid/psk =` setzen und `onSaved()`. Kurzer Hilfetext: „SSID und Passwort zeigt die R5 im Display unter ‚Mit Smartphone verbinden'." Deutsch.
- [ ] **Step 3:** `ConnectScreen` erweitern: Wenn `prefs.cameraCredentials() != null`, Button „Automatisch verbinden (WLAN + Kamera)" anzeigen; Handler:
```kotlin
scope.launch {
    busy = true; error = null
    runCatching {
        container.wifi.connectToCamera(prefs.cameraCredentials()!!)
        container.repo.connect(ipField)   // Gateway-IP, Default 192.168.1.2
    }.onSuccess { onConnected() }
     .onFailure { e -> container.wifi.release(); error = e.message ?: e.toString() }
    busy = false
}
```
Zusätzlich TextButton „WLAN-Zugangsdaten…" → navigiert zu CredentialsScreen. Der bestehende manuelle „Verbinden"-Button + IP-Feld bleiben unverändert darunter.
- [ ] **Step 4:** `MainActivity` Navigation: `Screen.Credentials` zum sealed interface hinzufügen; Connect→Credentials und zurück. Beim Verlassen einer getrennten Session (Gallery→Connect disconnect-Pfad) zusätzlich `container.wifi.release()` aufrufen, damit die Prozess-Bindung gelöst wird.
- [ ] **Step 5:** Build + Tests grün. Commit: `feat: credentials screen and auto-connect path (wifi join + ptp connect)`.

---

### Task 4: BLE-Smart-Pairing-Paketbau (reine Logik, TDD)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ble/CanonBlePairing.kt`
- Test: `app/src/test/java/io/github/canonwirelessraw/ble/CanonBlePairingTest.kt`

**Interfaces:**
- Consumes: nichts (pure Funktionen über ByteArray)
- Produces:
```kotlin
object CanonBlePairing {
    // Canon GATT-UUIDs (Basis xxxxxxxx-0000-1000-0000-d8492fffa821)
    val SERVICE_PHONE: UUID; val CHAR_NAME: UUID       // 00010006
    val CHAR_PAIRING: UUID                             // 0001000a
    val SERVICE_MODE: UUID; val CHAR_MODE: UUID        // 00030010
    const val MODE_WAKE: Byte = 0x03
    const val PAIR_ACCEPT: Byte = 0x02
    const val PAIR_REJECT: Byte = 0x03
    /** Die vier Writes auf CHAR_PAIRING in Reihenfolge:
     *  [0]=0x01+name(UTF-8), [1]=0x03+guid(16B), [2]=0x04+name(UTF-8), [3]=0x05+{0x02} */
    fun pairingWrites(deviceName: String, guid: ByteArray): List<ByteArray>
    fun uuid(short: String): UUID   // "00010006" -> volle Canon-UUID
}
```

- [ ] **Step 1 (TDD):** `CanonBlePairingTest`:
```kotlin
class CanonBlePairingTest {
    @Test fun `uuid builds canon 128bit from short`() {
        assertEquals(UUID.fromString("00010006-0000-1000-0000-d8492fffa821"),
                     CanonBlePairing.uuid("00010006"))
    }
    @Test fun `pairing writes have correct prefixes and order`() {
        val guid = ByteArray(16) { it.toByte() }
        val w = CanonBlePairing.pairingWrites("S25", guid)
        assertEquals(4, w.size)
        assertEquals(0x01.toByte(), w[0][0]); assertEquals("S25", String(w[0].copyOfRange(1, w[0].size)))
        assertEquals(0x03.toByte(), w[1][0]); assertArrayEquals(guid, w[1].copyOfRange(1, 17))
        assertEquals(0x04.toByte(), w[2][0]); assertEquals("S25", String(w[2].copyOfRange(1, w[2].size)))
        assertEquals(0x05.toByte(), w[3][0]); assertEquals(0x02.toByte(), w[3][1])
    }
    @Test fun `pairing write 2 rejects wrong guid length`() {
        try { CanonBlePairing.pairingWrites("S25", ByteArray(8)); fail() }
        catch (e: IllegalArgumentException) { /* erwartet */ }
    }
}
```
- [ ] **Step 2:** Rot laufen lassen.
- [ ] **Step 3:** Implementieren: `uuid(short)` = `UUID.fromString("$short-0000-1000-0000-d8492fffa821")`; `pairingWrites` baut die vier Byte-Arrays (Präfix-Byte + Nutzlast via `byteArrayOf(prefix) + payload`), `require(guid.size == 16)`.
- [ ] **Step 4:** Grün: `./gradlew testDebugUnitTest` (63 Tests). Commit: `feat: Canon BLE smart-pairing packet builder with tests`.

---

### Task 5: BleWaker (BLE-Scan, Pairing, Wake-Versuch)

**Files:**
- Create: `app/src/main/java/io/github/canonwirelessraw/ble/BleWaker.kt`

**Interfaces:**
- Consumes: `CanonBlePairing` (Task 4), `Prefs.pairingGuid()`
- Produces:
```kotlin
data class BleDevice(val name: String, val address: String)
enum class WakeResult { PAIRED_WAKE_SENT, NEEDS_CONFIRMATION, REJECTED, FAILED }
class BleWaker(private val context: Context) {
    /** Scannt nach gebondeten Canon-Kameras (Name enthält "EOS"/"Canon" ODER Service SERVICE_PHONE). null bei Timeout. */
    suspend fun scanForCamera(timeoutMs: Long = 10_000): BleDevice?
    /** Verbindet, führt Pairing-Writes aus, schreibt MODE_WAKE auf CHAR_MODE.
     *  WAKE_SENT bedeutet NUR erfolgreicher GATT-Write, NICHT dass WLAN hochkam. */
    suspend fun pairAndWake(device: BleDevice, deviceName: String = "Canon Wireless RAW"): WakeResult
    fun close()
}
```

- [ ] **Step 1:** Implementieren mit `BluetoothLeScanner` (ScanFilter auf `CanonBlePairing.SERVICE_PHONE`; wenn leer, Fallback über `BluetoothAdapter.bondedDevices` mit Namensfilter „EOS"/„Canon"). GATT-Ablauf über `suspendCancellableCoroutine` je Schritt: connect → discoverServices → setCharacteristicNotification auf CHAR_NAME → nacheinander die vier `pairingWrites` auf CHAR_PAIRING schreiben → auf notify PAIR_ACCEPT/REJECT warten (Timeout 60 s → NEEDS_CONFIRMATION) → bei Accept finalisieren (`{0x01}`) und `MODE_WAKE` auf CHAR_MODE schreiben → PAIRED_WAKE_SENT. Alle GATT-Callbacks sauber entkoppeln, `close()` schließt `BluetoothGatt`.
- [ ] **Step 2:** Guards: Bluetooth aus (`adapter?.isEnabled != true`) → FAILED mit Log; fehlende Runtime-Permission → FAILED (die eigentliche Abfrage macht der Debug-Screen/UI in Task 6). Kein Absturz ohne Permission.
- [ ] **Step 3:** Build `./gradlew assembleDebug` → SUCCESS; `testDebugUnitTest` grün (63, unverändert — BLE-Transport nicht unit-getestet, am Gerät via Task 6). Commit: `feat: BleWaker scan, smart-pairing and wake attempt`.

---

### Task 6: Permissions-Manifest + Runtime-Anfrage + Debug-BLE-Abschnitt

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/github/canonwirelessraw/ui/DebugScreen.kt`, `app/src/main/java/io/github/canonwirelessraw/AppContainer.kt`

**Interfaces:**
- Consumes: `BleWaker` (Task 5)
- Produces: `AppContainer.ble: BleWaker`; Debug-Screen mit BLE-Buttons

- [ ] **Step 1:** Manifest-Permissions ergänzen:
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```
- [ ] **Step 2:** `AppContainer`: `lateinit var ble: BleWaker`; in `init` `ble = BleWaker(context.applicationContext)`.
- [ ] **Step 3:** `DebugScreen` um einen BLE-Abschnitt erweitern (unter den PTP-Buttons): Runtime-Permission-Anfrage für `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT` via `rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())`; Buttons:
  - „BLE: Kamera suchen" → `ble.scanForCamera()`, loggt gefundenes Gerät oder „nichts gefunden (im Kameramenü koppeln!)"
  - „BLE: Pairing + Wake" → `ble.pairAndWake(device)`, loggt `WakeResult`; bei NEEDS_CONFIRMATION Hinweis „am Kamera-Display bestätigen"; nach WAKE_SENT Hinweis „prüfe, ob das Kamera-WLAN jetzt erscheint"
  Gleiches `busy`/`runCatching`-Muster wie die bestehenden Debug-Buttons.
- [ ] **Step 4:** Build + Tests grün. Commit: `feat: BLE permissions and debug-screen wake harness`.

---

### Task 7: Milestone-Protokoll + README aktualisieren

**Files:**
- Modify: `docs/MILESTONE-0-PROTOKOLL.md`, `README.md`

- [ ] **Step 1:** `MILESTONE-0-PROTOKOLL.md`: neuen Abschnitt „BLE-Wake & Auto-WiFi (experimentell)" mit Schritten: (a) Kamera im Menü mit S25 koppeln; (b) Debug „BLE: Kamera suchen" → Gerät erscheint?; (c) „BLE: Pairing + Wake" → WakeResult + erscheint danach das Kamera-WLAN? (Kernfrage — entscheidet, ob MODE_WAKE=0x03 den Wake auslöst); (d) Zugangsdaten eingeben, „Automatisch verbinden" → verbindet? Ergebnis-Spalten zum Ausfüllen.
- [ ] **Step 2:** `README.md`: Feature-Liste um „optionaler Auto-WiFi-Join und experimenteller BLE-Wake" ergänzen; klarstellen, dass BLE-Wake unverifiziert ist und ein einmaliges Pairing im Kameramenü braucht; neue Permissions nennen.
- [ ] **Step 3:** `./gradlew assembleDebug testDebugUnitTest` final grün. Commit: `docs: document BLE wake and wifi auto-join, extend milestone protocol`.

---

## Verifikation, die nur der Nutzer machen kann

Teil A (WiFi-Join) ist am Gerät sofort nutzbar, sobald SSID/PSK eingegeben sind. Teil B (BLE-Wake) hängt an der offenen Frage, ob `MODE_WAKE=0x03` die R5 tatsächlich ins WLAN bringt — das beantwortet nur Schritt (c) des erweiterten Protokolls an der echten Kamera. Fällt er negativ aus, bleibt Teil A voll funktionsfähig und der BLE-Teil ein folgenloser No-Op (Nutzer schaltet WLAN an der Kamera ein).
