package tv.cleanyour.android.platform

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.cleanyour.adb.pairing.AdbPairing
import tv.cleanyour.adb.pairing.PairingChannel
import tv.cleanyour.adb.pairing.PairingResult
import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException
import tv.cleanyour.core.model.Sha256

// The pairing half of the TLS transport: opens the TV's pairing port as TLS 1.3 with ALPN
// "adb" and drives the :adb:pairing protocol over it. The exported keying material the
// password needs comes from this socket; if Conscrypt will not export it, the pairing fails
// as TLS_FAILED rather than running with a wrong password.
object AdbPairingTls {
    suspend fun pair(host: String, port: Int, code: String, publicKeyLine: String, identity: TlsIdentity, timeoutMs: Int = 30_000,
        log: (String) -> Unit = {}): PairingResult =
        AdbPairing.pair(host, port, code, publicKeyLine, { _, _, _ ->
            SslPairingChannel(AdbTls.connect(host, port, identity, timeoutMs))
        }, timeoutMs.toLong(), log)
}

// A PairingChannel over a live SSLSocket. Export goes through AdbTls (Conscrypt reflection);
// a null export throws the pairing's TLS_FAILED inside the protocol runner.
private class SslPairingChannel(private val socket: SSLSocket) : PairingChannel {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream
    override fun exportKeyingMaterial(length: Int): ByteArray =
        AdbTls.exportKeyingMaterial(socket)?.takeIf { it.size == length }
            ?: throw AdbPairing.PairingFailure(tv.cleanyour.adb.pairing.PairingError.TLS_FAILED)
    override fun close() = runCatching { socket.close() }.let { }
}

// The STLS half: upgrades a plain TCP socket (opened to the TV's wireless-debugging TLS
// port) to TLS 1.3 in place, so the ADB handshake that follows runs encrypted and the TV
// accepts the key it learned at pairing. This is the TlsUpgrader the session manager was
// built with a null for; the session only calls it when the TV sends STLS, so plain-5555
// TVs never touch this code. The pin, when the caller supplies one, is the SHA-256 of the
// server certificate the caller expects; without one the certificate is accepted as AOSP
// does (its verify callback always returns 1).
class AdbTlsUpgrader(private val identitySource: suspend () -> TlsIdentity) : tv.cleanyour.adb.wire.TlsUpgrader {
    override suspend fun upgrade(socket: tv.cleanyour.adb.wire.AdbSocket, pin: Sha256?): tv.cleanyour.adb.wire.AdbSocket {
        val tcp = socket as? TcpAdbSocket
            ?: throw AdbException(AdbError.Transport.Protocol("TLS upgrade needs a TCP socket"))
        val identity = identitySource()
        val ssl = try {
            AdbTls.wrap(tcp.socket, tcp.host, tcp.port, identity, timeoutMs = 30_000)
        } catch (e: SocketTimeoutException) {
            throw AdbException(AdbError.Transport.Timeout("TLS handshake"))
        } catch (e: SSLException) {
            throw AdbException(AdbError.Transport.Protocol("TLS handshake failed: ${e.message}"))
        }
        if (pin != null) {
            // Sha256 is a lowercase hex string; compare against the hex of the digest.
            val peer = runCatching { ssl.session.peerCertificates.firstOrNull() }.getOrNull()
            val digestHex = peer?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.encoded) }
                ?.joinToString("") { "%02x".format(it) }
            if (digestHex == null || !digestHex.equals(pin.hex, ignoreCase = true)) {
                runCatching { ssl.close() }
                throw AdbException(AdbError.Transport.Protocol("TLS server certificate does not match the pinned hash"))
            }
        }
        return TcpAdbSocket(ssl, tcp.host, tcp.port)
    }
}
