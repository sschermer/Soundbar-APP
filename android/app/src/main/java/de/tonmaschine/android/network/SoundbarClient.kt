package de.tonmaschine.android.network

import de.tonmaschine.android.protocol.TemescalCodec
import de.tonmaschine.android.model.SoundbarCommands
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.EOFException
import java.util.concurrent.LinkedBlockingQueue

/**
 * One reader, one writer, serialized commands. A socket read timeout is not a fatal
 * error if no bytes of a frame were received; a partial frame timeout is fatal.
 * The port is injectable so the tests can run against a local simulator.
 */
class SoundbarClient(
    private val ip: String,
    private val port: Int = 9741,
    private val scope: CoroutineScope,
    private val onPacket: (JSONObject) -> Unit,
    private val onConnection: (Boolean, String?) -> Unit
) {
    private val queue = LinkedBlockingQueue<JSONObject>(128)
    private var task: Job? = null
    @Volatile private var active = false
    @Volatile private var connected = false
    @Volatile private var powerOn: Boolean? = null
    private val initialMessages = listOf(
        "SPK_LIST_VIEW_INFO", "FUNC_VIEW_INFO", "EQ_VIEW_INFO", "SETTING_VIEW_INFO"
    )

    fun start() {
        if (active) return
        active = true
        task = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        active = false
        task?.cancel()
        task = null
        queue.clear()
    }

    fun send(packet: JSONObject): Boolean {
        if (!active) return false
        return queue.offer(packet)
    }

    fun refresh() = initialMessages.forEach { send(SoundbarCommands.get(it)) }
    fun refreshSignal() {
        send(SoundbarCommands.get("SPK_LIST_VIEW_INFO"))
        send(SoundbarCommands.get("FUNC_VIEW_INFO"))
    }

    private suspend fun runLoop() {
        var retryMs = 1000L
        while (active && scope.isActive) {
            var socket: Socket? = null
            try {
                onConnection(false, null)
                socket = Socket()
                socket.connect(InetSocketAddress(ip, port), 5000)
                socket.soTimeout = 300
                connected = true
                retryMs = 1000L
                onConnection(true, null)
                // Do not query a confirmed sleeping unit repeatedly; it can wake it.
                if (powerOn != false) initialMessages.forEach { queue.offer(SoundbarCommands.get(it)) }
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                var nextSignal = System.currentTimeMillis() + 6000
                var lastPacket = System.currentTimeMillis()
                while (active && scope.isActive && socket.isConnected && !socket.isClosed) {
                    repeat(8) {
                        val pending = queue.poll() ?: return@repeat
                        output.write(TemescalCodec.encryptJson(pending.toString()))
                    }
                    output.flush()
                    try {
                        val packet = JSONObject(TemescalCodec.readJson(input))
                        val data = packet.optJSONObject("data")
                        if (packet.optString("msg") == "SPK_LIST_VIEW_INFO" && data?.has("b_powerstatus") == true) {
                            powerOn = data.opt("b_powerstatus") as? Boolean
                        }
                        lastPacket = System.currentTimeMillis()
                        onPacket(packet)
                    } catch (_: SocketTimeoutException) {
                        // ReadJson only safely times out between frames, never mid-frame.
                    }
                    val now = System.currentTimeMillis()
                    if (powerOn != false && now >= nextSignal) {
                        refreshSignal()
                        nextSignal = now + 6000
                    }
                    // Reset a silently stalled connection only while known awake.
                    if (powerOn != false && now - lastPacket > 40000) throw SocketTimeoutException("No device response")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (active) onConnection(false, error.message ?: error.javaClass.simpleName)
                retryMs = (retryMs * 2).coerceAtMost(30000L)
            } finally {
                connected = false
                try { socket?.close() } catch (_: Exception) {}
            }
            if (active) delay(retryMs)
        }
    }
}
