# Drittanbieter-Lizenzhinweise – Tonmaschine v1.0.1

Der eigene Tonmaschine-Quellcode steht unter der MIT-Lizenz (Datei `LICENSE`).
Für fremde Softwarebestandteile, Schriftarten und Laufzeitbibliotheken gelten
deren jeweilige Lizenzen. Die **vollständigen Originaltexte** werden ab
v1.0.1 im Ordner `third_party_licenses/` im Repository und im Verzeichnis
`licenses/` der installierten Anwendung mitgeliefert.

## Bestandteile des Windows-Installationspakets

Die Versionen entsprechen dem erfolgreichen Windows-Build von v1.0.0 und sind
für den Korrekturbau v1.0.1 ausdrücklich festgeschrieben.

| Bestandteil | Version | Lizenz / Lizenzdateien |
| --- | --- | --- |
| [lg-bartalk](https://github.com/skynet01/lg-bartalk) | 0.1.3 | Apache 2.0: `lg-bartalk_APACHE-2.0.txt`; zusätzlich `lg-bartalk_NOTICE.txt` |
| [CustomTkinter](https://github.com/TomSchimansky/CustomTkinter) | 6.0.0 | MIT: `customtkinter_MIT.txt`, Copyright (c) 2023 Tom Schimansky |
| [PyCryptodome](https://github.com/Legrandin/pycryptodome) | 3.24.1 | BSD-2-Clause und Public Domain: `pycryptodome_LICENSE.rst`; Copyright bei den jeweiligen Urhebern |
| [darkdetect](https://github.com/albertosottile/darkdetect) | 0.8.0 | BSD-3-Clause: `darkdetect_BSD-3-Clause.txt`, Copyright (c) 2019 Alberto Sottile |
| [packaging](https://github.com/pypa/packaging) | 26.3 | Wahlweise Apache 2.0 oder BSD-3-Clause: `packaging_LICENSE.APACHE.txt`, `packaging_LICENSE.BSD.txt` |
| [Roboto](https://github.com/googlefonts/roboto-2) | 2.137 (2017) | Apache 2.0: `Roboto_APACHE-2.0.txt`, Copyright 2011 Google Inc. |
| [Python](https://github.com/python/cpython) | 3.12.10 | Python/PSF-Lizenzverbund: `python-3.12.10_LICENSE.txt` |
| [Tcl](https://github.com/tcltk/tcl) | 8.6 | `Tcl_8.6_license.terms` |
| [Tk](https://github.com/tcltk/tk) | 8.6 | `Tk_8.6_license.terms` |
| [PyInstaller](https://github.com/pyinstaller/pyinstaller) | 6.22.3 | GPL mit Bootloader-Ausnahme und Apache-2.0-lizenzierte Laufzeit-Hooks: `pyinstaller_6.22.3_COPYING.txt` |
| [Inno Setup](https://github.com/jrsoftware/issrc) | 6.x | Inno Setup License: `inno-setup_LICENSE.txt` |

## Erläuterungen und Urhebervermerke

**lg-bartalk / python-temescal:** Das Projekt enthält Protokollkonstanten,
Befehlsdaten und Zuordnungstabellen, die aus dem Apache-2.0-Projekt
[python-temescal](https://github.com/google/python-temescal) abgeleitet sind.
Der originale `NOTICE`-Text von lg-bartalk ist unverändert in
`lg-bartalk_NOTICE.txt` beigefügt. Darin steht Copyright 2018 Google LLC.
Die Upstream-Datei `LICENSE.temescal` ist mit der beigefügten Apache-2.0-Lizenz
identisch (beide tragen im v0.1.3-Repository denselben Git-Objekthash).

**Roboto:** Die bei CustomTkinter enthaltenen Dateien
`Roboto-Regular.ttf` und `Roboto-Medium.ttf` tragen in ihren
eingebetteten Schrift-Metadaten „Copyright 2011 Google Inc.“,
„Version 2.137; 2017“ und „Licensed under the Apache License, Version 2.0“.
Daher ist die Apache-2.0-Lizenz und nicht die Lizenz späterer Roboto-Versionen
beigelegt. Die Schrift `CustomTkinter_shapes_font.otf` ist ein Bestandteil
des CustomTkinter-Projekts und wird mit dessen Lizenz dokumentiert.

**Python / Tcl / Tk:** Das gebündelte Python-Programm und die Tkinter-
Laufzeitkomponenten behalten ihre eigenen Lizenzbestimmungen.
Die Dokumentation ist unabhängig von der MIT-Lizenz von Tonmaschine.

**PyInstaller:** Die Bootloader-Ausnahme erlaubt die Weitergabe der aus
eigenem Code gebauten EXE unter einer passenden eigenen Lizenz.
Die beigelegte Datei enthält auch die Lizenz für eingebettete Laufzeit-Hooks.

**Inno Setup:** Das Windows-Installationsprogramm wurde mit Inno Setup erzeugt.
Copyright bei Jordan Russell und Martijn Laan. Das Projekt ist davon unabhängig.

Die GitHub-Release-Dateien `BUILD_DEPENDENCIES.txt` und `SHA256SUMS.txt`
dokumentieren die tatsächlich installierten Python-Pakete beziehungsweise
die SHA-256-Prüfsumme der veröffentlichten Installationsdatei.

## Abgrenzung

LG und S95QR dienen nur zur Bezeichnung der vorgesehenen Kompatibilität.
Tonmaschine ist nicht von LG herausgegeben, geprüft oder unterstützt.
Diese Hinweise stellen keine Garantie für das Nichtbestehen anderer
Schutzrechte dar. Bei geänderten Abhängigkeiten müssen auch die
Lizenzunterlagen geprüft und aktualisiert werden.
