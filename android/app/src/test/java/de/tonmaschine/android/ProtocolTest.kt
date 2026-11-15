package de.tonmaschine.android

import de.tonmaschine.android.protocol.TemescalCodec
import de.tonmaschine.android.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ProtocolTest {
    @Test fun aesKnownVectorAndWireFraming() {
        val packet = "{\"cmd\":\"get\",\"msg\":\"SPK_LIST_VIEW_INFO\"}"
        val frame = TemescalCodec.encryptJson(packet)
        assertEquals(0x10, frame[0].toInt())
        assertEquals(frame.size - 5, java.nio.ByteBuffer.wrap(frame, 1, 4).int)
        assertEquals(packet, TemescalCodec.readJson(ByteArrayInputStream(frame)))
        val tampered = frame.copyOf().also { it[4] = 0 }
        try { TemescalCodec.readJson(ByteArrayInputStream(tampered)); fail("invalid length") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun codecRejectsCorruptAndTruncatedFrames() {
        val frame = TemescalCodec.encryptJson("{}")
        try { TemescalCodec.readJson(ByteArrayInputStream(frame.copyOf(frame.size - 2))); fail("EOF") }
        catch (_: java.io.EOFException) {}
        try { TemescalCodec.decryptPayload(byteArrayOf(1,2)); fail("invalid") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun reducerNeverGuessesAudioChannels() {
        val packet = JSONObject("""{"result":"ok","msg":"SPK_LIST_VIEW_INFO","data":{"s_audio_source":" DOLBY ATMOS ","i_vol":12}}""")
        val new = DeviceReducer.reduce(DeviceState(), packet)
        assertEquals("DOLBY ATMOS", new.audioFormat)
        assertEquals(12, new.volume)
        assertFalse(new.audioFormat.contains("5.1"))
        assertEquals(new, DeviceReducer.reduce(new, JSONObject("""{"result":"error","msg":"SPK_LIST_VIEW_INFO"}""")))
    }
    @Test fun inputsOnlyAdvertisedOrObserved() {
        val state = DeviceReducer.reduce(DeviceState(), JSONObject(
            """{"result":"ok","msg":"FUNC_VIEW_INFO","data":{"ai_func_list":[0,1,6,10,19,111],"i_curr_func":20}}"""
        ))
        assertEquals(listOf(0,1,6,10,19,20), state.selectableInputs)
        assertTrue(state.hasInputList)
        assertFalse(111 in state.selectableInputs)
        assertEquals(1, SoundbarCommands.source(1).getJSONObject("data").getInt("i_curr_func"))
    }
    @Test fun levelsOffsetsAndInvalidValues() {
        val sub = Catalog.levels.first()
        assertEquals(0, SoundbarCommands.level(sub, -15).getJSONObject("data").getInt("i_woofer_level"))
        assertEquals(21, SoundbarCommands.level(sub, 6).getJSONObject("data").getInt("i_woofer_level"))
        assertEquals(-3, DeviceReducer.reduce(DeviceState(), JSONObject(
            """{"result":"ok","msg":"SETTING_VIEW_INFO","data":{"i_woofer_level":12}}"""
        )).levels["i_woofer_level"])
        try { SoundbarCommands.level(sub, 50); fail("invalid") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun profilesRoundTripAndValidation() {
        val p = SoundProfile(7, Catalog.levels.associate { it.field to 0 }, false, true, true)
        val map = mapOf("Kino" to p)
        assertEquals(map, ProfileCodec.decode(ProfileCodec.encode(map)))
        try { ProfileCodec.decode("""{"bad":{"mode":7,"levels":{},"drc":true,"night_time":false}}"""); fail("invalid") }
        catch (_: Exception) {}
    }
}
