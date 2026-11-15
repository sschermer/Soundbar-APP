package de.tonmaschine.android.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Temescal framing verified against lg-bartalk 0.1.3 / python-temescal.
 * Fixed key is an LG protocol constant, NOT authentication; trusted LAN only.
 * Original protocol attribution: google/python-temescal, Apache-2.0.
 */
object TemescalCodec {
    private val key: ByteArray get() = "T^&*J%^7tr~4^%^&I(o%^!jIJ__+a0 k".toByteArray(Charsets.UTF_8)
    private val iv: ByteArray get() = "'%^Ur7gy$~t+f)%@".toByteArray(Charsets.UTF_8)
    private const val MARKER = 0x10
    private const val MAX_LENGTH = 1024 * 1024

    private fun cipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(TemescalCodec.iv))
    }

    fun encryptJson(json: String): ByteArray {
        val payload = cipher(Cipher.ENCRYPT_MODE).doFinal(json.toByteArray(Charsets.UTF_8))
        return ByteBuffer.allocate(5 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(MARKER.toByte()).putInt(payload.size).put(payload).array()
    }

    fun decryptPayload(payload: ByteArray): String {
        require(payload.isNotEmpty() && payload.size % 16 == 0 && payload.size <= MAX_LENGTH) {
            "Invalid encrypted payload length"
        }
        return cipher(Cipher.DECRYPT_MODE).doFinal(payload).toString(Charsets.UTF_8)
    }

    /** Reads split TCP frames exactly. Missing bytes / invalid padding fail closed. */
    fun readJson(input: InputStream): String {
        val stream = DataInputStream(input)
        var skipped = 0
        while (stream.readUnsignedByte() != MARKER) {
            if (++skipped > 128) throw IllegalArgumentException("Missing Temescal frame marker")
        }
        try {
            val size = stream.readInt()
            require(size in 16..MAX_LENGTH && size % 16 == 0) { "Invalid frame length: " + size }
            val payload = ByteArray(size)
            stream.readFully(payload)
            return decryptPayload(payload)
        } catch (timeout: java.net.SocketTimeoutException) {
            throw IllegalStateException("Partial Temescal frame timed out", timeout)
        }
    }
}
