# Design: BLE-Wake + Auto-WiFi-Join

**Datum:** 2026-07-20
**Status:** Vom Auftraggeber abgenommen
**Baut auf:** 2026-07-19-canon-wireless-raw-android-design.md (Kern-App)

## Ziel

Den Verbindungsablauf komfortabler machen, angelehnt an Canon Camera Connect:
1. **Auto-WiFi-Join** — die App verbindet das Telefon mit dem Kamera-WLAN, statt
   dass der Nutzer manuell ins Android-Einstellungsmenü wechselt.
2. **BLE-Wake** — die Kamera per Bluetooth LE finden und ihr WLAN aufwecken,
   sodass sie nicht am Gerät manuell in den WLAN-Modus geschaltet werden muss.

## Recherche-Grundlage (Machbarkeit)

- Canons BLE-**Pairing + „Smart/Phone"-Handshake** ist öffentlich reverse-engineered
  und in lauffähigem Code nachgebaut: gkoh/furble (`CanonEOSSmart.h/.cpp`).
  Verifizierte GATT-Basis-UUID `xxxxxxxx-0000-1000-0000-d8492fffa821`.
  - Service 0001 (Phone): `00010006` (Name, notify), `0001000a` (Pairing/ID)
  - Service 0003 (Modus/Auslöser): `00030010` (Modus), `00030030` (Auslöser)
  - Konstanten (aus furble-Quelle verifiziert): `MODE_PLAYBACK=0x01`,
    `MODE_SHOOT=0x02`, `MODE_WAKE=0x03`, `PAIR_ACCEPT=0x02`, `PAIR_REJECT=0x03`
  - Pairing-Sequenz: OS-Bonding → subscribe `00010006` → vier Writes auf
    `0001000a` (`0x01`+Name, `0x03`+16-Byte-Client-UUID, `0x04`+Name, `0x05`+`{0x02}`)
    → bis 60 s auf Kamera-Bestätigung warten (notify `PAIR_ACCEPT`/`PAIR_REJECT`)
    → bei Accept `{0x01}` finalisieren, dann Modus-Byte auf `00030010`.
- **Kritische, öffentlich UNGELÖSTE Lücke:** der konkrete „WLAN einschalten"-Trigger
  und ob die Kamera SSID/Passwort per BLE herausgibt. `MODE_WAKE=0x03` existiert als
  Konstante, wird aber selbst in furble nirgends aufgerufen (furble ist reine
  BLE-Fernbedienung ohne WLAN-Brücke). Kein Drittanbieter (Cascable, qDslrDashboard,
  Camera Connect & Control) hat den BLE-Wake nachgebaut. Sauber verifizierbar nur mit
  BLE-Snoop-Log an echter R5.
- **Canon-Randbedingung (Manual-bestätigt):** Erstpairing MUSS im Kameramenü erfolgen
  (`Mit Smartphone verbinden → Gerät hinzufügen → über Bluetooth koppeln`); ein
  ungebondetes Telefon kann die Kamera nicht per Scan aufwecken. BLE bleibt nach
  Auto-Power-Off aktiv, aber es gibt vermutlich einen tieferen Sleep-Zustand mit
  anderem Verhalten (eos-remote-web: nach echtem Sleep Entpairen+Neustart nötig).

**Konsequenz fürs Design:** Teil A (WiFi-Join) ist garantiert baubar und der eigentliche
Nutzen. Teil B (BLE-Wake) ist Best-Effort — Pairing/Handshake baue ich aus verifiziertem
Wissen, der Wake-Write ist ein Versuch, dessen Wirkung erst der Gerätetest zeigt.

## Teil A — Auto-WiFi-Join (garantiert)

### Zugangsdaten
- SSID + Passphrase des Kamera-WLANs werden **einmalig manuell eingegeben** (die R5
  zeigt beide im Display bei „Mit Smartphone verbinden"). Persistenz in
  SharedPreferences über die bestehende `Prefs`-Klasse (`camera_ssid`, `camera_psk`).
- Grund für manuelle Eingabe: BLE-Credential-Übertragung ist nicht belegt (siehe oben),
  und ohne SSID/PSK kann `WifiNetworkSpecifier` das Netz nicht anfordern.

### Verbindung
- `WifiConnector` (neue Klasse) nutzt `WifiNetworkSpecifier` (Android 10+, minSdk 29):
  Netzanforderung mit SSID + WPA2-Passphrase über
  `ConnectivityManager.requestNetwork(request, callback)`. Der Nutzer bestätigt einen
  System-Dialog (Android-Sicherheit, unvermeidbar) — aber kein Gang ins Settings-Menü.
- **Traffic-Routing (kritisch):** So verbundene Netze haben kein Internet; Android routet
  sonst über Mobilfunk. In `onAvailable(network)` ruft `WifiConnector`
  `ConnectivityManager.bindProcessToNetwork(network)` — danach gehen alle neuen Sockets
  (auch libpicts C-Socket in ip.c) über das Kamera-Netz, ohne JNI-Änderung. Beim Trennen
  `bindProcessToNetwork(null)` + `unregisterNetworkCallback`.
- API: `suspend fun connectToCamera(ssid: String, psk: String): Network` (wirft bei
  Timeout/Ablehnung `WifiJoinException`); `fun release()`.

### Integration in den Connect-Flow
- ConnectScreen bekommt zwei Modi: „manuell" (bisheriger Pfad, WLAN schon verbunden →
  nur IP) und „automatisch" (SSID/PSK vorhanden → `WifiConnector.connectToCamera` →
  danach `repo.connect(ip)`). Gateway-IP der Kamera bleibt Default `192.168.1.2`.
- CredentialsScreen (neu, schlicht): SSID + Passphrase eingeben/ändern, in Prefs sichern.

## Teil B — BLE-Wake (Best-Effort, experimentell)

### Voraussetzung
- Nutzer koppelt die R5 einmalig im Kameramenü mit dem S25 (Canon-bedingt). Danach ist
  das Gerät OS-gebondet und per Scan auffindbar.

### BleWaker (neue Klasse)
- Android `BluetoothLeScanner` findet die gebondete Kamera (Filter auf Canon-Service-UUID
  oder bekannten Gerätenamen); `BluetoothGatt` verbindet.
- Führt die furble-verifizierte Smart-Pairing-Sequenz aus (siehe Recherche oben), nutzt
  die persistierte Client-GUID aus `Prefs.pairingGuid()` (dieselbe wie PTP/IP — bewusst,
  ein Identitätsbegriff für die App). Bei erstem Kontakt wartet er auf die
  Display-Bestätigung; danach reconnectet OS-Bonding automatisch.
- **Wake-Versuch:** schreibt `MODE_WAKE=0x03` auf `00030010`. Ob das die R5 in den
  WLAN-Smartphone-Modus bringt, ist unbestätigt — der Rückgabewert meldet nur, ob der
  GATT-Write erfolgreich war, NICHT ob das WLAN hochkam.
- API: `suspend fun scanForCamera(timeoutMs): BleDevice?`,
  `suspend fun pairAndWake(device): WakeResult` mit
  `WakeResult { PAIRED_WAKE_SENT, NEEDS_CONFIRMATION, REJECTED, FAILED }`.
- Berechtigungen: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Runtime-Abfrage ab Android 12),
  `ACCESS_FINE_LOCATION` nur falls für Scan auf der Ziel-Android-Version nötig.

### Verifikation
- Der bestehende Debug-Screen bekommt einen Abschnitt „BLE": Scan / Pair+Wake / Log.
  Am Gerät prüfst du, ob nach dem Wake-Write das Kamera-WLAN erscheint. Ergebnis wird
  ins Milestone-Protokoll aufgenommen (neue Schritte).

## Fehlerbehandlung

- WiFi: Timeout (Nutzer bestätigt System-Dialog nicht), falsche Passphrase, Netz nicht
  gefunden → `WifiJoinException` mit handlungsleitender Meldung; Fallback-Hinweis „WLAN
  manuell verbinden und IP eingeben" (der bisherige Pfad bleibt immer verfügbar).
- BLE: kein gebondetes Gerät gefunden → Hinweis aufs Kameramenü-Pairing; Ablehnung an der
  Kamera → klare Meldung; Bluetooth aus → Aufforderung zum Einschalten.
- `bindProcessToNetwork` wird bei jedem Verbindungsende und bei App-Hintergrund
  zuverlässig zurückgesetzt (sonst hängt das ganze Gerät ohne Internet).

## Tests

- `WifiConnector`: Die Android-Netz-APIs sind nicht JVM-unit-testbar → am Gerät verifiziert
  (Debug-Screen). Testbar und als Unit-Test ausgeführt: die Zugangsdaten-Validierung
  (SSID nicht leer, PSK ≥ 8 Zeichen für WPA2) als reine Funktion.
- `BleWaker`: die Pairing-**Paket-Konstruktion** (die vier Writes auf `0001000a`:
  Byte-Layout Präfix + Name-UTF/Client-GUID) als reine Kotlin-Funktion mit Unit-Tests
  (analog zum PTP/IP-Init-Paket) — der einzige logikreiche, offline prüfbare Teil.
  BLE-Transport selbst am Gerät.
- Der bestehende manuelle Pfad und die 56 Bestandstests bleiben grün.

## Referenzen

- gkoh/furble `CanonEOSSmart.h/.cpp` (verifizierter BLE-Smart-Pairing-Code)
- iandouglasscott.com (2017/2018) — Canon-BLE-GATT-Struktur, Ursprungs-Reverse-Engineering
- Canon R5/R7 Manual — BLE-nach-Auto-Power-Off, Pairing nur im Kameramenü
- Android: WifiNetworkSpecifier, ConnectivityManager.bindProcessToNetwork (API 29+)
