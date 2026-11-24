package tv.cleanyour.adb.pairing

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// The one-shot pairing exchange (pairing_connection.cpp): SPAKE2 message, then the encrypted
// PeerInfo, each in a version-1 packet. The transport is supplied by the caller (TLS lives in
// :android:platform); this module only drives the bytes, so it stays pure JVM and testable.
//
// A wrong pairing code does not produce a "wrong code" packet: the SPAKE2 exchange completes
// with a different key on each side, and the TV's PeerInfo then fails the GCM tag check. That
// decrypt failure is the wrong-code signal, and it is reported as such.
sealed interface PairingResult {
    data class Paired(val guid: String?) : PairingResult
    data class Failed(val error: PairingError) : PairingResult
}

enum class PairingError {
    UNREACHABLE,   // the TCP connect (or the TLS handshake) failed
    TLS_FAILED,    // the TLS handshake failed, or no keying material could be exported
    WRONG_CODE,    // the peer's PeerInfo did not decrypt: the code (or the session) was wrong
    PROTOCOL,      // a packet the TV sent was not the pairing protocol
    TIMEOUT,       // the TV stopped answering mid-exchange
    UNKNOWN,
}

// What the platform layer provides: a TLS 1.3 socket to the TV's pairing port, with its streams
// and the session's exported keying material. The label is fixed by AOSP ("adb-label", NUL
// included); the transport exports exactly 64 bytes with it.
interface PairingChannel {
    val input: InputStream
    val output: OutputStream
    fun exportKeyingMaterial(length: Int): ByteArray
    fun close()
}

object AdbPairing {
    const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val TYPE_SPAKE2_MSG: Byte = 0
    private const val TYPE_PEER_INFO: Byte = 1
    private const val MAX_PAYLOAD = PairingAuth.PEER_INFO_SIZE * 2

    // The pairing code is six digits; anything else cannot be what the TV shows.
    fun validCode(code: String): Boolean = code.length == 6 && code.all { it in '0'..'9' }

    // `log` takes the unexpected failures the runner cannot classify: the pairing's own
    // caller wires it to the app log, so an UNKNOWN that survives the key fix can be pinned
    // to its exception type (a handshake failure points at signing; an export failure at
    // the keying-material export) instead of vanishing into the error enum.
    suspend fun pair(
        host: String,
        port: Int,
        code: String,
        publicKeyLine: String,
        openChannel: suspend (host: String, port: Int, timeoutMs: Int) -> PairingChannel,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        log: (String) -> Unit = {},
    ): PairingResult = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                val channel = openChannel(host, port, timeoutMs.toInt())
                try {
                    runPairing(channel, code, publicKeyLine)
                } finally {
                    runCatching { channel.close() }
                }
            }
        } catch (e: PairingFailure) {
            PairingResult.Failed(e.error)
        } catch (e: java.util.concurrent.TimeoutException) {
            PairingResult.Failed(PairingError.TIMEOUT)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            PairingResult.Failed(PairingError.TIMEOUT)
        } catch (e: javax.net.ssl.SSLException) {
            // The TLS handshake failed (wrong port, a non-TLS listener, a bad identity): not
            // the same as the TV being unreachable, and the UI says so.
            PairingResult.Failed(PairingError.TLS_FAILED)
        } catch (e: IOException) {
            PairingResult.Failed(PairingError.UNREACHABLE)
        } catch (e: Exception) {
            // The catch-all: log the exception type before it becomes UNKNOWN, or the next
            // pairing failure after the key fix cannot be told apart from this one.
            log("pairing failed UNKNOWN: ${e.javaClass.simpleName}: ${e.message}")
            PairingResult.Failed(PairingError.UNKNOWN)
        }
    }

    // Thrown by a PairingChannel when the transport itself fails (export unavailable, socket
    // gone); the runner turns it into the matching PairingError instead of a generic failure.
    class PairingFailure(val error: PairingError) : Exception()

    private fun runPairing(channel: PairingChannel, code: String, publicKeyLine: String): PairingResult {
        // The password is the code with the TLS session's keying material appended (AOSP binds
        // the PAKE to the connection so it cannot be stolen mid-flight).
        val keyingMaterial = try {
            channel.exportKeyingMaterial(64)
        } catch (e: Exception) {
            throw PairingFailure(PairingError.TLS_FAILED)
        }
        if (keyingMaterial.size != 64) throw PairingFailure(PairingError.TLS_FAILED)
        val password = code.toByteArray(Charsets.UTF_8) + keyingMaterial

        val spake = Spake25519.client(password)
        val ourMsg = spake.generateMessage()
        writePacket(channel.output, TYPE_SPAKE2_MSG, ourMsg)
        val (type, theirMsg) = readPacket(channel.input)
        if (type != TYPE_SPAKE2_MSG || theirMsg.size != 32) throw PairingFailure(PairingError.PROTOCOL)
        val sharedKey = try {
            spake.processMessage(theirMsg)
        } catch (e: Exception) {
            throw PairingFailure(PairingError.PROTOCOL)
        }
        val cipher = PairingAuth.CipherStream(PairingAuth.deriveKey(sharedKey))
        val encrypted = cipher.encrypt(PairingAuth.ourPeerInfo(publicKeyLine))
        writePacket(channel.output, TYPE_PEER_INFO, encrypted)
        val (type2, theirEncrypted) = readPacket(channel.input)
        if (type2 != TYPE_PEER_INFO) throw PairingFailure(PairingError.PROTOCOL)
        val theirPlain = try {
            cipher.decrypt(theirEncrypted)
        } catch (e: Exception) {
            // The GCM tag is the only check on the shared key: a wrong code lands here.
            throw PairingFailure(PairingError.WRONG_CODE)
        }
        if (theirPlain.size != PairingAuth.PEER_INFO_SIZE) throw PairingFailure(PairingError.PROTOCOL)
        val peer = PairingAuth.parsePeerInfo(theirPlain)
        val guid = peer.text.takeIf { peer.type == PairingAuth.TYPE_ADB_DEVICE_GUID && it.isNotBlank() }
        return PairingResult.Paired(guid)
    }

    // 6-byte header: version 1, type, payload size big-endian. The payload follows unframed.
    fun writePacket(out: OutputStream, type: Byte, payload: ByteArray) {
        val header = ByteArray(6)
        header[0] = 1
        header[1] = type
        header[2] = (payload.size ushr 24).toByte()
        header[3] = (payload.size ushr 16).toByte()
        header[4] = (payload.size ushr 8).toByte()
        header[5] = payload.size.toByte()
        try {
            out.write(header)
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        } catch (e: IOException) {
            throw PairingFailure(PairingError.UNREACHABLE)
        }
    }

    fun readPacket(input: InputStream): Pair<Byte, ByteArray> {
        val header = ByteArray(6)
        try {
            readFully(input, header)
        } catch (e: EOFException) {
            throw PairingFailure(PairingError.TIMEOUT)
        } catch (e: IOException) {
            throw PairingFailure(PairingError.UNREACHABLE)
        }
        if (header[0] != 1.toByte()) throw PairingFailure(PairingError.PROTOCOL)
        val type = header[1]
        val size = ((header[2].toInt() and 0xFF) shl 24) or ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        if (size <= 0 || size > MAX_PAYLOAD) throw PairingFailure(PairingError.PROTOCOL)
        val payload = ByteArray(size)
        try {
            readFully(input, payload)
        } catch (e: EOFException) {
            throw PairingFailure(PairingError.TIMEOUT)
        } catch (e: IOException) {
            throw PairingFailure(PairingError.UNREACHABLE)
        }
        return type to payload
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException("EOF after $read of ${buf.size} bytes")
            read += n
        }
    }
}
