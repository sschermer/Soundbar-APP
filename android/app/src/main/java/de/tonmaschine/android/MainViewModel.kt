package de.tonmaschine.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tonmaschine.android.model.*
import de.tonmaschine.android.network.SoundbarClient
import de.tonmaschine.android.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    private val nasSettings = NasSettingsStore(application)
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
    private val _source = MutableStateFlow(nasSettings.profileSource())
    val source = _source.asStateFlow()
    private val _nasConnection = MutableStateFlow(NasConnection())
    val nasConnection = _nasConnection.asStateFlow()
    private val _nasOnline = MutableStateFlow(false)
    val nasOnline = _nasOnline.asStateFlow()
    private val _nasStatus = MutableStateFlow("NAS nicht konfiguriert")
    val nasStatus = _nasStatus.asStateFlow()
    private val _profileBusy = MutableStateFlow(false)
    val profileBusy = _profileBusy.asStateFlow()
    private var client: SoundbarClient? = null
    private var generation = 0

    init {
        try { _nasConnection.value = nasSettings.loadConnection() }
        catch (_: Exception) { _nasStatus.value = "NAS-Zugangsdaten konnten nicht entschlüsselt werden. Bitte erneut eingeben." }
        if (_source.value == ProfileSource.NAS) {
            try { _profiles.value = nasSettings.loadCache() }
            catch (_: Exception) { _nasStatus.value = "NAS-Lesekopie beschädigt" }
            if (_nasConnection.value.folder.isNotBlank()) refreshProfiles()
        } else {
            try { _profiles.value = store.load() }
            catch (e: Exception) { _message.value = "Lokale Profildaten beschädigt: ${e.message}" }
        }
    }

    fun updateIp(text: String) { _ip.value = text.trim() }

    /** Backup uses the already decrypted NAS settings in memory, never logging the password. */
    fun configurationBackup(): ConfigurationBackup = ConfigurationBackup(
        soundbarIp = _ip.value.trim(),
        nas = _nasConnection.value,
        source = _source.value
    )

    /** Restore portable settings; shared NAS profiles are retrieved from the NAS afterwards. */
    fun restoreConfiguration(backup: ConfigurationBackup): Boolean {
        if (_profileBusy.value) {
            _message.value = "NAS arbeitet noch – Import später wiederholen"
            return false
        }
        return try {
            backup.validate()
            val previousNas = _nasConnection.value
            // Validate before writing anything. Passwords go to encrypted AndroidKeystore storage.
            nasSettings.saveConnection(backup.nas)
            store.saveIp(backup.soundbarIp)
            nasSettings.saveSource(backup.source)
            if (previousNas != backup.nas) nasSettings.saveCache(emptyMap())
            _nasConnection.value = backup.nas
            _ip.value = backup.soundbarIp
            _source.value = backup.source
            _nasOnline.value = false
            _selectedProfile.value = null
            if (backup.source == ProfileSource.NAS) {
                _profiles.value = emptyMap()
                if (backup.nas.folder.isNotBlank()) refreshProfiles()
            } else {
                _profiles.value = store.load()
                _nasStatus.value = "Lokale Profile ausgewählt"
            }
            _message.value = "Konfiguration wiederhergestellt"
            true
        } catch (_: Exception) {
            _message.value = "Import fehlgeschlagen – Einstellungen prüfen"
            false
        }
    }

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

    fun setPower(turnOn: Boolean) {
        if (_device.value.connection != Connection.CONNECTED) {
            _message.value = "Soundbar nicht erreichbar – keine Netzverbindung"
            return
        }
        val queued = client?.send(SoundbarCommands.power(turnOn)) ?: false
        if (!queued) {
            _message.value = "Ein-/Ausschaltbefehl konnte nicht gesendet werden"
            return
        }
        _message.value = if (turnOn) "Einschaltbefehl gesendet – warte auf Bestätigung" else
            "Ausschaltbefehl gesendet – warte auf Bestätigung"
        val requestGeneration = generation
        viewModelScope.launch {
            // Polling a sleeping device can wake it. Await unsolicited confirmation.
            delay(5500)
            if (generation == requestGeneration &&
                _device.value.connection == Connection.CONNECTED &&
                _device.value.powerOn != turnOn) {
                _message.value = "Keine Bestätigung der Soundbar – Ein-/Ausschalten am Gerät prüfen"
            }
        }
    }
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

    fun selectProfileSource(next: ProfileSource) {
        if (_profileBusy.value || next == _source.value) return
        try { nasSettings.saveSource(next) }
        catch (e: Exception) { _nasStatus.value = "Speicherort konnte nicht gespeichert werden"; return }
        _source.value = next
        _selectedProfile.value = null
        _nasOnline.value = false
        if (next == ProfileSource.LOCAL) {
            try {
                _profiles.value = store.load()
                _nasStatus.value = "Lokale Profile ausgewählt"
            } catch (e: Exception) {
                _profiles.value = emptyMap()
                _nasStatus.value = "Lokale Profildatei beschädigt"
            }
        } else {
            try { _profiles.value = nasSettings.loadCache() }
            catch (_: Exception) { _profiles.value = emptyMap() }
            refreshProfiles()
        }
    }

    fun saveNasConnection(info: NasConnection) {
        if (_profileBusy.value) return
        try {
            nasSettings.saveConnection(info)
            _nasConnection.value = info.copy(folder = info.folder.trim(), user = info.user.trim(), domain = info.domain.trim())
            // Selection changes only after credentials were successfully saved.
            nasSettings.saveSource(ProfileSource.NAS)
            _source.value = ProfileSource.NAS
            _nasOnline.value = false
            _selectedProfile.value = null
            try { _profiles.value = nasSettings.loadCache() }
            catch (_: Exception) { _profiles.value = emptyMap() }
            refreshProfiles()
        } catch (e: Exception) {
            _nasStatus.value = "NAS-Einstellungen ungültig: ${e.message ?: "Bitte Pfad prüfen"}"
        }
    }

    private fun remoteError(e: Exception): String {
        val password = _nasConnection.value.password
        val description = e.message?.take(180).orEmpty()
        return if (password.isNotEmpty()) description.replace(password, "***") else description
    }

    fun refreshProfiles() {
        if (_profileBusy.value || _source.value != ProfileSource.NAS) return
        val connection = _nasConnection.value
        if (connection.folder.isBlank()) {
            _nasOnline.value = false
            _nasStatus.value = "Bitte NAS-Freigabe unter IP / Einstellungen einrichten"
            return
        }
        _profileBusy.value = true
        _nasStatus.value = "NAS wird geprüft …"
        viewModelScope.launch {
            try {
                val remote = withContext(Dispatchers.IO) {
                    SmbProfileFiles(connection).use { SharedProfileRepository(it).load() }
                }
                if (_source.value == ProfileSource.NAS && _nasConnection.value == connection) {
                    _profiles.value = remote
                    _nasOnline.value = true
                    _nasStatus.value = "NAS verbunden · ${remote.size} Profile"
                    try { withContext(Dispatchers.IO) { nasSettings.saveCache(remote) } }
                    catch (_: Exception) { _nasStatus.value += " · Cache nicht aktualisiert" }
                }
            } catch (e: Exception) {
                if (_source.value == ProfileSource.NAS) {
                    _nasOnline.value = false
                    _nasStatus.value = "NAS nicht erreichbar: ${remoteError(e)}"
                    // Offline: cached profiles remain viewable/applicable, but never writable.
                    try { _profiles.value = nasSettings.loadCache() }
                    catch (_: Exception) { _profiles.value = emptyMap() }
                }
            } finally { _profileBusy.value = false }
        }
    }

    val canEditProfiles: Boolean
        get() = _source.value == ProfileSource.LOCAL || _nasOnline.value

    private fun currentProfile(name: String): SoundProfile? {
        val state = _device.value
        if (name.isBlank() || state.connection != Connection.CONNECTED ||
            state.currentMode !in Catalog.modes.values ||
            Catalog.levels.any { it.field !in state.levels } ||
            state.drc == null || state.nightTime == null || state.surround == null) {
            _message.value = "Profilname oder Gerätezustand unvollständig"
            return null
        }
        return SoundProfile(state.currentMode!!, state.levels, state.drc, state.nightTime, state.surround)
    }

    fun saveProfile(name: String, overwrite: Boolean = false) {
        val clean = name.trim()
        val profile = currentProfile(clean) ?: return
        if (_profileBusy.value || !canEditProfiles) {
            _message.value = "NAS offline: Speichern nicht möglich"; return
        }
        if (clean in _profiles.value && !overwrite) {
            _message.value = "Vorhandenes Profil nur nach Bestätigung überschreiben"; return
        }
        if (_source.value == ProfileSource.LOCAL) {
            try {
                val updated = _profiles.value + (clean to profile)
                store.save(updated)
                _profiles.value = updated
                _selectedProfile.value = clean
                _message.value = "Profil gespeichert: $clean"
            } catch (e: Exception) { _message.value = "Speichern fehlgeschlagen: ${e.message}" }
        } else remoteUpdate(clean, profile, overwrite)
    }

    fun deleteProfile(name: String) {
        if (_profileBusy.value || !canEditProfiles) {
            _message.value = "NAS offline: Löschen nicht möglich"; return
        }
        if (_source.value == ProfileSource.LOCAL) {
            try {
                val updated = _profiles.value - name
                store.save(updated)
                _profiles.value = updated
                if (_selectedProfile.value == name) _selectedProfile.value = null
                _message.value = "Profil gelöscht: $name"
            } catch (e: Exception) { _message.value = "Löschen fehlgeschlagen: ${e.message}" }
        } else remoteUpdate(name, null)
    }

    private fun remoteUpdate(name: String, updatedProfile: SoundProfile?, overwrite: Boolean = false) {
        if (_profileBusy.value || !_nasOnline.value) return
        val connection = _nasConnection.value
        _profileBusy.value = true
        _message.value = "NAS-Profil wird aktualisiert …"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SmbProfileFiles(connection).use {
                        SharedProfileRepository(it).update(name, updatedProfile, replaceExisting = overwrite)
                    }
                }
                if (_source.value == ProfileSource.NAS && _nasConnection.value == connection) {
                    _profiles.value = result
                    _nasOnline.value = true
                    _selectedProfile.value = if (updatedProfile == null) null else name
                    _nasStatus.value = "NAS verbunden · ${result.size} Profile"
                    _message.value = if (updatedProfile == null) "Profil auf NAS gelöscht: $name" else "Profil auf NAS gespeichert: $name"
                    try { withContext(Dispatchers.IO) { nasSettings.saveCache(result) } }
                    catch (_: Exception) { _nasStatus.value += " · Cache nicht aktualisiert" }
                }
            } catch (e: Exception) {
                _message.value = "NAS-Schreibvorgang nicht bestätigt: ${remoteError(e)}"
                // Never silently fall back to local storage on a write failure.
                _nasOnline.value = false
                _nasStatus.value = "NAS prüfen und Profile aktualisieren"
            } finally { _profileBusy.value = false }
        }
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
