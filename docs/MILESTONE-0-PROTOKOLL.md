# Milestone-0-Protokoll: Verifikation gegen die echte R5

Dieses Dokument ist die Testcheckliste für die einzige Verifikation, die kein
Unit-Test ersetzen kann: läuft die PTP/IP-Verbindung im "Mit Smartphone
verbinden"-Modus der Kamera tatsächlich so, wie im Design angenommen? Alles
andere (Build, 53 Unit-Tests) ist bereits grün — dieses Protokoll ist der
fehlende Baustein.

## Voraussetzungen / Setup

1. **Kamera (EOS R5):** Menü → WLAN-Einstellungen → **„Mit Smartphone
   verbinden"** auswählen. Die Kamera öffnet ihr eigenes WLAN und zeigt die
   Gateway-IP an (Standard: `192.168.1.2`).
2. **Handy (Galaxy S25):** mit dem soeben geöffneten Kamera-WLAN verbinden.
3. **App:** Debug-APK installieren (`app/build/outputs/apk/debug/app-debug.apk`),
   öffnen, auf dem Connect-Screen unten auf **„Debug"** tippen, um in den
   Milestone-0-Debug-Screen zu wechseln.
4. IP-Feld im Debug-Screen prüfen/anpassen (Default `192.168.1.2`), dann die
   Schritte unten **der Reihe nach** antippen — jeder Button ist deaktiviert,
   während der vorherige noch läuft.

## Checkliste

| # | Button (Debug-Screen) | Was tun | Erwartung | Ergebnis |
|---|---|---|---|---|
| 1 | **„1. Connect (Handshake+Pairing)"** | Antippen. Falls die Kamera einen Bestätigungsdialog zeigt: **sofort** an der Kamera bestätigen (Zeitfenster nur wenige Sekunden). | Log zeigt `Connect: ip=…` dann `Connect OK`. Bei Erstverbindung bestätigt die Kamera den neuen Client; bei Folgeverbindungen (gleiche GUID) läuft es ohne Dialog durch. | ☐ |
| 2 | **„2. EOS Remote/Event Mode"** | Antippen. | Log zeigt `eosSetRemoteMode rc=0`, `eosSetEventMode rc=0`, dann `EOS Mode OK`. Ein Fehlercode ungleich 0 ist ein gültiges (dokumentierenswertes) Ergebnis, kein Blocker für die nächsten Schritte. | ☐ |
| 3 | **„3. Liste laden"** | Antippen. | Log zeigt `Liste geladen: N Objekte` (N = Anzahl Bilder auf der Karte) plus die ersten 5 Dateinamen mit Aufnahmedatum. | ☐ |
| 4 | **„4. 128 KB Partial + Rating"** | Antippen (setzt voraus, dass Schritt 3 mind. ein CR3 gefunden hat). | Log zeigt `Partial gelesen: 131072 Bytes von …`, danach `Rating: X, embeddedJpeg: Y Bytes`. Rating `X` mit dem an der Kamera für dieses Bild gesetzten Sterne-Wert vergleichen — muss übereinstimmen. | ☐ |
| 5 | **„5. Voll-Download erste CR3"** | Antippen. | Log zeigt `Download: …` (Dateiname + Größe), 10%-Fortschrittsschritte, zuletzt `Download fertig: content://…`. Datei in `Pictures/CanonRAW/` (MediaStore) prüfen — Größe muss der Kamera-Dateigröße entsprechen. | ☐ |
| 6 | **„Disconnect"** | Antippen. | Log zeigt `Disconnect OK`. Kamera zeigt keinen hängenden Client mehr an (WLAN-Status der Kamera prüfen). | ☐ |

**Ergebnis-Spalte:** ☐ = offen, ✅ = wie erwartet, ❌ = Fehlschlag (Fehlermeldung
aus dem Log hier notieren, insbesondere die rohen `PtpException`-Codes — sie
zeigen Schritt und Fehlercode).

## Fallback bei Fehlschlag von Schritt 3 oder 4

libpict spricht bei EOS nur den **Standard-PTP-Pfad**
(`GetObjectHandles`/`GetPartialObject`). Schlägt Schritt 3 (Liste laden) oder
Schritt 4 (Partial + Rating) fehl — z. B. weil die R5 im
Smartphone-Kopplungsmodus auf Standard-PTP-Objektzugriff nicht reagiert —, ist
das kein App-Bug, sondern das im Design-Dokument als offenes Risiko benannte
Szenario. Der Fallback dafür ist der **Canon-Vendor-Opcode-Pfad**
(`EOS_GetObjectInfoEx` 0x9109, `EOS_GetStorageIDs` 0x9101 u. a.), den libpict
aktuell nicht implementiert. Referenzimplementierung: libgphoto2
`camlibs/ptp2`. Das ist ein eigener Folge-Implementierungs-Durchgang, keine
Korrektur an den bestehenden 13 Tasks — siehe „Risiko & Milestone 0" in
[`docs/superpowers/specs/2026-07-19-canon-wireless-raw-android-design.md`](superpowers/specs/2026-07-19-canon-wireless-raw-android-design.md).

## Ergebnis dieses Protokolls

_(Nach Durchlauf ausfüllen: Datum, Kamera-Firmware, alle 6 Schritte ✅, oder
welcher Schritt fehlschlug und mit welchem Fehlercode.)_

## BLE-Wake & Auto-WiFi (experimentell)

Zusatzfunktionen aus Phase 2, unabhängig von der PTP/IP-Kernverifikation oben.
Teil A (Auto-WiFi-Join) ist eigenständig nutzbar, sobald WLAN-Zugangsdaten
gespeichert sind. Teil B (BLE-Wake) ist **unverifiziert**: ob der
`MODE_WAKE=0x03`-Schreibzugriff über Bluetooth die R5 tatsächlich in den
„Mit Smartphone verbinden"-WLAN-Modus versetzt, ist offen — kein bekanntes
Drittanbieter-Projekt hat das nachgebaut, der Rückgabewert bestätigt nur, dass
der GATT-Write erfolgreich war, nicht dass das WLAN hochkam. Schritt (c) unten
ist die einzige Verifikation, die diese Frage beantwortet.

### Voraussetzung: einmaliges BLE-Pairing im Kameramenü

Canon verlangt für BLE-Zugriff ein manuelles Erstpairing, das die App nicht
umgehen kann: an der R5 **Menü → WLAN-Einstellungen → „Mit Smartphone
verbinden" → „Gerät hinzufügen" → über Bluetooth koppeln** ausführen, bevor
Schritt (b) unten funktionieren kann.

### Checkliste

| # | Schritt | Button (App) | Was tun | Erwartung | Ergebnis |
|---|---|---|---|---|---|
| a | Kamera koppeln | *(kein Button — an der Kamera)* | Menü → „Mit Smartphone verbinden" → „Gerät hinzufügen" → über Bluetooth koppeln. | Kamera zeigt das Handy als gekoppeltes Gerät. | ☐ |
| b | BLE-Scan | Debug-Screen: **„BLE: Kamera suchen"** | Antippen (fragt beim ersten Mal Bluetooth-Berechtigungen an — erteilen, danach erneut tippen). | Log zeigt `Gefunden: <Name> (<Adresse>)`. Bleibt das aus, war a) nicht erfolgreich. | ☐ |
| c | **Kernfrage:** Pairing + Wake | Debug-Screen: **„BLE: Pairing + Wake"** | Erst nach erfolgreichem b) antippen. | Log zeigt `WakeResult: …` (siehe Werte unten). Bei `PAIRED_WAKE_SENT`: **am Handy die WLAN-Liste prüfen — erscheint jetzt das Kamera-WLAN, ohne dass am Kamera-Display manuell „Mit Smartphone verbinden" gestartet wurde?** Das — und nur das — beantwortet, ob `MODE_WAKE=0x03` den Wake tatsächlich auslöst. | ☐ |
| d | Auto-WiFi-Join | ConnectScreen: „WLAN-Zugangsdaten…" → SSID/Passwort → „Speichern"; danach **„Automatisch verbinden (WLAN + Kamera)"** | SSID/Passwort so eintragen, wie sie die R5 im Display unter „Mit Smartphone verbinden" zeigt, speichern, zurück zum ConnectScreen, Button antippen. | App verbindet sich selbst mit dem Kamera-WLAN (`WifiNetworkSpecifier` + `bindProcessToNetwork`) und danach per PTP/IP — kein manueller Wechsel in die Android-WLAN-Einstellungen nötig. | ☐ |

**WakeResult-Werte** (aus `BleWaker.kt`): `PAIRED_WAKE_SENT` (Pairing- und
`MODE_WAKE`-Writes erfolgreich abgesetzt — bestätigt nur den Schreibzugriff,
nicht dass das WLAN hochkam), `NEEDS_CONFIRMATION` (kein Accept/Reject-Notify
innerhalb 60 s — vermutlich ist eine Bestätigung am Kamera-Display nötig),
`REJECTED` (Kamera hat das Pairing abgelehnt), `FAILED` (Bluetooth aus,
fehlende Berechtigung, Verbindungsabbruch oder fehlgeschlagener GATT-Schritt).

**Ergebnis-Spalte:** wie oben — ☐ offen, ✅ wie erwartet, ❌ Fehlschlag
(Ergebnis/Fehlermeldung notieren; bei c) insbesondere festhalten, ob das
Kamera-WLAN nach `PAIRED_WAKE_SENT` erschienen ist oder nicht).

### Ergebnis dieses Abschnitts

_(Nach Durchlauf ausfüllen: Datum, ob a) und b) erfolgreich waren, welcher
WakeResult bei c) auftrat und ob das Kamera-WLAN danach ohne manuellen Eingriff
an der Kamera erschien, ob d) WLAN + Kamera erfolgreich verbunden hat.)_
