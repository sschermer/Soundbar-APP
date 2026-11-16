package de.tonmaschine.android.storage

import de.tonmaschine.android.model.Catalog
import org.json.JSONObject
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Portable settings only (no profile content). Backups can deliberately contain
 * the SMB password in CLEARTEXT: only user-initiated SAF export is supported.
 */
enum class BackupFormat { JSON, TXT }

data class ConfigurationBackup(
    val soundbarIp: String,
    val nas: NasConnection,
    val source: ProfileSource
) {
    fun validate() {
        require(soundbarIp.isEmpty() || (soundbarIp.length <= 15 && Catalog.validIp(soundbarIp))) {
            "Soundbar-IP ist ungültig"
        }
        require(nas.folder.length <= 1024 && nas.user.length <= 255 &&
                nas.domain.length <= 255 && nas.password.length <= 1024) {
            "Ein Feld ist zu lang"
        }
        require(nas.user.none { it.code < 32 } && nas.domain.none { it.code < 32 }) {
            "NAS-Benutzer oder Domäne enthält Steuerzeichen"
        }
        if (nas.folder.isNotEmpty()) {
            NasPath.folderUrl(nas.folder)
        } else {
            require(nas.user.isEmpty() && nas.domain.isEmpty() && nas.password.isEmpty()) {
                "NAS-Zugangsdaten ohne NAS-Pfad"
            }
            require(source == ProfileSource.LOCAL) {
                "NAS-Speicherung ohne NAS-Pfad"
            }
        }
    }
}

object ConfigurationBackupCodec {
    private const val TYPE = "tonmaschine-android-settings"
    private const val VERSION = "1"
    const val MAX_BYTES = 64 * 1024

    private fun fields(backup: ConfigurationBackup, includePassword: Boolean): Map<String, String> {
        backup.validate()
        val fields = linkedMapOf(
            "type" to TYPE,
            "version" to VERSION,
            "profileSource" to backup.source.name.lowercase()
        )
        if (backup.soundbarIp.isNotBlank()) fields["soundbarIp"] = backup.soundbarIp
        if (backup.nas.folder.isNotBlank()) fields["nasFolder"] = backup.nas.folder
        if (backup.nas.user.isNotBlank()) fields["nasUser"] = backup.nas.user
        if (backup.nas.domain.isNotBlank()) fields["nasDomain"] = backup.nas.domain
        if (includePassword && backup.nas.password.isNotEmpty())
            fields["nasPassword"] = backup.nas.password
        return fields
    }

    fun encode(backup: ConfigurationBackup, format: BackupFormat, includePassword: Boolean): String {
        val fields = fields(backup, includePassword)
        val text = when (format) {
            BackupFormat.JSON -> JSONObject(fields).toString(2) + "\n"
            BackupFormat.TXT -> StringWriter().also { writer ->
                val props = Properties()
                fields.forEach { (key, value) -> props.setProperty(key, value) }
                props.store(writer, "Tonmaschine Android Konfiguration. NAS-Passwort, falls vorhanden, ist unverschluesselt!")
            }.toString()
        }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sicherung zu groß" }
        return text
    }

    fun decode(text: String): ConfigurationBackup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Sicherung zu groß" }
        val trimmed = text.trimStart().removePrefix("\uFEFF").trimStart()
        val data: Map<String, String> = if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val output = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.get(key)
                require(value is String) { "Ungültiger Datentyp für $key" }
                output[key] = value
            }
            output
        } else {
            val props = Properties()
            props.load(StringReader(trimmed))
            props.stringPropertyNames().associateWith { props.getProperty(it) }
        }
        require(data["type"] == TYPE && data["version"] == VERSION) {
            "Keine unterstützte Tonmaschine-Konfigurationsdatei"
        }
        val profileSource = when (data["profileSource"]) {
            "nas" -> ProfileSource.NAS
            "local" -> ProfileSource.LOCAL
            else -> throw IllegalArgumentException("Ungültiger Profilspeicherort")
        }
        val backup = ConfigurationBackup(
            soundbarIp = data["soundbarIp"].orEmpty(),
            nas = NasConnection(
                folder = data["nasFolder"].orEmpty(),
                user = data["nasUser"].orEmpty(),
                domain = data["nasDomain"].orEmpty(),
                password = data["nasPassword"].orEmpty()
            ),
            source = profileSource
        )
        backup.validate()
        return backup
    }
}
