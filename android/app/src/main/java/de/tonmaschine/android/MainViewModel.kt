package de.tonmaschine.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tonmaschine.android.model.*
import de.tonmaschine.android.network.SoundbarClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val _device = MutableStateFlow(DeviceState())
    val device = _device.asStateFlow()
    private val _message = MutableStateFlow("Nicht verbunden")
    val message = _message.asStateFlow()
    private val _ip = MutableStateFlow(store.ip())
    val ip = _ip.asStateFlow()
    private val _profiles = MutableStateFlow<Map<String, SoundProfile>>(emptyMap())
    val profiles = _profiles.asStateFlow()
    private val _selectedProfile = MutableStateFlow<String?>(null)
    val selectedProfile = _selectedProfile.asStateFlow()
    private var client: SoundbarClient? = null
    private var generation = 0

    init {
        try { _profiles.value = store.load() }
        catch (ex: Exception) { _message.value = "Profildaten beschädigt: ${ex.message}" }
    }
    fun updateIp(text: String) { _ip.value = text.trim() }

    fun connect() {
        if (!Catalog.validIp(ip.value)) {
            _message.value = "Bitte eine gültige IPv4-Adresse eingeben"
            return
        }
        try { store.saveIp(ip.value) }
        catch (e: Exception) { _message.value = "IP konnte nicht gespeichert werden: ${e.message}"; return }
        generation++
        val id = generation
        client?.stop()
        _device.value = DeviceState(connection = Connection.CONNECTING)
        _message.value = "Verbindung wird hergestellt …"
        client = SoundbarClient(ip.value, scope = viewModelScope,
            onPacket = { packet ->
                if (generation == id) _device.update { DeviceReducer.reduce(it, packet) }
            },
            onConnection = { online, error ->
                if (generation == id) {
                    _device.update { it.copy(connection = if (online) Connection.CONNECTED else Connection.CONNECTING, error = error) }
                    _message.value = if (online) "Verbunden – synchronisiere" else error?.let { "Wiederverbinden: $it" } ?: "Verbinde …"
                }
            }).also { it.start() }
    }
    fun refresh() { client?.refresh() }
    private fun send(packet: JSONObject, readBack: String? = null) {
        if (_device.value.connection != Connection.CONNECTED) return
        val ok = client?.send(packet) ?: false
        if (!ok) _message.value = "Befehl konnte nicht gesendet werden"
        else if (readBack != null) viewModelScope.launch {
            delay(450)
            client?.send(SoundbarCommands.get(readBack))
        }
    }
    private fun changed() { _selectedProfile.value = null }
    fun setVolume(n: Int) = send(SoundbarCommands.volume(n))
    fun chooseSource(id: Int) {
        if (!_device.value.canSelectInput || id !in _device.value.selectableInputs) return
        changed()
        send(SoundbarCommands.source(id), "FUNC_VIEW_INFO")
    }
    fun chooseMode(id: Int) {
        if (id !in _device.value.selectableModes.values) return
        changed()
        send(SoundbarCommands.mode(id), "EQ_VIEW_INFO")
    }
    fun setOption(field: String, value: Boolean) {
        changed()
        send(SoundbarCommands.option(field, value), "SETTING_VIEW_INFO")
    }
    fun setLevel(spec: LevelSpec, value: Int) {
        changed()
        send(SoundbarCommands.level(spec, value), "SETTING_VIEW_INFO")
    }
    fun saveProfile(name: String) {
        val clean = name.trim()
        val state = _device.value
        if (clean.isBlank() || state.connection != Connection.CONNECTED || state.currentMode !in Catalog.modes.values ||
            Catalog.levels.any { it.field !in state.levels } ||
            state.drc == null || state.nightTime == null || state.surround == null) {
            _message.value = "Profilname oder Gerätezustand unvollständig"; return
        }
        val profile = SoundProfile(state.currentMode!!, state.levels, state.drc, state.nightTime, state.surround)
        val changed = _profiles.value + (clean to profile)
        try {
            store.save(changed)
            _profiles.value = changed
            _selectedProfile.value = clean
            _message.value = "Profil gespeichert: $clean"
        } catch (e: Exception) { _message.value = "Speichern fehlgeschlagen: ${e.message}" }
    }
    fun deleteProfile(name: String) {
        val updated = _profiles.value - name
        try {
            store.save(updated)
            _profiles.value = updated
            if (_selectedProfile.value == name) _selectedProfile.value = null
            _message.value = "Profil gelöscht: $name"
        } catch (e: Exception) { _message.value = "Löschen fehlgeschlagen: ${e.message}" }
    }
    fun applyProfile(name: String) {
        val profile = _profiles.value[name] ?: return
        if (!profile.valid() || _device.value.connection != Connection.CONNECTED) return
        viewModelScope.launch {
            client?.send(SoundbarCommands.mode(profile.mode))
            Catalog.levels.forEach { spec ->
                profile.levels[spec.field]?.let { client?.send(SoundbarCommands.level(spec, it)) }
            }
            client?.send(SoundbarCommands.option("b_drc", profile.drc))
            client?.send(SoundbarCommands.option("b_night_time", profile.nightTime))
            profile.surround?.let { client?.send(SoundbarCommands.option("b_rear", it)) }
            delay(700)
            client?.refresh()
            _selectedProfile.value = name
            _message.value = "Profil übertragen – Rückmeldung abwarten"
        }
    }
    override fun onCleared() { client?.stop(); super.onCleared() }
}
