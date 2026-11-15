"""Dependency-free behavior tests for Tonmaschine V16's persistence layer.
Run with: python -m pytest -q
"""
from pathlib import Path
import importlib.util
import json
import queue
from concurrent.futures import ThreadPoolExecutor
import sys
import types

import pytest


class Var:
    def __init__(self, value=None):
        self.value = value
    def get(self):
        return self.value
    def set(self, value):
        self.value = value


@pytest.fixture
def mod(monkeypatch):
    monkeypatch.setitem(sys.modules, 'customtkinter', types.ModuleType('customtkinter'))
    stub = types.ModuleType('lg_bartalk')
    stub.LGSoundbarClient = object
    monkeypatch.setitem(sys.modules, 'lg_bartalk', stub)
    src = Path(__file__).resolve().parents[1] / 'Tonmaschine.py'
    spec = importlib.util.spec_from_file_location('tonmaschine_audit_test', src)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


@pytest.fixture
def state(mod, monkeypatch, tmp_path):
    appdir = tmp_path / 'appdata' / 'Tonmaschine'
    monkeypatch.setattr(mod, 'APP_DIR', appdir)
    monkeypatch.setattr(mod, 'CONFIG_FILE', appdir / 'config.json')
    monkeypatch.setattr(mod, 'CACHE_FILE', appdir / 'Tonmaschine_Profile_cache.json')
    monkeypatch.setattr(mod, 'LOCAL_PROFILE_FILE', appdir / 'Tonmaschine_Profile.json')
    c = mod.Controller.__new__(mod.Controller)
    c.ip = Var('192.0.2.1')
    c.storage_mode = Var('local')
    c.nas_folder = Var('')
    c.nas_state = Var('')
    c.profiles = {}
    c.nas_online = False
    c.update_profile_list = lambda: None
    c.root = object()
    return c,mod,appdir


def profile(mode=0):
    return {'mode': mode, 'levels': {'i_woofer_level': 0},
            'drc': True, 'night_time': False, 'surround': False}


def test_new_install_config_persists(state):
    c,m,root = state
    c.save_config()
    assert json.loads(m.CONFIG_FILE.read_text(encoding='utf-8')) == {
        'soundbar_ip': '192.0.2.1', 'storage_mode': 'local', 'nas_folder': ''}
    assert m.Controller.load_config()['storage_mode'] == 'local'


def test_legacy_v15_config_remains_nas(state):
    c,m,root = state
    root.mkdir(parents=True)
    m.CONFIG_FILE.write_text(json.dumps({'nas_folder': r'\\SERVER\share'}),encoding='utf-8')
    got = m.Controller.load_config()
    assert got['storage_mode'] == 'nas'
    assert got['nas_folder'] == r'\\SERVER\share'


def test_local_create_overwrite_delete(state):
    c,m,_ = state
    c.write_profiles('A',profile())
    c.write_profiles('B',profile(7))
    c.write_profiles('A',profile(14))
    assert set(json.loads(m.LOCAL_PROFILE_FILE.read_text(encoding='utf-8'))) == {'A','B'}
    c.write_profiles('B',delete=True)
    assert list(json.loads(m.LOCAL_PROFILE_FILE.read_text(encoding='utf-8'))) == ['A']
    assert c.load_profiles() is True
    assert c.profiles['A']['mode'] == 14


def test_corrupt_local_data_not_overwritten(state):
    c,m,root=state
    root.mkdir(parents=True)
    m.LOCAL_PROFILE_FILE.write_text('{invalid json',encoding='utf-8')
    with pytest.raises(ValueError):
        c.write_profiles('A',profile())
    assert m.LOCAL_PROFILE_FILE.read_text(encoding='utf-8')=='{invalid json'
    assert c.load_profiles() is False


def test_nas_creates_lock_writes_cache_and_deletes(state,tmp_path):
    c,m,_=state
    share = tmp_path / 'nas'
    share.mkdir()
    c.storage_mode.set('nas')
    c.nas_folder.set(str(share))
    c.write_profiles('A',profile())
    assert not (share / '.Tonmaschine_write_lock').exists()
    assert json.loads((share/'Tonmaschine_Profile.json').read_text(encoding='utf-8'))['A'] == profile()
    assert json.loads(m.CACHE_FILE.read_text(encoding='utf-8'))['A'] == profile()
    c.write_profiles('A',delete=True)
    assert json.loads((share/'Tonmaschine_Profile.json').read_text(encoding='utf-8')) == {}


def test_offline_nas_reads_only_cache(state,tmp_path):
    c,m,root=state
    root.mkdir(parents=True)
    m.CACHE_FILE.write_text(json.dumps({'Cached':profile()}),encoding='utf-8')
    c.storage_mode.set('nas')
    c.nas_folder.set(str(tmp_path / 'no_such_share'))
    assert c.load_profiles() is False
    assert 'Cached' in c.profiles
    with pytest.raises(OSError):
        c.write_profiles('New',profile())
    assert not (tmp_path / 'no_such_share').exists()


def test_cache_failure_does_not_report_nas_save_failure(state,tmp_path,monkeypatch):
    c,m,_=state
    share = tmp_path / 'nas'
    share.mkdir()
    c.storage_mode.set('nas')
    c.nas_folder.set(str(share))
    actual = m.atomic_write_json
    def cache_error(path, obj):
        if Path(path) == m.CACHE_FILE:
            raise OSError('cache not writable')
        return actual(path,obj)
    monkeypatch.setattr(m,'atomic_write_json',cache_error)
    c.write_profiles('Saved',profile())
    assert 'Saved' in json.loads((share/'Tonmaschine_Profile.json').read_text(encoding='utf-8'))
    assert 'Cache' in c.nas_state.get() or 'Cache'.lower() in c.nas_state.get().lower()


def test_local_nas_switch_keeps_both_data_files(state,tmp_path):
    c,m,_=state
    c.write_profiles('Local',profile())
    share=tmp_path/'nas'
    share.mkdir()
    c.storage_mode.set('nas')
    c.nas_folder.set(str(share))
    c.write_profiles('NAS',profile(7))
    c.storage_mode.set('local')
    assert c.load_profiles() is True
    assert 'Local' in c.profiles and 'NAS' not in c.profiles
    assert 'NAS' in json.loads((share/'Tonmaschine_Profile.json').read_text(encoding='utf-8'))


def test_manual_import_reads_selected_file(state,tmp_path,monkeypatch):
    c,m,_=state
    src=tmp_path/'old.json'
    src.write_text(json.dumps({'MyOld':profile(7)}),encoding='utf-8')
    monkeypatch.setattr(m.filedialog,'askopenfilename',lambda **kwargs: str(src))
    monkeypatch.setattr(m.messagebox,'askyesno',lambda *args,**kwargs: True)
    monkeypatch.setattr(m.messagebox,'showinfo',lambda *args,**kwargs: None)
    c.import_legacy_profiles()
    assert json.loads(m.LOCAL_PROFILE_FILE.read_text(encoding='utf-8'))['MyOld']['mode']==7


def test_import_cancel_does_not_write(state,tmp_path,monkeypatch):
    c,m,_=state
    monkeypatch.setattr(m.filedialog,'askopenfilename',lambda **kwargs: '')
    c.import_legacy_profiles()
    assert not m.LOCAL_PROFILE_FILE.exists()


class DummyControl:
    def __init__(self):
        self.state=None
    def configure(self,**kwargs):
        self.state=kwargs.get('state',self.state)


class DummyRoot:
    def __init__(self):
        self.after_calls=[]
    def after(self,ms,callback):
        self.after_calls.append((ms,callback))


def test_network_error_disables_controls(mod):
    c=mod.Controller.__new__(mod.Controller)
    c.events=queue.Queue()
    c.events.put(('error','socket error'))
    c.root=DummyRoot()
    c.ready=True
    c.connection_text=Var('connected')
    c.connection_label=DummyControl()
    c.status=Var('')
    c.mode_busy=True
    c.profile_busy=True
    toggle=DummyControl()
    c.toggle_controls={'b_drc':(Var(False),toggle,'set_drc')}
    c.update_mode_buttons=lambda:None
    c.update_profile_highlight=lambda:None
    c.poll()
    assert c.ready is False and toggle.state=='disabled'
    assert c.root.after_calls and 'Fehler' in c.status.get()


def test_invalid_message_keeps_poll_scheduled(mod):
    c=mod.Controller.__new__(mod.Controller)
    c.events=queue.Queue()
    c.events.put(('response',{'result':'ok','data':{'i_vol':'not-an-int'}}))
    c.root=DummyRoot()
    c.status=Var('')
    c.pending={}
    c.updating=False
    c.vol_scale=Var()
    c.poll()
    assert c.root.after_calls
    assert c.updating is False
    assert 'Ungültige' in c.status.get()


def test_installer_preserves_existing_config():
    script=(Path(__file__).resolve().parents[1]/'Tonmaschine.iss').read_text(encoding='utf-8')
    assert 'function ExistingConfig: Boolean;' in script
    assert 'if FileExists(ConfigFile) then' in script
    assert "Result := ExistingConfig and ((PageID = SoundbarPage.ID)" in script
    assert 'Log(\'Konfiguration existiert bereits und bleibt unveraendert:' in script


def test_two_nas_writers_keep_both_profiles(state,tmp_path):
    c,m,_ = state
    share=tmp_path/'shared'
    share.mkdir()
    c.storage_mode.set('nas')
    c.nas_folder.set(str(share))
    other=m.Controller.__new__(m.Controller)
    other.storage_mode=Var('nas')
    other.nas_folder=Var(str(share))
    other.nas_state=Var('')
    other.profiles={}
    other.update_profile_list=lambda:None
    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs=[pool.submit(c.write_profiles,'A',profile()),pool.submit(other.write_profiles,'B',profile(7))]
        for job in jobs:
            job.result()
    content=json.loads((share/'Tonmaschine_Profile.json').read_text(encoding='utf-8'))
    assert set(content)=={'A','B'}
    assert not (share/'.Tonmaschine_write_lock').exists()


# Audio/source feature tests for the v1.1 preview.
def test_audio_signal_preserves_device_label_without_channel_guess(mod):
    assert mod.normalize_audio_signal(' PCM ') == 'PCM'
    assert mod.normalize_audio_signal('DOLBY AUDIO') == 'DOLBY AUDIO'
    assert mod.normalize_audio_signal('DOLBY ATMOS') == 'DOLBY ATMOS'
    assert mod.normalize_audio_signal('NO SIGNAL') == 'NO SIGNAL'
    assert mod.normalize_audio_signal(None) == '–'


def test_input_choices_only_include_device_advertised_and_observed(mod):
    choices = mod.input_choices([0, 1, 15, 6, 10, 19, 111, 1], 20, [20])
    assert choices == [
        ('WLAN', 0), ('Bluetooth', 1), ('Optisch / ARC', 15),
        ('HDMI 1', 6), ('HDMI 2', 10), ('USB', 19), ('HDMI eARC', 20)]
    assert mod.input_choices([], None) == []


def test_selector_keeps_observed_earc_when_missing_from_input_list(mod):
    class Menu:
        def __init__(self):
            self.opts = {}
        def configure(self, **kwargs):
            self.opts.update(kwargs)
    c = mod.Controller.__new__(mod.Controller)
    c.input_reported_list = False
    c.available_inputs = []
    c.observed_inputs = []
    c.last_input = None
    c.input_busy = False
    c.ready = True
    c.source_menu = Menu()
    c.source_var = Var('–')
    c.input_options_by_name = {}
    c.update_input_status('SPK_LIST_VIEW_INFO', {'i_curr_func': 20})
    assert c.source_var.get() == 'HDMI eARC'
    assert c.source_menu.opts['state'] == 'disabled'
    c.update_input_status('FUNC_VIEW_INFO',
                          {'ai_func_list': [0, 1, 15, 6, 10, 19], 'i_curr_func': 20})
    assert c.input_options_by_name['HDMI eARC'] == 20
    assert c.input_options_by_name['Bluetooth'] == 1
    assert c.source_menu.opts['state'] == 'normal'
    c.update_input_status('FUNC_VIEW_INFO', {'i_curr_func': 1})
    assert c.source_var.get() == 'Bluetooth'
    assert c.input_options_by_name['HDMI eARC'] == 20


def test_switch_source_only_calls_set_func_and_reads_back(mod, monkeypatch):
    class Menu:
        def configure(self, **kwargs):
            pass
    calls = []
    class Client:
        def set_func(self, code):
            calls.append(('set_func', code))
        def get_func(self):
            calls.append(('get_func',))
        def get_info(self):
            calls.append(('get_info',))
    c = mod.Controller.__new__(mod.Controller)
    c.ready = True
    c.updating = False
    c.input_busy = False
    c.last_input = 20
    c.input_options_by_name = {'Bluetooth': 1}
    c.source_menu = Menu()
    c.status = Var('')
    c.client = Client()
    c.events = queue.Queue()
    c.work = lambda f: f()
    monkeypatch.setattr(mod.time, 'sleep', lambda _: None)
    c.choose_input('Bluetooth')
    assert calls == [('set_func', 1), ('get_func',), ('get_info',)]
    assert c.events.get_nowait() == ('input_done', 1)
