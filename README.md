# Tonmaschine

**Eine kostenlose, unabhängige Windows-Anwendung zur Steuerung der LG S95QR Soundbar.**

Tonmaschine ist ein privates Open-Source-Projekt zur komfortablen Steuerung ausgewählter Funktionen der LG S95QR Soundbar über das lokale Netzwerk.

Die Anwendung ermöglicht die Anpassung von Klangeinstellungen und die Speicherung individueller Klangprofile, die sich bei Bedarf wiederherstellen lassen.

## Funktionen

- Steuerung ausgewählter Soundbar-Einstellungen über das lokale Netzwerk
- Auswahl verschiedener Klangmodi
- Individuelle Anpassung der Lautsprecherpegel
- Steuerung von Surround, Night Time und Dynamic Range Control (DRC)
- Speichern, Laden und Löschen eigener Klangprofile
- Wahlweise lokale Speicherung oder Speicherung auf einem NAS
- Windows-Installationsassistent zur Einrichtung der Verbindung und des Speicherorts

## Voraussetzungen

- Windows-PC
- LG S95QR Soundbar
- Netzwerkverbindung zwischen PC und Soundbar
- Für die optionale NAS-Speicherung: Zugriff auf eine geeignete Netzwerkfreigabe

Die Anwendung wurde speziell für die LG S95QR entwickelt. Die Kompatibilität mit anderen Geräten wird nicht zugesichert.

## Installation und Nutzung

Die Anwendung kann nach Bereitstellung eines Installationspakets über den Bereich **Releases** dieses GitHub-Repositories heruntergeladen werden.

Bei der Erstinstallation werden die IP-Adresse der Soundbar und der gewünschte Speicherort für Klangprofile abgefragt. Bei Updates bleibt eine bereits vorhandene Konfiguration unverändert. Einstellungen lassen sich innerhalb der Anwendung ändern. Wenn eine ältere V15-Konfiguration noch keine Soundbar-IP enthält, muss diese einmalig in der App eingetragen werden.

Die Nutzung erfolgt auf eigene Verantwortung.

**Version 1.0.0:** Die automatisierten Tests für die Profilverwaltung, Konfigurationsübernahme und mehrere Fehlersituationen wurden erfolgreich ausgeführt. Die Installation unter Windows, die Verbindung zur LG S95QR sowie das Laden und Speichern von Klangprofilen wurden praktisch erfolgreich getestet. Weitere technische Details stehen in [PRUEFBERICHT.md](PRUEFBERICHT.md).

### Aus dem Quellcode bauen

Python 3.10 oder neuer wird benötigt. Für die Windows-EXE zusätzlich `pyinstaller` installieren:

```powershell
py -m pip install -r requirements.txt pyinstaller
py -m PyInstaller --noconfirm --clean --onefile --windowed --name Tonmaschine --icon Tonmaschine.ico --collect-all customtkinter --collect-all Crypto Tonmaschine.py
```

Anschließend `Tonmaschine.iss` mit Inno Setup kompilieren. Der Installer wird in `Installer` erzeugt. Vor der öffentlichen Weitergabe eines kompilierten Installers sind die tatsächlich gebündelten Drittlizenzen und erforderlichen Hinweise beizufügen (siehe [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

Die automatisierten Tests lassen sich mit `py -m pip install -r requirements-dev.txt` und anschließend `py -m pytest -q tests` ausführen.

## Unabhängiges Hobbyprojekt

Tonmaschine wurde von Steffen Schermer als privates, nichtkommerzielles Hobbyprojekt entwickelt und wird kostenlos zur Verfügung gestellt.

Das Projekt steht in keiner geschäftlichen oder sonstigen offiziellen Verbindung zu LG Electronics.

Tonmaschine ist weder ein offizielles Produkt von LG Electronics noch wird die Anwendung von LG Electronics entwickelt, unterstützt, geprüft oder zertifiziert.

Alle genannten Produktnamen und Marken gehören ihren jeweiligen Rechteinhabern.

## Haftungsausschluss

Die Software wird kostenlos und ohne Zusicherung bestimmter Eigenschaften oder Funktionen bereitgestellt.

Es wird keine Gewähr für die Fehlerfreiheit, Zuverlässigkeit, Sicherheit, Verfügbarkeit oder Kompatibilität der Anwendung übernommen.

Die Nutzung erfolgt auf eigenes Risiko. Insbesondere wird keine Gewähr dafür übernommen, dass die Anwendung mit sämtlichen Firmware-Versionen oder Gerätekonfigurationen ordnungsgemäß funktioniert.

Soweit gesetzlich zulässig, ist eine Haftung des Entwicklers für Schäden ausgeschlossen, die unmittelbar oder mittelbar durch die Installation, Nutzung, Fehlfunktion oder Nichtverfügbarkeit der Software entstehen.

Dies umfasst insbesondere Datenverluste, fehlerhafte Geräteeinstellungen, Betriebsunterbrechungen sowie sonstige materielle oder immaterielle Schäden.

**Zwingende gesetzliche Haftungsansprüche bleiben unberührt.** Insbesondere gelten die gesetzlichen Bestimmungen zur Haftung für Vorsatz, grobe Fahrlässigkeit und Schäden an Leben, Körper oder Gesundheit.

Der Haftungsausschluss ergänzt die Bestimmungen der MIT-Lizenz und soll diese nicht ersetzen oder einschränken.

## Sicherheit und Datenschutz

Tonmaschine ist für die Verwendung in einem vertrauenswürdigen lokalen Netzwerk vorgesehen.

Die Anwendung sollte nicht unmittelbar aus dem öffentlichen Internet erreichbar gemacht werden. Es wird empfohlen, die Soundbar und gegebenenfalls verwendete Netzwerkfreigaben angemessen abzusichern.

Die Anwendung benötigt für ihre grundlegende Funktion keine Registrierung und kein Benutzerkonto bei einem vom Entwickler betriebenen Dienst.

Die Konfiguration und die Klangprofile werden entsprechend dem gewählten Speicherort lokal oder auf einer vom Nutzer angegebenen Netzwerkfreigabe abgelegt.

Es wird keine Garantie für die Sicherheit der verwendeten Netzwerkverbindungen oder der angeschlossenen Geräte übernommen.

## Weiterentwicklung und Unterstützung

Tonmaschine wird freiwillig und ohne vertragliche Verpflichtungen entwickelt und bereitgestellt.

Es besteht kein Anspruch auf:

- Fehlerbehebungen oder Sicherheitsupdates
- Technischen Support
- Weiterentwicklung oder Pflege
- Kompatibilität mit zukünftigen Firmware-Versionen
- Dauerhafte Verfügbarkeit der Software

Bekannt gewordene Probleme können über die GitHub-Issue-Funktion gemeldet werden. Eine Bearbeitung oder Behebung kann nicht zugesichert werden.

## Lizenz

Tonmaschine wird unter der **MIT License** veröffentlicht.

Copyright (c) 2026 Steffen Schermer

Die MIT-Lizenz erlaubt die kostenlose und kommerzielle Nutzung, Veränderung und Weiterverbreitung der Software unter der Voraussetzung, dass der ursprüngliche Copyright-Vermerk und der Lizenztext entsprechend den Lizenzbedingungen erhalten bleiben.

Der vollständige Lizenztext befindet sich in der Datei [LICENSE](LICENSE).

---

**Entwickler:** Steffen Schermer  
**Projektart:** Privates, nichtkommerzielles Open-Source-Projekt  
**Lizenz:** MIT License

**Entwicklung: 100 % Vibe Coding – KI-generierter Code, vom Projektinitiator getestet und weiterentwickelt.**
