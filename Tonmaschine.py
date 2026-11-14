"""Tonmaschine V16 - LG S95QR local controller. Requires lg-bartalk.
Only uses named public methods. Unknown parameter signatures disable the control.
"""
import inspect
import os
import sys
import tempfile
import uuid
import json
from pathlib import Path
import time
import queue
import threading
import tkinter as tk
from tkinter import messagebox, filedialog
try:
    import customtkinter as ctk
except ImportError as exc:
    raise SystemExit("CustomTkinter fehlt. Bitte einmal ausfuehren: py -m pip install customtkinter") from exc
from lg_bartalk import LGSoundbarClient

IP_DEFAULT = ''
DEFAULT_NAS_FOLDER = ''
APP_DIR = Path(os.environ.get('APPDATA', str(Path.home()))) / 'Tonmaschine'
CONFIG_FILE = APP_DIR / 'config.json'
CACHE_FILE = APP_DIR / 'Tonmaschine_Profile_cache.json'
LOCAL_PROFILE_FILE = APP_DIR / 'Tonmaschine_Profile.json'
LEGACY_PROFILE_FILE = Path(sys.executable if getattr(sys, 'frozen', False) else __file__).with_name('Tonmaschine_Profile.json')
PROFILE_FILENAME = 'Tonmaschine_Profile.json'


def read_json_dict(path):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(data, dict):
        raise ValueError('Ungültiges JSON-Format (Objekt erwartet).')
    return data


def atomic_write_json(path, data):
    path = Path(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(path)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass

MODES = {'AI Sound Pro': 19, 'Standard': 0, 'Cinema': 7, 'Clear Voice Pro': 26, 'Sports': 21, 'Music (Meridian)': 6, 'Game': 22, 'Bass Blast': 14}
MODE_BY_ID = {v:k for k,v in MODES.items()}
LEVELS = [
    ('Subwoofer', 'i_woofer_level', -15, 6, 15, 'set_woofer_level', True),
    ('Center', 'i_center_level', -6, 6, 6, 'set_center_level', True),
    ('Front-Höhe', 'i_top_level', -6, 6, 6, 'set_top_level', False),
    ('Front-Seite', 'i_side_level', -6, 6, 6, None, False),
    ('Rear', 'i_rear_level', -6, 6, 6, 'set_rear_level', False),
    ('Rear-Höhe', 'i_rear_top_level', -6, 6, 6, None, False),
    ('Rear-Seite', 'i_rear_side_level', -6, 6, 6, None, False),
]


class Controller:
    def __init__(self, root):
        self.root = root
        self.root.title('Tonmaschine - LG S95QR')
        self.root.geometry('650x670')
        self.expanded = False
        self.mode_buttons = {}
        self.profile_buttons = {}
        self.profile_busy = False
        self.active_profile = None
        self.root.minsize(625, 575)
        self.config = self.load_config()
        self.ip = tk.StringVar(value=self.config.get('soundbar_ip', IP_DEFAULT))
        self.status = tk.StringVar(value='Nicht verbunden')
        self.events = queue.Queue()
        self.client = None
        self.lock = threading.RLock()
        self.ready = False
        self.updating = False
        self.pending = {}
        self.after_ids = {}
        self.settings = {}
        self.volume = tk.IntVar(value=0)
        self.volume_display = tk.StringVar(value='–')
        self.level_controls = {}
        self.toggle_controls = {}
        self.eq_display = tk.StringVar(value='–')
        self.mode_var = tk.StringVar(value='–')
        self.profile_var = tk.StringVar()
        self.profile_name = tk.StringVar()
        self.storage_mode = tk.StringVar(value=self.config.get('storage_mode', 'local'))
        self.nas_folder = tk.StringVar(value=self.config.get('nas_folder', DEFAULT_NAS_FOLDER))
        self.nas_state = tk.StringVar(value='NAS: noch nicht geprüft')
        self.profiles = {}
        self.nas_online = False
        self.load_profiles()
        self.last_mode = None
        self.mode_busy = False
        self.last_settings = {}
        self.bass_display = tk.StringVar(value='–')
        self.treble_display = tk.StringVar(value='–')
        self.build_ui()
        self.root.after(80, self.poll)
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        if self.ip.get().strip():
            self.connect()
        else:
            self.show_tab('config')

    def build_ui(self):
        BG = '#171B23'
        PANEL = '#232936'
        BTN = '#303746'
        BLUE = '#4265A7'
        TEXT = '#F0F4FA'
        MUTED = '#A7B4C8'
        self.root.configure(fg_color=BG)

        top = ctk.CTkFrame(self.root, fg_color='transparent')
        top.pack(fill='x', padx=18, pady=(15, 5))
        ctk.CTkLabel(top, text='◉  TONMASCHINE', font=('Segoe UI', 21, 'bold'),
                     text_color=TEXT).pack(side='left')
        ctk.CTkLabel(top, text='LG S95QR  ·  V16', text_color=MUTED,
                     font=('Segoe UI', 12)).pack(side='left', padx=(12, 0))
        # Stable connection indicator; transient actions no longer occupy the header.
        self.connection_text = tk.StringVar(value='Nicht verbunden')
        self.connection_label = ctk.CTkLabel(top, textvariable=self.connection_text,
            text_color='#A7B4C8', font=('Segoe UI', 12))
        self.connection_label.pack(side='right', padx=(8, 0))

        # Tabs and channel toggle share ONE toolbar; no extra tab header row.
        toolbar = ctk.CTkFrame(self.root, fg_color='transparent', height=36)
        toolbar.pack(fill='x', padx=20, pady=(2, 5))
        self.sound_tab_button = ctk.CTkButton(toolbar, text='Soundeinstellungen', width=150,
            height=32, fg_color=BLUE, command=lambda: self.show_tab('sound'))
        self.sound_tab_button.pack(side='left', padx=(0, 5))
        self.config_tab_button = ctk.CTkButton(toolbar, text='Konfiguration', width=120,
            height=32, fg_color=BTN, command=lambda: self.show_tab('config'))
        self.config_tab_button.pack(side='left')
        self.expand_button = ctk.CTkButton(toolbar, text='Kanalpegel  »', width=130,
            height=32, fg_color=BTN, hover_color='#45556E', command=self.toggle_levels)
        # Anchor to the fixed-width left control area, not the growing window edge.
        # The button stays at the same horizontal position when channels open.
        self.expand_button.pack(side='left', padx=(135, 0))

        self.tab_host = ctk.CTkFrame(self.root, fg_color=BG)
        self.tab_host.pack(fill='both', expand=True, padx=12, pady=(0, 10))
        self.sound_tab = ctk.CTkFrame(self.tab_host, fg_color=BG)
        self.config_tab = ctk.CTkFrame(self.tab_host, fg_color=BG)
        self.sound_tab.pack(fill='both', expand=True)
        self.current_tab = 'sound'
        # Keep the left control column at a fixed width when the right panel opens.
        # Otherwise Tk redistributes the extra space and every slider/button redraws.
        self.sound_tab.grid_columnconfigure(0, weight=0, minsize=610)
        self.sound_tab.grid_columnconfigure(1, weight=0, minsize=0)
        self.sound_tab.grid_rowconfigure(0, weight=1)

        left = ctk.CTkScrollableFrame(self.sound_tab, fg_color='transparent',
                                     width=600, scrollbar_button_color='#4C5870')
        left.grid(row=0, column=0, sticky='nsew', padx=(2, 5))

        def section(title):
            panel = ctk.CTkFrame(left, fg_color=PANEL, corner_radius=12)
            panel.pack(fill='x', pady=(0, 9))
            ctk.CTkLabel(panel, text=title, font=('Segoe UI', 14, 'bold'),
                         text_color=TEXT, anchor='w').pack(fill='x', padx=13, pady=(9, 3))
            return panel

        volume_box = section('Gesamtlautstärke')
        volume_row = ctk.CTkFrame(volume_box, fg_color='transparent')
        volume_row.pack(fill='x', padx=12, pady=(0, 12))
        ctk.CTkButton(volume_row, text='−', width=34, height=30, fg_color=BTN,
                      command=lambda: self.step_volume(-1)).pack(side='left')
        self.vol_scale = ctk.CTkSlider(volume_row, from_=0, to=100, number_of_steps=100,
                                      progress_color='#76A9FF', button_color='#9BC0FF',
                                      command=self.on_volume_move)
        self.vol_scale.pack(side='left', fill='x', expand=True, padx=11)
        ctk.CTkButton(volume_row, text='+', width=34, height=30, fg_color=BTN,
                      command=lambda: self.step_volume(1)).pack(side='left')
        ctk.CTkLabel(volume_row, textvariable=self.volume_display, width=38,
                     font=('Segoe UI', 15, 'bold')).pack(side='left', padx=(8, 0))

        toggle_box = section('Klangoptionen')
        toggle_row = ctk.CTkFrame(toggle_box, fg_color='transparent')
        toggle_row.pack(fill='x', padx=12, pady=(1, 12))
        for label, field, method in [
            ('Night Time', 'b_night_time', 'set_night_time_corrected'),
            ('DRC', 'b_drc', 'set_drc'),
            ('Surround', 'b_rear', 'set_surround_corrected'),
        ]:
            variable = tk.BooleanVar(value=False)
            control = ctk.CTkSwitch(toggle_row, text=label, variable=variable,
                font=('Segoe UI', 11), switch_width=31, switch_height=17,
                progress_color=BLUE, button_color='#E5EEFF',
                command=lambda f=field, m=method, v=variable: self.on_toggle(f, m, v))
            control.pack(side='left', expand=True, padx=(0, 5))
            control.configure(state='disabled')
            self.toggle_controls[field] = (variable, control, method)

        eq_box = section('Klangmodus')
        eq_grid = ctk.CTkFrame(eq_box, fg_color='transparent')
        eq_grid.pack(fill='x', padx=11, pady=(0, 4))
        for col in range(3):
            eq_grid.grid_columnconfigure(col, weight=1, uniform='mode')
        for i, name in enumerate(MODES):
            button = ctk.CTkButton(eq_grid, text=name, height=36, corner_radius=8,
                font=('Segoe UI', 11), fg_color=BTN, hover_color='#43506A',
                command=lambda n=name: self.choose_mode(n))
            button.grid(row=i // 3, column=i % 3, sticky='ew', padx=3, pady=3)
            self.mode_buttons[name] = button
        info_row = ctk.CTkFrame(eq_box, fg_color='transparent')
        info_row.pack(fill='x', padx=14, pady=(1, 9))
        ctk.CTkLabel(info_row, textvariable=self.eq_display, text_color=MUTED,
                     font=('Segoe UI', 11)).pack(side='left')
        ctk.CTkLabel(info_row, text='Bass', text_color=MUTED).pack(side='left', padx=(16, 4))
        ctk.CTkLabel(info_row, textvariable=self.bass_display).pack(side='left')
        ctk.CTkLabel(info_row, text='Höhen', text_color=MUTED).pack(side='left', padx=(14, 4))
        ctk.CTkLabel(info_row, textvariable=self.treble_display).pack(side='left')

        profile_box = ctk.CTkFrame(left, fg_color=PANEL, corner_radius=12)
        profile_box.pack(fill='x', pady=(0, 9))
        profile_head = ctk.CTkFrame(profile_box, fg_color='transparent')
        profile_head.pack(fill='x', padx=13, pady=(8, 5))
        ctk.CTkLabel(profile_head, text='Klangprofile', font=('Segoe UI', 14, 'bold')).pack(side='left')
        ctk.CTkButton(profile_head, text='Verwalten', width=90, height=28,
                      fg_color=BTN, command=self.manage_profiles).pack(side='right')
        self.profile_grid = ctk.CTkFrame(profile_box, fg_color='transparent')
        self.profile_grid.pack(fill='x', padx=11, pady=(0, 10))
        for col in range(3):
            self.profile_grid.grid_columnconfigure(col, weight=1, uniform='profile')
        self.update_profile_list()

        self.level_panel = ctk.CTkScrollableFrame(self.sound_tab, fg_color=PANEL,
                                                  corner_radius=12, width=340)
        ctk.CTkLabel(self.level_panel, text='Kanalpegel', font=('Segoe UI', 16, 'bold')).pack(anchor='w', pady=(2, 5))
        for label, key, low, high, offset, method, verified in LEVELS:
            row = ctk.CTkFrame(self.level_panel, fg_color='transparent')
            row.pack(fill='x', pady=2)
            ctk.CTkLabel(row, text=label, anchor='w', font=('Segoe UI', 12), height=19).pack(fill='x')
            slider_row = ctk.CTkFrame(row, fg_color='transparent')
            slider_row.pack(fill='x', pady=(0, 0))
            scale = ctk.CTkSlider(slider_row, from_=low, to=high, height=14,
                number_of_steps=high-low, progress_color='#76A9FF',
                command=lambda val, k=key: self.on_level_move(k, val))
            scale.pack(side='left', fill='x', expand=True, padx=(0, 9))
            value = tk.StringVar(value='–')
            value_badge = ctk.CTkLabel(slider_row, textvariable=value, width=65, height=26,
                corner_radius=7, fg_color='#34465E', text_color='#A9C9FF',
                font=('Segoe UI', 12, 'bold'))
            value_badge.pack(side='right')
            # Zero-point tick below the scale; proportional for asymmetric subwoofer range.
            tick_track = ctk.CTkFrame(row, fg_color='transparent', height=5)
            tick_track.pack(fill='x', padx=(0, 74))
            tick_track.grid_columnconfigure(0, weight=1)
            tick = ctk.CTkLabel(tick_track, text='│', text_color='#78889E',
                font=('Segoe UI', 8), width=6, height=6)
            tick.place(relx=(0-low)/(high-low), rely=0, anchor='n')
            self.level_controls[key] = {'scale': scale, 'value': value, 'offset': offset,
                'min': low, 'max': high, 'method': method, 'verified': verified}

        connection = ctk.CTkFrame(self.config_tab, fg_color=PANEL, corner_radius=12)
        connection.pack(fill='x', padx=8, pady=(12, 10))
        ctk.CTkLabel(connection, text='Soundbar-Verbindung', font=('Segoe UI', 16, 'bold')).pack(anchor='w', padx=16, pady=(14, 8))
        r = ctk.CTkFrame(connection, fg_color='transparent')
        r.pack(fill='x', padx=16, pady=(0, 16))
        ctk.CTkEntry(r, textvariable=self.ip, width=175, height=35).pack(side='left', padx=(0, 8))
        ctk.CTkButton(r, text='Verbinden', width=100, command=self.connect).pack(side='left', padx=(0, 8))
        ctk.CTkButton(r, text='Aktualisieren', width=110, fg_color=BTN,
                      command=self.refresh).pack(side='left')
        nas_box = ctk.CTkFrame(self.config_tab, fg_color=PANEL, corner_radius=12)
        nas_box.pack(fill='x', padx=8, pady=(0, 10))
        ctk.CTkLabel(nas_box, text='Speicherort für Klangprofile',
                     font=('Segoe UI', 16, 'bold')).pack(anchor='w', padx=16, pady=(14, 7))
        self.local_radio = ctk.CTkRadioButton(nas_box, text='Lokal auf diesem PC',
            variable=self.storage_mode, value='local', command=self.on_storage_mode_change)
        self.local_radio.pack(anchor='w', padx=16, pady=(4, 6))
        self.nas_radio = ctk.CTkRadioButton(nas_box, text='Gemeinsamer Netzwerkordner (NAS)',
            variable=self.storage_mode, value='nas', command=self.on_storage_mode_change)
        self.nas_radio.pack(anchor='w', padx=16, pady=(0, 8))
        self.nas_path_entry = ctk.CTkEntry(nas_box, textvariable=self.nas_folder, height=35,
            placeholder_text=r'\\SERVER\Freigabe\Tonmaschine')
        self.nas_path_entry.pack(fill='x', padx=16, pady=(0, 10))
        ctk.CTkButton(nas_box, text='Konfiguration speichern / Profile aktualisieren',
                      command=self.refresh_profiles).pack(anchor='w', padx=16, pady=(0, 9))
        ctk.CTkLabel(nas_box, textvariable=self.nas_state, text_color=MUTED,
                     wraplength=510, justify='left').pack(anchor='w', padx=16, pady=(0, 10))
        ctk.CTkButton(nas_box, text='Alte Profile aus JSON importieren',
                      fg_color=BTN, command=self.import_legacy_profiles).pack(anchor='w', padx=16, pady=(0, 12))
        self.on_storage_mode_change()
        self.update_mode_buttons()
        # Show operation and error messages; otherwise self.status is invisible.
        status_bar = ctk.CTkFrame(self.root, fg_color='transparent')
        status_bar.pack(fill='x', padx=20, pady=(0, 6))
        ctk.CTkLabel(status_bar, textvariable=self.status, anchor='w',
                     text_color=MUTED, wraplength=590,
                     font=('Segoe UI', 11)).pack(fill='x')

    def show_tab(self, tab):
        self.current_tab = tab
        self.sound_tab.pack_forget()
        self.config_tab.pack_forget()
        (self.sound_tab if tab == 'sound' else self.config_tab).pack(fill='both', expand=True)
        self.sound_tab_button.configure(fg_color='#4265A7' if tab == 'sound' else '#303746')
        self.config_tab_button.configure(fg_color='#4265A7' if tab == 'config' else '#303746')
        self.expand_button.configure(state='normal' if tab == 'sound' else 'disabled')

    def toggle_levels(self):
        """Show/hide only the existing channel panel, without rebuilding controls.

        The left column has a fixed width, so widening the main window does not
        resize or reconstruct its widgets. Tk may still repaint on window resize.
        """
        self.expanded = not self.expanded
        if self.expanded:
            # First grow the window, then reveal the already-created panel.
            self.root.minsize(980, 575)
            self.root.geometry('1010x670')
            self.level_panel.grid(row=0, column=1, sticky='nsew', padx=(0, 8), pady=(0, 6))
            self.expand_button.configure(text='Kanalpegel  «')
        else:
            # Hide the existing widgets; never destroy or recreate them.
            self.level_panel.grid_remove()
            self.expand_button.configure(text='Kanalpegel  »')
            self.root.minsize(625, 575)
            self.root.geometry('650x670')

    def update_mode_buttons(self):
        active = MODE_BY_ID.get(self.last_mode) if self.last_mode is not None else None
        for name, button in self.mode_buttons.items():
            selected = name == active
            button.configure(fg_color='#4265A7' if selected else '#303746',
                             hover_color='#5377B9' if selected else '#43506A',
                             state='normal' if self.ready and not self.mode_busy else 'disabled')

    def mark_profile_dirty(self):
        if self.active_profile is not None and not self.profile_busy:
            self.active_profile = None
            self.update_profile_highlight()

    def choose_mode(self, name):
        self.mark_profile_dirty()
        self.mode_var.set(name)
        self.on_mode_change()

    def update_profile_buttons(self):
        if not hasattr(self, 'profile_grid'):
            return
        for widget in self.profile_grid.winfo_children():
            widget.destroy()
        self.profile_buttons.clear()
        for i, name in enumerate(sorted(self.profiles, key=str.casefold)):
            button = ctk.CTkButton(self.profile_grid, text=name, height=36,
                font=('Segoe UI', 11), corner_radius=8, fg_color='#303746',
                hover_color='#43506A', command=lambda n=name: self.choose_profile(n))
            button.grid(row=i // 3, column=i % 3, sticky='ew', padx=3, pady=3)
            self.profile_buttons[name] = button
        self.update_profile_highlight()

    def update_profile_highlight(self):
        active = self.active_profile
        for name, button in self.profile_buttons.items():
            selected = name == active
            button.configure(text=('✓  ' + name) if selected else name,
                             fg_color='#4265A7' if selected else '#303746',
                             state='normal' if self.ready and not self.profile_busy else 'disabled')

    def choose_profile(self, name):
        if self.profile_busy or not self.ready:
            return
        self.profile_var.set(name)
        self.apply_profile()

    def manage_profiles(self):
        dialog = ctk.CTkToplevel(self.root)
        dialog.title('Klangprofile verwalten')
        dialog.geometry('480x315')
        dialog.transient(self.root)
        dialog.after(150, dialog.grab_set)
        frame = ctk.CTkFrame(dialog, fg_color='#232936', corner_radius=12)
        frame.pack(fill='both', expand=True, padx=14, pady=14)
        ctk.CTkLabel(frame, text='Klangprofile verwalten', font=('Segoe UI', 17, 'bold')).pack(anchor='w', padx=14, pady=(12, 8))
        combo = ctk.CTkComboBox(frame, variable=self.profile_var, values=sorted(self.profiles) or [''],
                                state='readonly', height=34)
        combo.pack(fill='x', padx=14, pady=(0, 10))
        ctk.CTkEntry(frame, textvariable=self.profile_name,
                     placeholder_text='Profilname (neu oder überschreiben)', height=34).pack(fill='x', padx=14, pady=(0, 12))
        row = ctk.CTkFrame(frame, fg_color='transparent')
        row.pack(fill='x', padx=14)
        ctk.CTkButton(row, text='Aktuelle Werte speichern', width=180,
            command=lambda: (self.save_profile(), combo.configure(values=sorted(self.profiles) or ['']))).pack(side='left')
        ctk.CTkButton(row, text='Auswahl löschen', width=130, fg_color='#7B3541',
            command=lambda: (self.delete_profile(), combo.configure(values=sorted(self.profiles) or ['']))).pack(side='left', padx=8)
        ctk.CTkButton(frame, text='Schließen', width=100, fg_color='#303746',
                      command=dialog.destroy).pack(anchor='e', padx=14, pady=(16, 0))
        ctk.CTkLabel(frame, text='Umbenennen: unter neuem Namen speichern, altes Profil löschen.',
                     text_color='#A7B4C8', font=('Segoe UI', 11)).pack(anchor='w', padx=14, pady=(8, 0))

    @staticmethod
    def load_config():
        try:
            config = read_json_dict(CONFIG_FILE)
        except (OSError, ValueError):
            return {}
        # Existing V15 installations used NAS exclusively.
        if 'storage_mode' not in config and config.get('nas_folder'):
            config['storage_mode'] = 'nas'
        return config

    def save_config(self):
        APP_DIR.mkdir(parents=True, exist_ok=True)
        atomic_write_json(CONFIG_FILE, {
            'soundbar_ip': self.ip.get().strip(),
            'storage_mode': self.storage_mode.get(),
            'nas_folder': self.nas_folder.get().strip(),
        })

    def on_storage_mode_change(self):
        if hasattr(self, 'nas_path_entry'):
            self.nas_path_entry.configure(state='normal' if self.storage_mode.get() == 'nas' else 'disabled')

    def profile_path(self):
        if self.storage_mode.get() == 'local':
            return LOCAL_PROFILE_FILE
        return Path(self.nas_folder.get().strip()) / PROFILE_FILENAME

    def update_profile_list(self):
        self.update_profile_buttons()

    def load_profiles(self):
        """Local storage is writable; NAS uses a read-only cache if offline."""
        if self.storage_mode.get() == 'local':
            try:
                self.profiles = read_json_dict(LOCAL_PROFILE_FILE) if LOCAL_PROFILE_FILE.exists() else {}
                self.nas_online = True  # storage is writable, no NAS required
                self.nas_state.set(f'Lokal: {LOCAL_PROFILE_FILE} ({len(self.profiles)} Profile)')
            except (OSError, ValueError) as exc:
                self.profiles = {}
                self.nas_online = False
                self.nas_state.set(f'Lokale Profildatei nicht lesbar: {exc}')
            self.update_profile_list()
            return self.nas_online
        folder_text = self.nas_folder.get().strip()
        if not folder_text:
            self.nas_online = False
            self.profiles = {}
            self.nas_state.set('Bitte einen Netzwerkordner angeben.')
            self.update_profile_list()
            return False
        folder = Path(folder_text)
        try:
            if not folder.is_dir():
                raise OSError(f'Ordner nicht erreichbar: {folder}')
            path = folder / PROFILE_FILENAME
            self.profiles = read_json_dict(path) if path.exists() else {}
            self.nas_online = True
            self.nas_state.set(f'NAS verbunden: {folder} ({len(self.profiles)} Profile)')
            APP_DIR.mkdir(parents=True, exist_ok=True)
            atomic_write_json(CACHE_FILE, self.profiles)
        except (OSError, ValueError) as exc:
            self.nas_online = False
            try:
                self.profiles = read_json_dict(CACHE_FILE)
                self.nas_state.set(f'NAS nicht verfügbar; nur Lesekopie ({len(self.profiles)} Profile). {exc}')
            except (OSError, ValueError):
                self.profiles = {}
                self.nas_state.set(f'NAS nicht verfügbar. {exc}')
        self.update_profile_list()
        return self.nas_online

    def refresh_profiles(self, silent=False):
        if self.storage_mode.get() == 'nas' and not self.nas_folder.get().strip():
            messagebox.showwarning('Netzwerkpfad fehlt', 'Bitte einen Netzwerkordner angeben.')
            return
        try:
            self.save_config()
        except OSError as exc:
            messagebox.showerror('Konfiguration', str(exc))
            return
        online = self.load_profiles()
        if not online and not silent:
            messagebox.showwarning('Profilspeicher nicht erreichbar', self.nas_state.get())

    def write_profiles(self, name, profile=None, delete=False):
        """Update profiles atomically; serialize concurrent NAS writers with lock."""
        if self.storage_mode.get() == 'local':
            APP_DIR.mkdir(parents=True, exist_ok=True)
            path = LOCAL_PROFILE_FILE
            current = read_json_dict(path) if path.exists() else {}
            if delete:
                if name not in current:
                    raise OSError('Profil nicht mehr vorhanden.')
                current.pop(name)
            else:
                current[name] = profile
            atomic_write_json(path, current)
            self.profiles = current
            self.nas_online = True
            self.nas_state.set(f'Lokal: {path} ({len(current)} Profile)')
            self.update_profile_list()
            return
        folder = Path(self.nas_folder.get().strip())
        if not folder.is_dir():
            raise OSError('NAS-Ordner ist nicht erreichbar. Es wurde nichts lokal gespeichert.')
        lock_folder = folder / '.Tonmaschine_write_lock'
        deadline = time.monotonic() + 6
        while True:
            try:
                lock_folder.mkdir()
                break
            except FileExistsError:
                if time.monotonic() >= deadline:
                    raise OSError('Der NAS-Speicher ist durch einen anderen Schreibvorgang gesperrt. '
                                  'Falls die Sperre dauerhaft bleibt, den Ordner .Tonmaschine_write_lock prüfen.')
                time.sleep(0.15)
        try:
            path = folder / PROFILE_FILENAME
            current = read_json_dict(path) if path.exists() else {}
            if delete:
                if name not in current:
                    raise OSError('Das Profil existiert auf dem NAS nicht mehr. Bitte aktualisieren.')
                current.pop(name)
            else:
                current[name] = profile
            atomic_write_json(path, current)
            self.profiles = current
            self.nas_online = True
            self.nas_state.set(f'NAS verbunden: {folder} ({len(current)} Profile)')
            try:
                APP_DIR.mkdir(parents=True, exist_ok=True)
                atomic_write_json(CACHE_FILE, current)
            except OSError as exc:
                # The NAS write already succeeded. A failed optional cache update
                # must not be reported as a failed profile save.
                self.nas_state.set(f'NAS gespeichert, lokaler Cache nicht aktualisiert: {exc}')
            self.update_profile_list()
        finally:
            lock_folder.rmdir()

    def import_legacy_profiles(self):
        # A one-file PyInstaller executable extracts Python files to a temporary
        # folder. Let the user explicitly choose the old profile file instead.
        source = filedialog.askopenfilename(
            parent=self.root,
            title='Bisherige Tonmaschine-Profildatei auswählen',
            initialdir=str(LEGACY_PROFILE_FILE.parent),
            initialfile=LEGACY_PROFILE_FILE.name,
            filetypes=[('Tonmaschine-Profile', 'Tonmaschine_Profile.json'),
                       ('JSON-Dateien', '*.json')],
        )
        if not source:
            return
        try:
            old = read_json_dict(source)
        except (OSError, ValueError) as exc:
            messagebox.showerror('Import fehlgeschlagen', str(exc))
            return
        if not old:
            messagebox.showinfo('Keine Profile', 'Die bisherige Profildatei ist leer.')
            return
        if not self.load_profiles():
            messagebox.showerror('Speicher nicht erreichbar', 'Import ist nur bei erreichbarem Profilspeicher möglich.')
            return
        if not messagebox.askyesno('Profile importieren',
                                   f'{len(old)} alte Profile übernehmen? Gleichnamige Profile werden überschrieben.'):
            return
        try:
            for name, profile in old.items():
                self.write_profiles(name, profile)
        except (OSError, ValueError) as exc:
            messagebox.showerror('Import fehlgeschlagen', str(exc))
            return
        messagebox.showinfo('Import abgeschlossen', f'{len(old)} Profile übernommen.')

    def save_profile(self):
        name = self.profile_name.get().strip()
        if not name:
            messagebox.showwarning('Profilname fehlt', 'Bitte einen Namen für das Profil eingeben.')
            return
        if not self.ready or self.last_mode not in MODE_BY_ID or not self.last_settings:
            messagebox.showwarning('Keine Daten', 'Bitte erst mit der Soundbar verbinden und aktualisieren.')
            return
        if not self.load_profiles():
            messagebox.showerror('Speicher nicht erreichbar', 'Der gewählte Profilspeicher ist nicht erreichbar.')
            return
        if name in self.profiles and not messagebox.askyesno('Profil ersetzen', f'Profil „{name}“ überschreiben?'):
            return
        # Capture the currently displayed controls, including recent manual changes.
        levels = {key: int(round(control['scale'].get())) for key, control in self.level_controls.items()}
        new_profile = {
            'mode': int(self.last_mode),
            'levels': levels,
            'drc': bool(self.toggle_controls['b_drc'][0].get()),
            'night_time': bool(self.toggle_controls['b_night_time'][0].get()),
            'surround': bool(self.toggle_controls['b_rear'][0].get()),
        }
        try:
            self.write_profiles(name, new_profile)
        except OSError as exc:
            messagebox.showerror('Speichern fehlgeschlagen', str(exc))
            return
        self.profile_var.set(name)
        self.active_profile = name
        self.update_profile_highlight()
        self.status.set(f'Profil „{name}“ gespeichert (ohne Gesamtlautstärke).')

    def delete_profile(self):
        name = self.profile_var.get()
        if not name or name not in self.profiles:
            return
        if not messagebox.askyesno('Profil löschen', f'Profil „{name}“ wirklich löschen?'):
            return
        if not self.load_profiles():
            messagebox.showerror('Speicher nicht erreichbar', 'Der gewählte Profilspeicher ist nicht erreichbar.')
            return
        if name not in self.profiles:
            messagebox.showinfo('Profil nicht vorhanden', 'Das Profil wurde auf einem anderen PC bereits gelöscht.')
            return
        try:
            self.write_profiles(name, delete=True)
        except OSError as exc:
            messagebox.showerror('Löschen fehlgeschlagen', str(exc))
            return
        self.profile_var.set('')
        self.active_profile = None
        self.update_profile_highlight()
        self.status.set(f'Profil „{name}“ gelöscht.')

    def on_mode_change(self, event=None):
        if self.updating or not self.ready or self.mode_busy:
            return
        name = self.mode_var.get()
        if name not in MODES:
            return
        code = MODES[name]
        self.mode_busy = True
        self.update_mode_buttons()
        self.status.set(f'Schalte auf {name} ...')
        def task():
            self.client.set_eq(code)
            time.sleep(0.45)
            self.client.get_eq()
            self.events.put(('mode_done', None))
        self.work(task)

    def apply_profile(self):
        name = self.profile_var.get()
        # Read latest profile data from the configured storage.
        self.load_profiles()
        if not self.ready or name not in self.profiles:
            return
        p = self.profiles[name]
        if p.get('mode') not in MODE_BY_ID or not isinstance(p.get('levels'), dict):
            messagebox.showerror('Profil ungültig', 'Dieses Profil enthält unbekannte Werte.')
            return
        for _, key, low, high, _, _, _ in LEVELS:
            val = p['levels'].get(key)
            if type(val) is not int or not low <= val <= high:
                messagebox.showerror('Profil ungültig', f'Ungültiger Pegel: {key}')
                return
        if type(p.get('drc')) is not bool or type(p.get('night_time')) is not bool or (
                'surround' in p and type(p['surround']) is not bool):
            messagebox.showerror('Profil ungültig', 'Ungültige Klangoptionen.')
            return
        for job in list(self.after_ids.values()):
            self.root.after_cancel(job)
        self.after_ids.clear()
        self.pending.clear()
        self.profile_busy = True
        self.active_profile = None
        self.update_profile_highlight()
        self.status.set(f'Lade Profil „{name}“ ...')
        def task():
            self.client.set_eq(p['mode'])
            for _, key, _, _, offset, method_name, _ in LEVELS:
                raw = p['levels'][key] + offset
                method = getattr(self.client, method_name, None) if method_name else None
                if callable(method):
                    method(raw)
                else:
                    self.client.send_packet({'cmd':'set','data':{key:raw},'msg':'SETTING_VIEW_INFO'})
            self.client.set_drc(p['drc'])
            self.client.send_packet({'cmd':'set','data':{'b_night_time':p['night_time']},
                                     'msg':'SETTING_VIEW_INFO'})
            # Older V6 profiles lack 'surround': leave current device setting unchanged.
            if 'surround' in p:
                self.client.send_packet({'cmd':'set','data':{'b_rear':p['surround']},
                                         'msg':'SETTING_VIEW_INFO'})
            time.sleep(0.65)
            self.client.get_eq()
            self.client.get_settings()
            self.events.put(('profile_done', name))
        self.work(task)

    def work(self, fn):
        def runner():
            try:
                with self.lock:
                    fn()
            except Exception as exc:
                self.events.put(('error', str(exc)))
        threading.Thread(target=runner, daemon=True).start()

    def callback(self, response):
        self.events.put(('response', response))

    def connect(self):
        address = self.ip.get().strip()
        if not address:
            messagebox.showerror('IP fehlt', 'Bitte die IP-Adresse angeben.')
            return
        try:
            self.save_config()
        except OSError as exc:
            messagebox.showerror('Konfiguration', str(exc))
            return
        self.ready = False
        self.status.set('Verbinde ...')
        self.connection_text.set('● Verbinde ...')
        self.connection_label.configure(text_color='#A7B4C8')
        def task():
            if self.client:
                self.client.close()
            self.client = LGSoundbarClient(address, callback=self.callback)
            self.client.get_info()
            self.client.get_eq()
            self.client.get_settings()
            # Do not mark the connection ready before the initial commands succeed.
            self.events.put(('connected', address))
        self.work(task)

    def refresh(self):
        if not self.client:
            self.connect()
            return
        self.work(lambda: (self.client.get_info(), self.client.get_eq(), self.client.get_settings()))

    @staticmethod
    def toggle_arg(method):
        """Only accept a single explicit boolean argument; do not guess parameter names."""
        try:
            params = list(inspect.signature(method).parameters.values())
        except (ValueError, TypeError):
            return None
        required = [p for p in params if p.default is inspect.Parameter.empty
                    and p.kind not in (inspect.Parameter.VAR_KEYWORD, inspect.Parameter.VAR_POSITIONAL)]
        if len(required) != 1:
            return None
        name = required[0].name
        if name not in ('enabled', 'enable', 'value', 'state', 'on', 'status', 'drc', 'night_time', 'auto_vol'):
            return None
        return name

    def toggle_available(self, name):
        if not self.client:
            return False
        if name in ('set_night_time_corrected', 'set_surround_corrected'):
            return callable(getattr(self.client, 'send_packet', None))
        method = getattr(self.client, name, None)
        return callable(method) and self.toggle_arg(method) is not None

    def on_toggle(self, field, method_name, var):
        if self.updating or not self.ready:
            return
        self.mark_profile_dirty()
        if not self.toggle_available(method_name):
            return
        value = bool(var.get())
        self.status.set('Übertrage ' + field + ' ...')
        if field in ('b_night_time', 'b_rear'):
            def task():
                self.client.send_packet({'cmd': 'set', 'data': {field: value},
                                         'msg': 'SETTING_VIEW_INFO'})
                time.sleep(0.35)
                self.client.get_settings()
            self.work(task)
        else:
            method = getattr(self.client, method_name)
            param = self.toggle_arg(method)
            self.work(lambda: (method(**{param: value}), self.client.get_settings()))

    def on_level_move(self, key, value):
        if self.updating or not self.ready:
            return
        self.mark_profile_dirty()
        entry = self.level_controls[key]
        level = max(entry['min'], min(entry['max'], round(float(value))))
        entry['value'].set(f'{level:+d} dB')
        self.pending[key] = level
        if key in self.after_ids:
            self.root.after_cancel(self.after_ids[key])
        self.after_ids[key] = self.root.after(230, lambda k=key: self.send_level(k))

    def send_level(self, key):
        self.after_ids.pop(key, None)
        level = self.pending.pop(key, None)
        if level is None or not self.client or not self.ready:
            return
        entry = self.level_controls[key]
        raw = level + entry['offset']
        method = getattr(self.client, entry['method'], None) if entry['method'] else None
        def task():
            if callable(method):
                method(raw)
            else:
                self.client.send_packet({'cmd': 'set', 'data': {key: raw},
                                         'msg': 'SETTING_VIEW_INFO'})
            # Allow the soundbar to apply the command before reading back.
            import time
            time.sleep(0.35)
            self.client.get_settings()
        self.work(task)

    def on_volume_move(self, value):
        if self.updating or not self.ready:
            return
        number = max(0, min(100, round(float(value))))
        self.volume_display.set(str(number))
        self.schedule_volume(number)

    def step_volume(self, delta):
        if not self.ready:
            return
        value = max(0, min(100, round(float(self.vol_scale.get())) + delta))
        self.updating = True
        self.vol_scale.set(value)
        self.updating = False
        self.volume_display.set(str(value))
        self.schedule_volume(value)

    def schedule_volume(self, value):
        # Debounce rapid mouse movement; the latest value wins.
        self.pending['volume'] = value
        if 'volume' in self.after_ids:
            self.root.after_cancel(self.after_ids['volume'])
        self.after_ids['volume'] = self.root.after(180, self.send_volume)

    def send_volume(self):
        self.after_ids.pop('volume', None)
        value = self.pending.pop('volume', None)
        if value is not None and self.client and self.ready:
            self.work(lambda: self.client.set_volume(value))

    def poll(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == 'connected':
                    self.ready = True
                    self.update_mode_buttons()
                    self.update_profile_highlight()
                    self.status.set('Verbunden mit ' + value)
                    self.connection_text.set('● Verbunden')
                    self.connection_label.configure(text_color='#80CFAE')
                    for field, (var, control, method) in self.toggle_controls.items():
                        if self.toggle_available(method):
                            control.configure(state='normal')
                        else:
                            control.configure(state='disabled')
                elif kind == 'mode_done':
                    self.mode_busy = False
                    self.update_mode_buttons()
                elif kind == 'profile_done':
                    self.profile_busy = False
                    self.active_profile = value
                    self.update_profile_highlight()
                    self.status.set(f'Profil „{value}“ übertragen; Werte aktualisiert.')
                elif kind == 'error':
                    self.ready = False
                    self.connection_text.set('● Fehler')
                    self.connection_label.configure(text_color='#F1A1A1')
                    self.mode_busy = False
                    self.profile_busy = False
                    for _, control, _ in self.toggle_controls.values():
                        control.configure(state='disabled')
                    self.update_mode_buttons()
                    self.update_profile_highlight()
                    self.status.set('Fehler: ' + value + ' (bitte erneut verbinden)')
                elif kind == 'response' and isinstance(value, dict) and value.get('result') == 'ok':
                    data = value.get('data') or {}
                    self.updating = True
                    if 'i_vol' in data and 'volume' not in self.pending:
                        self.vol_scale.set(int(data['i_vol']))
                        self.volume_display.set(str(data['i_vol']))
                    if 'i_curr_eq' in data:
                        self.last_mode = int(data['i_curr_eq'])
                        self.eq_display.set('Modus-ID: ' + str(self.last_mode))
                        self.mode_var.set(MODE_BY_ID.get(self.last_mode, ''))
                        self.update_mode_buttons()
                    if 'i_bass' in data:
                        self.bass_display.set(str(data['i_bass']))
                    if 'i_treble' in data:
                        self.treble_display.set(str(data['i_treble']))
                    if value.get('cmd') == 'notibyget' and value.get('msg') == 'SETTING_VIEW_INFO':
                        self.last_settings.update(data)
                    for field, (var, control, method) in self.toggle_controls.items():
                        if field in data:
                            var.set(bool(data[field]))
                    for field, entry in self.level_controls.items():
                        if field in data and field not in self.pending and field not in self.after_ids:
                            level = int(data[field]) - entry['offset']
                            if entry['min'] <= level <= entry['max']:
                                entry['scale'].set(level)
                                entry['value'].set(f'{level:+d} dB')
                            else:
                                entry['value'].set(f'Rohwert {data[field]}')
                    self.updating = False
                    if self.active_profile and not self.profile_busy:
                        p = self.profiles.get(self.active_profile)
                        if p and self.last_mode is not None and self.last_settings:
                            actual_levels = {k: int(round(e['scale'].get())) for k, e in self.level_controls.items()}
                            mismatch = (p.get('mode') != self.last_mode or
                                any(k in data and p.get('levels', {}).get(k) != actual_levels[k] for k in actual_levels) or
                                any(field in data and p.get(profile_key) != bool(self.toggle_controls[field][0].get())
                                    for field, profile_key in [('b_drc','drc'), ('b_night_time','night_time'), ('b_rear','surround')]
                                    if profile_key in p))
                            if mismatch:
                                self.mark_profile_dirty()
        except queue.Empty:
            pass
        except (ValueError, TypeError, OverflowError) as exc:
            # A malformed soundbar response must not stop the UI event loop.
            self.updating = False
            self.status.set(f'Ungültige Soundbar-Antwort: {exc}')
        self.root.after(80, self.poll)

    def close(self):
        for job in self.after_ids.values():
            self.root.after_cancel(job)
        if self.client:
            threading.Thread(target=self.client.close, daemon=True).start()
        self.root.destroy()

if __name__ == '__main__':
    ctk.set_appearance_mode('dark')
    ctk.set_default_color_theme('blue')
    root = ctk.CTk()
    Controller(root)
    root.mainloop()
