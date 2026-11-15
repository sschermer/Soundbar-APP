package de.tonmaschine.android

import de.tonmaschine.android.model.Catalog
import de.tonmaschine.android.model.ProfileCodec
import de.tonmaschine.android.model.SoundProfile
import de.tonmaschine.android.storage.NasPath
import de.tonmaschine.android.storage.RemoteProfileFiles
import de.tonmaschine.android.storage.SharedProfileRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NasProfilesTest {
    private val profile = SoundProfile(7, Catalog.levels.associate { it.field to 0 },
        drc = false, nightTime = false, surround = true)

    private class FakeNas(initial: String?) : RemoteProfileFiles {
        var file: String? = initial
        var locked = false
        var alwaysLocked = false
        var writes = 0
        var unlocks = 0
        var offline = false
        override fun ensureFolder() {
            if (offline) throw IOException("Netzwerk offline")
        }
        override fun read(name: String): String? {
            if (offline) throw IOException("Netzwerk offline")
            assertEquals("Tonmaschine_Profile.json", name)
            return file
        }
        override fun tryLock(): Boolean {
            if (alwaysLocked || locked) return false
            locked = true
            return true
        }
        override fun unlock() { locked = false; unlocks++ }
        override fun writeAtomically(name: String, text: String) {
            if (offline) throw IOException("Schreiben fehlgeschlagen")
            assertTrue(locked)
            assertEquals("Tonmaschine_Profile.json", name)
            writes++
            file = text
        }
        override fun close() {}
    }

    @Test fun windowsUncAndSmbPathsAreRecognized() {
        assertEquals("smb://NAS/Profile/", NasPath.folderUrl("""\\NAS\Profile\Tonmaschine_Profile.json"""))
        assertEquals("smb://192.168.1.44/Share/Tonmaschine/",
            NasPath.folderUrl("smb://192.168.1.44/Share/Tonmaschine"))
        assertEquals("smb://NAS/Meine%20Profile/", NasPath.folderUrl("""\\NAS\Meine Profile"""))
        try { NasPath.folderUrl("smb://user:pw@nas/share"); fail("embedded credentials") }
        catch (_: IllegalArgumentException) {}
        try { NasPath.folderUrl("smb://nas/share/../other"); fail("path traversal") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun profileSaveKeepsOriginalWindowsProfiles() {
        val initial = mapOf("Windows Kino" to profile, "Windows Musik" to profile.copy(mode = 6))
        val nas = FakeNas(ProfileCodec.encode(initial))
        val repo = SharedProfileRepository(nas, sleep = {})
        assertEquals(initial, repo.load())
        val after = repo.update("Android", profile.copy(mode = 14))
        assertEquals(3, after.size)
        assertEquals(initial["Windows Kino"], after["Windows Kino"])
        assertEquals(initial["Windows Musik"], after["Windows Musik"])
        assertEquals(after, ProfileCodec.decode(nas.file!!))
        assertEquals(1, nas.writes)
        assertEquals(1, nas.unlocks)
        assertFalse(nas.locked)
    }

    @Test fun deletingOneProfileDoesNotRemoveOthers() {
        val nas = FakeNas(ProfileCodec.encode(mapOf("A" to profile, "B" to profile.copy(mode = 6))))
        val after = SharedProfileRepository(nas, sleep = {}).update("A", null)
        assertEquals(setOf("B"), after.keys)
        assertEquals(setOf("B"), ProfileCodec.decode(nas.file!!).keys)
    }

    @Test fun lockedDirectoryNeverWritesOrDeletesProfiles() {
        val initial = ProfileCodec.encode(mapOf("A" to profile))
        val nas = FakeNas(initial).apply { alwaysLocked = true }
        try { SharedProfileRepository(nas, sleep = {}, lockAttempts = 3).update("B", profile); fail() }
        catch (_: IOException) {}
        assertEquals(initial, nas.file)
        assertEquals(0, nas.writes)
        assertEquals(0, nas.unlocks)
    }

    @Test fun offlineOrInvalidFilesNeverOverwriteRemoteState() {
        val initial = ProfileCodec.encode(mapOf("A" to profile))
        val nas = FakeNas(initial)
        nas.offline = true
        try { SharedProfileRepository(nas, sleep = {}).update("B", profile); fail() }
        catch (_: IOException) {}
        assertEquals(initial, nas.file)
        assertEquals(0, nas.writes)
        assertEquals(0, nas.unlocks)
        nas.offline = false
        nas.file = "{invalid"
        try { SharedProfileRepository(nas, sleep = {}).update("B", profile); fail() }
        catch (_: Exception) {}
        assertEquals("{invalid", nas.file)
        assertEquals(0, nas.writes)
        assertFalse(nas.locked)
    }



    @Test fun similarlyNamedWindowsProfilesRemainDistinct() {
        val differentCase = mapOf("Kino" to profile, "kino" to profile.copy(mode = 6))
        val encoded = ProfileCodec.encode(differentCase)
        assertEquals(2, ProfileCodec.decode(encoded).size)
        val nas = FakeNas(encoded)
        val after = SharedProfileRepository(nas, sleep = {}).update("Neu", profile.copy(mode = 14))
        assertEquals(3, after.size)
        assertEquals(setOf("Kino", "kino", "Neu"), after.keys)
    }

    @Test fun existingProfileRequiresExplicitOverwrite() {
        val original = profile.copy(mode = 7)
        val nas = FakeNas(ProfileCodec.encode(mapOf("Windows" to original)))
        val repo = SharedProfileRepository(nas, sleep = {})
        try { repo.update("Windows", profile.copy(mode = 14)); fail("overwrite without consent") }
        catch (_: IOException) {}
        assertEquals(original, repo.load()["Windows"])
        assertEquals(0, nas.writes)
        val after = repo.update("Windows", profile.copy(mode = 14), replaceExisting = true)
        assertEquals(14, after["Windows"]?.mode)
        assertEquals(1, nas.writes)
    }

    @Test fun missingWindowsProfileFileIsNeverCreatedSilently() {
        val nas = FakeNas(null)
        try { SharedProfileRepository(nas, sleep = {}).update("Neu", profile); fail() }
        catch (_: IOException) {}
        assertNull(nas.file)
        assertEquals(0, nas.writes)
        assertEquals(1, nas.unlocks)
    }
}
