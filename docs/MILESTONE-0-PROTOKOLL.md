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
