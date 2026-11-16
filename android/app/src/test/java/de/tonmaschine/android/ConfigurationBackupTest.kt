package de.tonmaschine.android

import de.tonmaschine.android.storage.*
import org.junit.Assert.*
import org.junit.Test

class ConfigurationBackupTest {
    private val sample = ConfigurationBackup(
        soundbarIp = "192.168.178.99",
        nas = NasConnection(
            folder = "\\\\192.168.178.1\\FRITZ.NAS\\Tonmaschine",
            user = "Steffen-Ä",
            domain = "WORKGROUP",
            password = "a=ß\\b:\\nsecond line"
        ),
        source = ProfileSource.NAS
    )

    @Test fun jsonRoundTripWithCredentials() {
        val json = ConfigurationBackupCodec.encode(sample, BackupFormat.JSON, true)
        assertTrue(json.contains("nasPassword"))
        assertEquals(sample, ConfigurationBackupCodec.decode(json))
    }

    @Test fun txtRoundTripWithEscapedValues() {
        val text = ConfigurationBackupCodec.encode(sample, BackupFormat.TXT, true)
        assertTrue(text.contains("nasPassword"))
        assertEquals(sample, ConfigurationBackupCodec.decode(text))
    }

    @Test fun passwordIsExcludedUnlessExplicitlyRequested() {
        for (format in BackupFormat.values()) {
            val text = ConfigurationBackupCodec.encode(sample, format, false)
            assertFalse(text.contains(sample.nas.password))
            assertFalse(text.contains("nasPassword"))
            assertEquals(sample.copy(nas = sample.nas.copy(password = "")),
                ConfigurationBackupCodec.decode(text))
        }
    }

    @Test fun localOnlyAndEmptyOptionalFields() {
        val backup = ConfigurationBackup("192.168.1.7", NasConnection(), ProfileSource.LOCAL)
        for (format in BackupFormat.values())
            assertEquals(backup,
                ConfigurationBackupCodec.decode(
                    ConfigurationBackupCodec.encode(backup, format, true)))
    }

    @Test fun invalidAndMaliciousInputRejected() {
        val malformed = listOf(
            "hello world",
            """{"type":"tonmaschine-android-settings","version":"99","profileSource":"local"}""",
            """{"type":"tonmaschine-android-settings","version":"1","profileSource":"admin"}""",
            """{"type":"tonmaschine-android-settings","version":"1","profileSource":"local","soundbarIp":"bad_ip"}""",
            """{"type":"tonmaschine-android-settings","version":"1","profileSource":"nas"}""",
            """{"type":"tonmaschine-android-settings","version":"1","profileSource":"local","nasUser":"user"}"""
        )
        malformed.forEach { text ->
            try {
                ConfigurationBackupCodec.decode(text)
                fail("Should reject: $text")
            } catch (_: IllegalArgumentException) {
                // expected
            } catch (_: org.json.JSONException) {
                // invalid JSON is also expected
            }
        }
    }

    @Test fun backupsAreLimitedInSize() {
        val long = sample.copy(nas = sample.nas.copy(password = "x".repeat(100000)))
        try {
            ConfigurationBackupCodec.encode(long, BackupFormat.JSON, true)
            fail("Oversized backup must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
