# Design: Canon Wireless RAW für Android

**Datum:** 2026-07-19
**Status:** Vom Auftraggeber abgenommen

## Ziel

Android-App (GitHub-Release, F-Droid-tauglich), die ohne jede Kamera-Freischaltung
per WLAN Bilder von Canon-EOS-Kameras (Referenzgerät: EOS R5) auflistet, nach
Kamera-Rating filtert, CR3/RAW herunterlädt und wahlweise per Share-Sheet an
Lightroom/Luminar übergibt.

**Bewusst NICHT CCAPI:** CCAPI erfordert pro Kamera eine einmalige Freischaltung
per Canon-Tool (PC/Mac + Canon-Account). Ziel ist „APK installieren und los" für
jeden Nutzer — daher PTP/IP über die normale „Mit Smartphone verbinden"-Funktion
der Kamera (TCP-Port 15740, ISO 15740). Machbarkeit belegt durch gphoto2
(`--port ptpip:` mit EOS R), Camera Connect iOS (RAW-Download) und Drittapps
(Camera Connect & Control, CamControl).

## Anforderungen

1. Alle Bilder der Speicherkarte anzeigen, sortiert nach Aufnahmedatum (neueste zuerst)
2. Filter nach Kamera-Rating (★1–★5, in der R5-Galerie vergeben) — **Pflicht in V1**
3. CR3-Original herunterladen (JPEG/HEIF ebenfalls)
4. Nach Download: nur speichern ODER Share-Sheet (Lightroom, Luminar, …)
5. Keine Kamera-Freischaltung, kein Canon-Account, keine Cloud
6. R5 als Referenz; andere EOS-Modelle nicht künstlich gesperrt
7. Netz: Kamera-AP-Modus (primär) und gemeinsames Heim-WLAN (manuelle IP).
   Automatische SSDP-Discovery ist bewusst NICHT in V1 — manuelle IP reicht,
   Discovery kann später nachgerüstet werden
8. Videos (MP4/CRM) sind in V1 ausgeklammert

## Architektur

### Stack

- Kotlin + Jetpack Compose, Single-Module-App, minSdk 29 (Android 10+)
- **libpict** (petabyt/libpict, Apache-2.0, ehem. camlib) als Git-Submodule,
  CMake-Subproject via `externalNativeBuild`
  - CMake-Flags: `-DPTP_USE_LIBUSB=OFF -DPTP_NO_USB=ON` (nur libc/pthread/Sockets)
- Eine JNI-Bridge-C-Datei. Exponierte Funktionen:
  `connect(ip, guid, name)`, `listObjects()`, `getObjectInfo(handle)`,
  `getPartial(handle, offset, len)`, `downloadToFile(handle, fd)` mit
  Fortschritts-Callback, `disconnect()`
- Kotlin-Seite: `PtpClient` (suspend-Funktionen, `Dispatchers.IO`); libpict ist
  blocking + mutex-geschützt und hat einen Kill-Switch für Abbrüche

### Verbindungsablauf (libpict, aus `examples/wifi.c` verifiziert)

`ptp_new(PTP_IP)` → `ptpip_connect(ip, 15740)` → `ptpip_init_command_request(name)`
→ `ptp_open_session` → `ptp_eos_set_remote_mode(1)` → `ptp_eos_set_event_mode(1)`

**Nötiger libpict-Patch:** `ptpip_init_command_request` hartkodiert die
Client-GUID (`0xffffffff…`). Die GUID ist das Pairing-Merkmal, an dem die Kamera
den Client wiedererkennt. Wir parametrisieren sie und persistieren eine einmalig
erzeugte GUID in DataStore. Erstverbindung: Nutzer bestätigt an der Kamera
(Zeitfenster wenige Sekunden!); Folgeverbindungen laufen automatisch.

### Rating & Thumbnails

Pro Bild EIN `GetPartialObject` über die ersten ~128 KB, daraus beides:

- **Rating (CR3):** CR3 ist ISO-BMFF. Box-Scan nach der XMP-uuid-Box
  (`be7acfcb-97a9-42e8-9c71-999491e3afac`), darin `xmp:Rating` als Text parsen.
  Identisch mit dem, was exiftool liest. Box-Offset variiert je Modell → Scan,
  kein Fix-Offset.
- **Rating (JPEG/HEIF):** EXIF-Rating-Tag aus demselben Partial-Read
  (`ExifInterface` über Byte-Array).
- **Thumbnail:** eingebettetes Vorschau-JPEG per SOI/EOI-Marker-Scan
  (FF D8 … FF D9) im selben Puffer (Technik aus canon-mtp-direct übernommen).

Disk-Cache mit Key `objectHandle + Dateigröße`. Laden lazy für sichtbare
Galerie-Items. Rating-Filter wirkt auf bereits Bekanntes; Button
„Alle Ratings einlesen" erzwingt Voll-Scan mit Fortschrittsanzeige.

### Bildliste

`GetStorageIDs` → `GetObjectHandles` (flat; Fallback rekursiv über
Association-Ordner) → `GetObjectInfo` pro Handle (Dateiname, Größe,
`date_created`). Filter auf Endungen CR3/JPG/HIF/HEIF. Sortierung:
`date_created` absteigend. ObjectInfo-Ergebnisse werden pro Session gecacht.

### Download

`ptp_download_object` / eigene Schleife mit `GetPartialObject` in 1-MB-Chunks
(hält libpicts Single-Buffer klein — kein Voll-Datei-malloc bei 50-MB-CR3s).
Ziel: MediaStore, `Pictures/CanonRAW/`. Fortschritt via überschriebenem
`ptp_report_read_progress`-Hook → JNI-Callback. Abgebrochene/halbe Downloads
werden gelöscht. Danach optional `ACTION_SEND_MULTIPLE`-Share-Sheet.

### UI (3 Screens)

1. **Verbinden:** AP-Modus-Kurzanleitung, „Verbinden"-Button (Gateway-IP der
   Kamera vorbelegt), manuelles IP-Feld fürs Heim-WLAN. Fehlertexte erklären
   das Kamera-Bestätigungs-Zeitfenster.
2. **Galerie:** Grid neueste-zuerst, Filter-Chips ★1–★5, Dateityp-Badge,
   Mehrfachauswahl.
3. **Download:** Fortschritt pro Datei, danach „Fertig" oder „Teilen…".
   Der Milestone-0-Debug-Screen bleibt als versteckter Entwickler-Screen erhalten.

## Risiko & Milestone 0 (Spike, VOR der App-UI)

libpict spricht bei EOS nur den Standard-PTP-Pfad (GetObjectHandles /
GetPartialObject); Canon-Vendor-Opcodes wie `EOS_GetObjectInfoEx (0x9109)` oder
`GetPartialObjectEx (0x901B)` sind nicht implementiert. gphoto2-Berichte belegen
Standard-Listing/-Download über PTP/IP bei EOS-R-Bodies — ob die R5 im
Smartphone-Modus identisch reagiert, ist die zentrale offene Frage.

**Milestone 0:** Minimal-App, ein Debug-Screen, gegen die echte R5:

1. Handshake + Pairing (GUID-Persistenz)
2. `GetObjectHandles` liefert Karteninhalt
3. `GetPartialObject`: 128 KB eines CR3 lesen
4. Rating aus dem CR3-Header parsen (Vergleich mit an der Kamera gesetztem Wert)
5. Voll-Download einer CR3 mit Fortschritt
6. Zusätzlich testen: Verhalten mit/ohne `SetRemoteMode` (Smartphone-Modus
   braucht ihn evtl. nicht)

**Fallback bei Fehlschlag von 2/3:** Canon-Vendor-Pfad (EOS-Events +
`GetObjectInfoEx 0x9109`, `EOS_GetStorageIDs 0x9101`) in libpict nachrüsten;
Referenz-Implementierung: libgphoto2 `camlibs/ptp2`.

## Fehlerbehandlung

- Verbindungs-Timeouts mit handlungsleitenden Meldungen (Pairing-Zeitfenster,
  falsche IP, Kamera im falschen Menü)
- Reconnect-Logik nach Verbindungsabriss (GUID macht Re-Pairing unnötig)
- Downloads abbrechbar (Kill-Switch), Partial-Dateien werden aufgeräumt

## Tests

- **CR3-Parser (Box-Scan, XMP-Rating, JPEG-Extraktion): reine Kotlin-Unit-Tests**
  mit echten Header-Bytes von R5-CR3s als Fixtures (einmalig per USB kopiert).
  Das ist der logikreichste, komplett offline testbare Teil.
- Protokoll-Layer: manuell gegen echte Kamera über den Debug-Screen
  (Checkliste = Milestone-0-Punkte). Kein Kamera-Mock — der Aufwand lohnt nicht.
- JNI-Bridge: schmal halten, Logik lebt in Kotlin oder in libpict selbst.

## Recherche-Referenzen

- libpict-API/Build/Lizenz: verifiziert am `master`-Stand (Quelldateien-Kopien
  im Session-Scratchpad `lp/`)
- Canon PTP/IP Handshake & Pairing: julianschroden.com Blog-Serie (5 Teile, EOS 70D)
- EOS-R-über-ptpip-Nachweise: gphoto/libgphoto2 Issues #1133, gphoto2 #356
- CR3-Struktur/XMP-Box: lclevy/canon_cr3
- Thumbnail-Technik: Akopmm/canon-mtp-direct (`ThumbnailUtils.kt`)
