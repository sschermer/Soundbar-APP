package de.tonmaschine.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.tonmaschine.android.model.ProfileCodec
import de.tonmaschine.android.model.SoundProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Only the NAS path, user and domain are stored in plain local preferences.
 * The NAS password is AES-GCM encrypted under a non-exportable AndroidKeyStore key.
 * No cloud, backup, analytics or background server.
 */
class NasSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("tonmaschine_nas", Context.MODE_PRIVATE)
    private val keyAlias = "tonmaschine_smb_password_v1"

    fun profileSource(): ProfileSource =
        if (prefs.getString("profile_source", "local") == "nas") ProfileSource.NAS else ProfileSource.LOCAL

    fun saveSource(source: ProfileSource) {
        check(prefs.edit().putString("profile_source", source.name.lowercase()).commit())
    }

    fun loadConnection(): NasConnection = NasConnection(
        folder = prefs.getString("folder", "").orEmpty(),
        user = prefs.getString("user", "").orEmpty(),
        domain = prefs.getString("domain", "").orEmpty(),
        password = prefs.getString("password_secure", null)?.let(::decrypt).orEmpty()
    )

    fun saveConnection(info: NasConnection) {
        // Validate before persisting; do not allow a malformed UNC path or embedded credentials.
        if (info.folder.isNotBlank()) {
            NasPath.folderUrl(info.folder)
        } else {
            require(info.user.isBlank() && info.domain.isBlank() && info.password.isEmpty()) {
                "NAS-Zugangsdaten ohne NAS-Pfad"
            }
        }
        val encrypted = if (info.password.isEmpty()) null else encrypt(info.password)
        check(prefs.edit()
            .putString("folder", info.folder.trim())
            .putString("user", info.user.trim())
            .putString("domain", info.domain.trim())
            .putString("password_secure", encrypted).commit())
    }

    fun loadCache(): Map<String, SoundProfile> =
        prefs.getString("nas_cache", null)?.let(ProfileCodec::decode) ?: emptyMap()

    fun saveCache(profiles: Map<String, SoundProfile>) {
        check(prefs.edit().putString("nas_cache", ProfileCodec.encode(profiles)).commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(cleartext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(cleartext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(raw: String): String {
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 28) { "Invalid encrypted NAS credentials" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}
