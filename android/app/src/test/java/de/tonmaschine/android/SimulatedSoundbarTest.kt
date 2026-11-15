package de.tonmaschine.android

import de.tonmaschine.android.protocol.TemescalCodec
import de.tonmaschine.android.model.SoundbarCommands
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class SimulatedSoundbarTest {
    @Test fun fakeSoundbarAcceptsEncryptedCommandsAndEmitsNotifications() {
        ServerSocket(0).use { server ->
            val response = arrayOfNulls<String>(1)
            val worker = thread {
                server.accept().use { socket ->
                    val command = JSONObject(TemescalCodec.readJson(socket.getInputStream()))
                    response[0] = command.getString("msg")
                    val event = JSONObject("""{"result":"ok","cmd":"notibyget","msg":"SPK_LIST_VIEW_INFO","data":{"s_audio_source":"PCM","i_vol":28}}""")
                    val data = TemescalCodec.encryptJson(event.toString())
                    socket.getOutputStream().write(data, 0, 6)
                    socket.getOutputStream().write(data, 6, data.size-6)
                }
            }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.getOutputStream().write(TemescalCodec.encryptJson(SoundbarCommands.get("SPK_LIST_VIEW_INFO").toString()))
                val event = JSONObject(TemescalCodec.readJson(socket.getInputStream()))
                assertEquals("PCM", event.getJSONObject("data").getString("s_audio_source"))
            }
            worker.join(2000)
            assertEquals("SPK_LIST_VIEW_INFO", response[0])
        }
    }
}
