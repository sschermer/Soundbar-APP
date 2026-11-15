"""Tonmaschine Audiosignal-Diagnose - read-only LG soundbar protocol capture.

This diagnostic only invokes get_info/get_func/get_eq/get_play in lg-bartalk.
It does not change any soundbar settings and omits personal device identifiers
from the exported report. Intended for local trusted networks only.
"""

from __future__ import annotations

import datetime as dt
import ipaddress
import json
import os
import queue
import re
import threading
from collections import deque
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, scrolledtext, ttk

APP_CONFIG = Path(os.environ.get("APPDATA", str(Path.home()))) / "Tonmaschine" / "config.json"
PROTOCOL_MESSAGES = frozenset(("SPK_LIST_VIEW_INFO", "FUNC_VIEW_INFO", "EQ_VIEW_INFO", "PLAY_INFO"))
SENSITIVE_PARTS = (
    "password", "passwd", "secret", "token", "credential", "uuid", "serial",
    "mac_addr", "mac_address", "s_mac", "s_ipv", "ip_addr", "ipv4", "ipv6",
    "ssid", "user_name", "bt_name", "device_id", "client_id", "ip_address",
    "track_title", "title", "artist", "album", "thumbnail", "cover_art", "artwork",
    "play_url", "stream_url", "media_url", "file_path", "hostname",
)
IP_PATTERN = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")
MAC_PATTERN = re.compile(r"\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\b")

INPUT_NAMES = {
    0: "Wi-Fi", 1: "Bluetooth", 4: "Optisch", 6: "HDMI",
    7: "HDMI ARC", 10: "HDMI 2", 11: "HDMI 3", 15: "Optisch/ARC",
    18: "USB", 19: "USB", 20: "HDMI eARC",
}
MODE_NAMES = {
    0: "Standard", 6: "Musik", 7: "Cinema", 14: "Bass Blast",
    15: "Dolby Atmos (Moduskennung)", 18: "DTS:X (Moduskennung)",
    19: "AI Sound Pro", 21: "Sport", 22: "Game", 26: "Clear Voice Pro",
}
TEST_SCENARIOS = (
    "Stereo (Testinhalt)",
    "5.1 (Testinhalt)",
    "Dolby Atmos (Testinhalt)",
    "DTS:X (Testinhalt)",
    "Anderer Testinhalt",
)


def now_iso() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


def configured_ip() -> str:
    """Read only the IP from Tonmaschine settings; never save or alter config."""
    try:
        config = json.loads(APP_CONFIG.read_text(encoding="utf-8"))
        address = config.get("soundbar_ip", "") if isinstance(config, dict) else ""
        return str(ipaddress.IPv4Address(address)) if address else ""
    except (OSError, ValueError, TypeError, ipaddress.AddressValueError):
        return ""


def scrub_text(text: object) -> str:
    value = str(text)[:512]
    value = IP_PATTERN.sub("[IP ausgeblendet]", value)
    value = MAC_PATTERN.sub("[MAC ausgeblendet]", value)
    return value


def sensitive_key(name: object) -> bool:
    lower = str(name).lower()
    return any(part in lower for part in SENSITIVE_PARTS)


def sanitize(value: object, depth: int = 0) -> object:
    """Keep protocol fields, but remove identifiers and bound report size."""
    if depth > 4:
        return "[verschachtelte Daten ausgelassen]"
    if isinstance(value, dict):
        return {
            str(key)[:80]: sanitize(item, depth + 1)
            for key, item in list(value.items())[:150]
            if not sensitive_key(key)
        }
    if isinstance(value, (list, tuple)):
        return [sanitize(item, depth + 1) for item in value[:80]]
    if isinstance(value, str):
        return scrub_text(value)
    if isinstance(value, (bool, int, float)) or value is None:
        return value
    return scrub_text(repr(value))


def parse_response(response: object) -> tuple[str, dict] | None:
    if not isinstance(response, dict) or response.get("result") != "ok":
        return None
    name = response.get("msg")
    if name not in PROTOCOL_MESSAGES:
        return None
    data = response.get("data")
    if not isinstance(data, dict):
        return None
    return name, sanitize(data)


def state_summary(latest: dict) -> dict:
    """No 2.0/5.1 guessing from codec or sound-mode fields."""
    speaker = latest.get("SPK_LIST_VIEW_INFO", {})
    input_state = latest.get("FUNC_VIEW_INFO", {})
    equalizer = latest.get("EQ_VIEW_INFO", {})
    audio = speaker.get("s_audio_source", "Nicht von der Soundbar gemeldet")
    input_code = input_state.get("i_curr_func", speaker.get("i_curr_func"))
    eq_code = equalizer.get("i_curr_eq", speaker.get("i_curr_eq"))
    try:
        input_name = INPUT_NAMES.get(int(input_code), "unbekannter Eingang")
    except (ValueError, TypeError):
        input_name = "unbekannter Eingang"
    try:
        mode_name = MODE_NAMES.get(int(eq_code), "unbekannter Klangmodus")
    except (ValueError, TypeError):
        mode_name = "unbekannter Klangmodus"
    return {
        "audio_source_rohwert": audio,
        "eingang_rohcode": input_code,
        "eingang_bezeichnung_nur_zur_orientierung": input_name,
        "klangmodus_rohcode": eq_code,
        "klangmodus_bezeichnung_nur_zur_orientierung": mode_name,
        "hinweis": "Klangmodus und Eingang beweisen NICHT das Audio-Kanalformat (2.0, 5.1, Atmos).",
    }


class SignalDiagnose:
    POLL_SECONDS = 4.0

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        root.title("Tonmaschine – Audiosignal-Diagnose")
        root.geometry("870x710")
        root.minsize(750, 590)
        self.ip_var = tk.StringVar(value=configured_ip())
        self.connection_var = tk.StringVar(value="Nicht verbunden")
        self.audio_var = tk.StringVar(value="Noch kein Signalwert empfangen")
        self.input_var = tk.StringVar(value="–")
        self.mode_var = tk.StringVar(value="–")
        self.updated_var = tk.StringVar(value="–")
        self.case_var = tk.StringVar(value=TEST_SCENARIOS[0])
        self.results: deque[dict] = deque(maxlen=1000)
        self.snapshots: list[dict] = []
        self.latest: dict[str, dict] = {}
        self.latest_by_msg: dict[str, dict] = {}
        self.events: queue.Queue[tuple[int, str, object]] = queue.Queue()
        self.stop_event: threading.Event | None = None
        self.refresh_event: threading.Event | None = None
        self.session = 0
        self.running = False
        self._build_ui()
        self.root.after(120, self._drain_events)
        self.root.protocol("WM_DELETE_WINDOW", self._close)

    def _build_ui(self) -> None:
        outer = ttk.Frame(self.root, padding=15)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="Audiosignal-Diagnose für LG S95QR", font=("Segoe UI", 17, "bold")).pack(anchor="w")
        ttk.Label(
            outer,
            text="Nur Leseabfragen. Keine Klangeinstellungen werden verändert. "
                 "Die vorhandene Tonmaschine-Konfiguration bleibt unverändert.",
            wraplength=830,
        ).pack(anchor="w", pady=(4, 11))

        conn = ttk.Frame(outer)
        conn.pack(fill="x")
        ttk.Label(conn, text="IP der Soundbar:").pack(side="left")
        self.ip_entry = ttk.Entry(conn, width=19, textvariable=self.ip_var)
        self.ip_entry.pack(side="left", padx=(8, 11))
        self.start_button = ttk.Button(conn, text="Verbinden", command=self._start)
        self.start_button.pack(side="left", padx=(0, 5))
        self.stop_button = ttk.Button(conn, text="Trennen", command=self._stop, state="disabled")
        self.stop_button.pack(side="left", padx=(0, 5))
        self.refresh_button = ttk.Button(conn, text="Jetzt abfragen", command=self._refresh, state="disabled")
        self.refresh_button.pack(side="left")
        ttk.Label(outer, textvariable=self.connection_var, foreground="#2563a5").pack(anchor="w", pady=(6, 9))

        values = ttk.LabelFrame(outer, text="Von der Soundbar gemeldete Werte", padding=12)
        values.pack(fill="x", pady=(0, 10))
        for i, (label, variable) in enumerate((
            ("Audiosignal (s_audio_source):", self.audio_var),
            ("Aktiver Eingang:", self.input_var),
            ("Klangmodus (nicht Signalformat!):", self.mode_var),
            ("Letztes Statuspaket:", self.updated_var),
        )):
            ttk.Label(values, text=label).grid(row=i, column=0, sticky="w", pady=3, padx=(0, 16))
            ttk.Label(values, textvariable=variable, font=("Segoe UI", 10, "bold") if i == 0 else ("Segoe UI", 10)).grid(
                row=i, column=1, sticky="w", pady=3)
        values.columnconfigure(1, weight=1)

        instructions = (
            "So testen: 1. Verbinden. 2. Einen bekannten Stereo-Titel abspielen und ca. 5 Sekunden warten. "
            "3. 'Stereo' auswählen und Messpunkt markieren. 4. Mit 5.1 und Atmos wiederholen. "
            "5. Protokoll speichern und hier im Chat hochladen."
        )
        ttk.Label(outer, text=instructions, wraplength=820).pack(anchor="w", pady=(0, 9))

        measure = ttk.Frame(outer)
        measure.pack(fill="x", pady=(0, 11))
        ttk.Label(measure, text="Gerade abgespielter Testinhalt:").pack(side="left")
        self.case_select = ttk.Combobox(measure, state="readonly", textvariable=self.case_var, values=TEST_SCENARIOS, width=27)
        self.case_select.pack(side="left", padx=(8, 8))
        self.mark_button = ttk.Button(measure, text="Messpunkt markieren", command=self._mark, state="disabled")
        self.mark_button.pack(side="left", padx=(0, 7))
        ttk.Button(measure, text="Protokoll speichern", command=self._save).pack(side="left")

        ttk.Label(outer, text="Liveprotokoll (ohne IP- und Gerätekennungen):").pack(anchor="w")
        self.log = scrolledtext.ScrolledText(outer, height=13, wrap="word", state="disabled", font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, pady=(5, 10))
        ttk.Label(
            outer,
            text="Wichtig: 'Dolby Audio' beweist nicht 5.1. Die Testbezeichnungen werden von dir gesetzt, "
                 "nicht automatisch erkannt. Protokolldaten werden nur auf deinem PC gespeichert.",
            wraplength=820,
        ).pack(anchor="w")

    def _append_log(self, message: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", f"{dt.datetime.now().strftime('%H:%M:%S')}  {scrub_text(message)}\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _record(self, event: str, **kwargs: object) -> None:
        self.results.append({"zeit": now_iso(), "ereignis": event, **kwargs})

    def _start(self) -> None:
        address = self.ip_var.get().strip()
        try:
            ipaddress.IPv4Address(address)
        except (ValueError, ipaddress.AddressValueError):
            messagebox.showerror("Ungültige IP-Adresse", "Bitte eine gültige IPv4-Adresse der Soundbar eingeben.")
            return
        if self.running:
            return
        self.running = True
        self.session += 1
        sid = self.session
        self.latest = {}
        self.latest_by_msg = {}
        self.results.clear()
        self.snapshots.clear()
        self.stop_event = threading.Event()
        self.refresh_event = threading.Event()
        self.ip_entry.configure(state="disabled")
        self.start_button.configure(state="disabled")
        self.stop_button.configure(state="normal")
        self.refresh_button.configure(state="normal")
        self.mark_button.configure(state="normal")
        self.connection_var.set("Verbinde ...")
        self.audio_var.set("Warte auf Protokolldaten ...")
        self.input_var.set("–")
        self.mode_var.set("–")
        self.updated_var.set("–")
        self._append_log("Verbindungsaufbau gestartet; ausschließlich GET-Abfragen")
        self._record("diagnose_start")
        threading.Thread(target=self._worker, args=(sid, address, self.stop_event, self.refresh_event), daemon=True).start()

    def _worker(self, sid: int, address: str, stop: threading.Event, refresh: threading.Event) -> None:
        client = None
        try:
            from lg_bartalk import LGSoundbarClient

            def callback(response: object) -> None:
                self.events.put((sid, "response", response))

            client = LGSoundbarClient(address, callback=callback)
            self.events.put((sid, "connected", None))
            while not stop.is_set():
                for method_name in ("get_info", "get_func", "get_eq", "get_play"):
                    if stop.is_set():
                        break
                    getattr(client, method_name)()
                if stop.is_set():
                    break
                refresh.wait(self.POLL_SECONDS)
                refresh.clear()
        except Exception as exc:
            if not stop.is_set():
                self.events.put((sid, "error", scrub_text(f"{type(exc).__name__}: {exc}")))
        finally:
            if client is not None:
                try:
                    client.close()
                except Exception:
                    pass
            self.events.put((sid, "closed", None))

    def _stop(self) -> None:
        if self.running and self.stop_event is not None:
            self.connection_var.set("Trenne Verbindung ...")
            self.stop_event.set()
            if self.refresh_event is not None:
                self.refresh_event.set()
            self.stop_button.configure(state="disabled")
            self.refresh_button.configure(state="disabled")

    def _refresh(self) -> None:
        if self.running and self.refresh_event is not None:
            self.refresh_event.set()
            self._append_log("Manuelle Leseabfrage angefordert")

    def _drain_events(self) -> None:
        for _ in range(80):
            try:
                sid, kind, value = self.events.get_nowait()
            except queue.Empty:
                break
            if sid != self.session:
                continue
            if kind == "connected":
                self.connection_var.set("Verbunden – Statusdaten werden alle 4 Sekunden abgefragt")
                self._append_log("Verbindung hergestellt")
                self._record("verbunden")
            elif kind == "response":
                parsed = parse_response(value)
                if parsed is None:
                    continue
                name, data = parsed
                self.updated_var.set(dt.datetime.now().strftime("%H:%M:%S"))
                if self.latest_by_msg.get(name) != data:
                    self.latest_by_msg[name] = data
                    self._record("protokollantwort", nachricht=name, daten=data)
                    self._append_log(f"{name}: {json.dumps(data, ensure_ascii=False)}")
                self.latest[name] = data
                self._update_status()
            elif kind == "error":
                self.connection_var.set("Fehler bei der Verbindung")
                self._append_log(f"Fehler: {value}")
                self._record("fehler", meldung=value)
            elif kind == "closed":
                self.running = False
                self.ip_entry.configure(state="normal")
                self.start_button.configure(state="normal")
                self.stop_button.configure(state="disabled")
                self.refresh_button.configure(state="disabled")
                self.mark_button.configure(state="disabled")
                if self.connection_var.get() != "Fehler bei der Verbindung":
                    self.connection_var.set("Getrennt – Messwerte können gespeichert werden")
                self._record("verbindung_beendet")
                self._append_log("Verbindung beendet")
        self.root.after(120, self._drain_events)

    def _update_status(self) -> None:
        summary = state_summary(self.latest)
        audio = summary["audio_source_rohwert"]
        self.audio_var.set(str(audio))
        code = summary["eingang_rohcode"]
        self.input_var.set(f"{summary['eingang_bezeichnung_nur_zur_orientierung']} (Code {code})" if code is not None else "Noch nicht empfangen")
        code = summary["klangmodus_rohcode"]
        self.mode_var.set(f"{summary['klangmodus_bezeichnung_nur_zur_orientierung']} (Code {code})" if code is not None else "Noch nicht empfangen")

    def _mark(self) -> None:
        if not self.latest:
            messagebox.showwarning("Noch keine Daten", "Bitte erst verbinden und Statusdaten empfangen.")
            return
        sample = {
            "zeit": now_iso(),
            "manuell_gewaehlter_testinhalt": self.case_var.get(),
            "status": state_summary(self.latest),
            "letzte_protokollantworten": json.loads(json.dumps(self.latest, ensure_ascii=False)),
        }
        self.snapshots.append(sample)
        self._record("manueller_messpunkt", testinhalt=sample["manuell_gewaehlter_testinhalt"], status=sample["status"])
        self._append_log(f"MESSPUNKT: {sample['manuell_gewaehlter_testinhalt']} | Audiosignal: {sample['status']['audio_source_rohwert']}")

    def _save(self) -> None:
        if not self.results:
            messagebox.showwarning("Keine Messwerte", "Bitte zunächst verbinden und Messwerte sammeln.")
            return
        filename = "Tonmaschine_Signaldiagnose_" + dt.datetime.now().strftime("%Y%m%d_%H%M%S") + ".json"
        path = filedialog.asksaveasfilename(
            title="Diagnoseprotokoll speichern", defaultextension=".json", initialfile=filename,
            filetypes=[("JSON-Diagnoseprotokoll", "*.json")],
        )
        if not path:
            return
        report = {
            "programm": "Tonmaschine Audiosignal-Diagnose 1.0",
            "erstellt": now_iso(),
            "geraetekennungen_und_ip": "absichtlich nicht enthalten",
            "wichtig": "Die Testinhalt-Bezeichnungen sind manuelle Angaben des Nutzers, keine automatischen Audiokanal-Messwerte.",
            "zusammenfassung": state_summary(self.latest),
            "messpunkte": self.snapshots,
            "ereignisse": list(self.results),
        }
        try:
            Path(path).write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        except OSError as exc:
            messagebox.showerror("Speichern fehlgeschlagen", str(exc))
            return
        messagebox.showinfo("Gespeichert", "Protokoll gespeichert. Bitte die JSON-Datei hier im Chat hochladen.")

    def _close(self) -> None:
        if self.stop_event is not None:
            self.stop_event.set()
        if self.refresh_event is not None:
            self.refresh_event.set()
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    SignalDiagnose(root)
    root.mainloop()


if __name__ == "__main__":
    main()