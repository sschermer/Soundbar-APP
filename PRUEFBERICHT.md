# Prüfung Tonmaschine V16 – 14.11.2026

## Umfang

Geprüft: `Tonmaschine.py`, `Tonmaschine.iss`, `requirements.txt`, `.gitignore`, README und Paketinhalt. Die Python-Datei des ursprünglichen GitHub-Pakets war bytegenau identisch mit der zuletzt erzeugten V16. Das ursprüngliche Paket blieb unverändert. Korrekturen wurden ausschließlich in dieser separaten Arbeitskopie vorgenommen.

## Bestätigte Ausgangsprobleme und Korrekturen

1. **Konfigurationsverlust bei Updates (hoch):** Das Inno-Setup-Skript schrieb `config.json` bei jedem Lauf neu. Jetzt werden die Einrichtungsseiten bei vorhandener Konfiguration übersprungen und die vorhandene Datei nicht verändert. Für Änderungen die Konfiguration in der App öffnen.
2. **V7/V9-Import aus der Windows-EXE unzuverlässig (mittel):** Der starre Dateipfad konnte bei PyInstaller-Onefile auf ein temporäres Verzeichnis zeigen. Stattdessen kann die alte JSON-Datei über einen Dateiauswahldialog ausgewählt werden.
3. **Nach Kommunikationsfehlern weiterhin freigeschaltete Bedienung (mittel):** Netzwerkfehler führen nun zum deaktivierten Zustand bis zum erneuten Verbinden. Die Startverbindung gilt erst nach den initialen Abfragen als aufgebaut.
4. **Fehlerdetails unsichtbar (mittel):** `self.status` war zuvor an kein sichtbares Element gebunden. Eine Statusleiste zeigt Meldungen nun an.
5. **Ungültige Geräteantwort konnte Event-Verarbeitung unterbrechen (mittel):** Fehlerhafte Zahlenfelder werden jetzt abgefangen; die Verarbeitung wird erneut geplant.
6. **Erfolgreiche NAS-Speicherung bei Cachefehler fälschlich als fehlgeschlagen gemeldet (mittel):** Ein Fehler bei der optionalen Cache-Aktualisierung ändert nicht mehr die Bewertung der bereits erfolgreichen NAS-Speicherung.
7. **Versions-/Abhängigkeitsdrift (mittel):** Für `lg-bartalk` wurde 0.1.3 festgelegt; für die weiteren Bibliotheken sind unterstützte Versionsbereiche angegeben. Diese Kombination ist noch nicht unter Windows verifiziert.
8. **Profilcache nicht in Git ignoriert (gering):** Cache-Datei und temporäre Dateien werden jetzt ignoriert.

## Statische Sicherheitseinschätzung

Keine hartcodierten privaten IP-Adressen, NAS-Freigaben, Tokens, Passwörter oder API-Schlüssel im geprüften V16-Programm. Keine Hinweise auf Telemetrie oder externe Web-Dienste im Programmcode. Die tatsächliche Bibliothek `lg-bartalk` verwendet einen Soundbar-Protokollschlüssel; dies ist keine Authentifizierung. Deshalb nur im vertrauenswürdigen LAN verwenden.

## Automatisierte Prüfung

Die Datei `tests/test_storage.py` enthält mit Mock-Abhängigkeiten ausführbare Tests zu Konfiguration, Profilen, Offline-NAS, Datenfehlern, Import und Eventfehlern. Ausführen mit `python -m pytest -q tests/test_storage.py`. Der Python-Quellcode besteht den Syntaxcheck mit `python -m py_compile Tonmaschine.py`.

## Was noch NICHT verifiziert ist

- Echte Verbindung und Steuerung einer LG S95QR einschließlich aller Kanalpegel.
- Windows-UI in der Zielumgebung und Kommunikation mit dem installierten `lg-bartalk`.
- Kompilieren und Ausführen der EXE mit PyInstaller.
- Kompilieren und Ausführen des Inno-Setup-Installers auf Windows, insbesondere Upgrade und Deinstallation.
- Ein reales SMB/NAS mit Netzwerkunterbrechung und gleichzeitigen Schreibzugriffen verschiedener PCs.
- Vollständige Lizenzhinweise aller tatsächlich im Windows-Binary gebündelten Abhängigkeiten. Siehe `THIRD_PARTY_NOTICES.md`.

**Freigabestatus:** Der Quellcode kann als überprüfter Entwicklungsstand veröffentlicht werden. Für einen als funktionsfähig beworbenen Windows-Installer sollten die oben genannten realen Windows-/Soundbar-Tests vorab stattfinden.

**Teststand:** 14 von 14 automatisierten Tests bestanden (einschließlich paralleler NAS-Schreibzugriffe auf einem simulierten gemeinsamen Ordner). Die Installer-Prüfung ist statisch und ersetzt keinen Windows-Installationstest.
