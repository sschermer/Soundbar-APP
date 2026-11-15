package de.tonmaschine.android.storage

import de.tonmaschine.android.model.ProfileCodec
import de.tonmaschine.android.model.SoundProfile
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.util.Properties
import java.util.UUID

enum class ProfileSource { LOCAL, NAS }

data class NasConnection(
    val folder: String = "",
    val user: String = "",
    val domain: String = "",
    val password: String = ""
)

/** Converts a Windows UNC directory or SMB URL into a credential-free SMB directory URL. */
object NasPath {
    fun folderUrl(folder: String): String {
        val text = folder.trim().replace('\\', '/')
        val raw = when {
            text.startsWith("smb://", ignoreCase = true) -> text.substring(6)
            text.startsWith("//") -> text.substring(2)
            else -> throw IllegalArgumentException("NAS-Pfad muss mit \\\\ oder smb:// beginnen")
        }
        val all = raw.split('/').filter(String::isNotEmpty)
        val parts = if (all.lastOrNull().equals("Tonmaschine_Profile.json", true)) all.dropLast(1) else all
        require(parts.size >= 2) { "Bitte Server und Freigabe angeben, z. B. \\\\NAS\\Profile" }
        require(parts[0].matches(Regex("[A-Za-z0-9._-]+"))) { "Ungültiger Servername" }
        require(parts.drop(1).all {
            it.isNotBlank() && it != "." && it != ".." && !it.contains('@') &&
                !it.contains('?') && !it.contains('#') && !it.contains('%') &&
                it.none { ch -> ch.code < 32 }
        }) { "Ungültiger Freigabe-/Ordnername" }
        return "smb://" + parts[0] + "/" +
            parts.drop(1).joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            } + "/"
    }
}

/** Small replaceable file abstraction: tests use an in-memory NAS, production uses SMB2/SMB3. */
interface RemoteProfileFiles : Closeable {
    fun ensureFolder()
    /** Null only when the profile file is absent; errors must throw. */
    fun read(name: String): String?
    /** Lock shared with Windows Tonmaschine's .Tonmaschine_write_lock directory. */
    fun tryLock(): Boolean
    fun unlock()
    /** Must not replace the original until all bytes have been written. */
    fun writeAtomically(name: String, text: String)
}

class SharedProfileRepository(
    private val files: RemoteProfileFiles,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val lockAttempts: Int = 41
) {
    companion object { const val FILE_NAME = "Tonmaschine_Profile.json" }

    fun load(): Map<String, SoundProfile> {
        files.ensureFolder()
        val raw = files.read(FILE_NAME)
            ?: throw IOException("Tonmaschine_Profile.json auf NAS nicht gefunden; keine Datei wird angelegt")
        return ProfileCodec.decode(raw)
    }

    /** Read-modify-write inside the *same directory lock* the Windows app uses. */
    fun update(name: String, newProfile: SoundProfile?, replaceExisting: Boolean = false): Map<String, SoundProfile> {
        require(name.isNotBlank())
        files.ensureFolder()
        var acquired = false
        for (attempt in 0 until lockAttempts) {
            if (files.tryLock()) { acquired = true; break }
            if (attempt < lockAttempts - 1) sleep(150)
        }
        if (!acquired) throw IOException("Der NAS-Profilordner ist noch gesperrt (.Tonmaschine_write_lock)")
        try {
            val latest = load().toMutableMap()
            if (newProfile == null) {
                if (latest.remove(name) == null) throw IOException("Profil auf dem NAS nicht mehr vorhanden")
            } else {
                require(newProfile.valid())
                if (name in latest && !replaceExisting) throw IOException("Profil existiert bereits – Überschreiben bestätigen")
                latest[name] = newProfile
            }
            files.writeAtomically(FILE_NAME, ProfileCodec.encode(latest))
            return latest
        } finally {
            files.unlock()
        }
    }
}

/**
 * Direct SMB access. Do not put credentials in URLs or logs.
 * SMB1 is explicitly disabled; SMB2/3 is supported by jcifs-ng.
 */
class SmbProfileFiles(private val connection: NasConnection) : RemoteProfileFiles {
    private val directoryUrl = NasPath.folderUrl(connection.folder)
    private val baseContext: CIFSContext = BaseContext(PropertyConfiguration(Properties().apply {
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.connTimeout", "5000")
        setProperty("jcifs.smb.client.responseTimeout", "9000")
        setProperty("jcifs.smb.client.soTimeout", "12000")
        setProperty("jcifs.smb.client.disablePlainTextPasswords", "true")
        setProperty("jcifs.smb.lmCompatibility", "3")
    }))
    private val context: CIFSContext = if (connection.user.isBlank()) {
        baseContext.withAnonymousCredentials()
    } else {
        baseContext.withCredentials(NtlmPasswordAuthenticator(
            connection.domain, connection.user, connection.password
        ))
    }

    private fun resource(name: String) = SmbFile(directoryUrl + name, context)

    override fun ensureFolder() {
        SmbFile(directoryUrl, context).use { folder ->
            if (!folder.exists() || !folder.isDirectory)
                throw IOException("NAS-Ordner nicht erreichbar oder keine Leseberechtigung")
        }
    }

    override fun read(name: String): String? = resource(name).use { file ->
        if (!file.exists()) return null
        file.openInputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 1024 * 1024) throw IOException("NAS-Profildatei zu groß")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    override fun tryLock(): Boolean = resource(".Tonmaschine_write_lock/").use { lock ->
        try {
            lock.mkdir()
            true
        } catch (e: Exception) {
            if (lock.exists()) false else throw e
        }
    }

    override fun unlock() {
        resource(".Tonmaschine_write_lock/").use { it.delete() }
    }

    override fun writeAtomically(name: String, text: String) {
        val temporary = name + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp"
        var renamed = false
        try {
            resource(temporary).use { tmp ->
                tmp.openOutputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            resource(temporary).use { tmp ->
                resource(name).use { destination ->
                    tmp.renameTo(destination, true)
                    renamed = true
                }
            }
        } finally {
            if (!renamed) {
                try { resource(temporary).use { if (it.exists()) it.delete() } }
                catch (_: Exception) { /* Keep original error and leave a recoverable temp file. */ }
            }
        }
    }

    override fun close() {
        try { context.close() }
        finally { baseContext.close() }
    }
}
