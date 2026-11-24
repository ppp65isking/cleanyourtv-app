package tv.cleanyour.android.platform

import android.os.Build
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The phone's TLS identity for the ADB protocols: the RSA key the TV learns (pairing) or
// already trusts (STLS), with the certificate the handshake presents. The certificate is the
// Keystore's own self-signed one for a hardware key, or a self-signed one built here for the
// sealed software key (SelfSignedCert). adbd accepts any certificate (AOSP sets a verify
// callback that always returns 1); the certificate only has to parse and carry this key.
data class TlsIdentity(val privateKey: PrivateKey, val certificates: Array<X509Certificate>)

// The TLS client both ADB TLS flows share (pairing and the STLS upgrade): TLS 1.3, ALPN "adb",
// the phone's key, any server certificate. The pairing flow additionally exports keying
// material from the session (RFC 5705); Android's public API has no exporter, so that goes
// through Conscrypt by reflection, with the same fallbacks other pairing implementations use.
// A failure to export is a failure of the pairing, never a silent downgrade.
object AdbTls {
    const val ALPN_PROTOCOL = "adb"
    // AOSP adb/tls/tls_connection.cpp: kExportedKeyLabel, NUL included.
    const val EXPORT_LABEL = "adb-label\u0000"
    const val EXPORT_LENGTH = 64

    fun sslContext(identity: TlsIdentity): SSLContext {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf<KeyManager>(adbKeyManager(identity)), arrayOf<TrustManager>(TrustAllManager), null)
        return ctx
    }

    private fun adbKeyManager(identity: TlsIdentity): X509KeyManager = object : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> = arrayOf("adb")
        override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<java.security.Principal>?, socket: Socket?): String = "adb"
        override fun getServerAliases(keyType: String?, issuers: Array<java.security.Principal>?): Array<String> = emptyArray()
        override fun chooseServerAlias(keyType: String?, issuers: Array<java.security.Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = identity.certificates
        override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // A fresh TLS 1.3 socket to the host: plain TCP first (so the connect timeout applies),
    // then the handshake on the IO dispatcher. Throws IOException on a connect failure and
    // javax.net.ssl.SSLException on a handshake failure, so the caller can tell them apart.
    suspend fun connect(host: String, port: Int, identity: TlsIdentity, timeoutMs: Int): SSLSocket = withContext(Dispatchers.IO) {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            wrap(plain, host, port, identity, timeoutMs)
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }

    // Wraps an already-connected TCP socket in TLS (the STLS upgrade path): the socket stays
    // open under the SSLSocket, which closes it when it closes.
    suspend fun wrap(socket: Socket, host: String, port: Int, identity: TlsIdentity, timeoutMs: Int = 0): SSLSocket = withContext(Dispatchers.IO) {
        val ssl = sslContext(identity).socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            if (timeoutMs > 0) ssl.soTimeout = timeoutMs
            setAlpn(ssl)
            ssl.startHandshake()
            if (timeoutMs > 0) ssl.soTimeout = 0
            ssl
        } catch (e: Exception) {
            runCatching { ssl.close() }
            throw e
        }
    }

    private fun setAlpn(socket: SSLSocket) {
        if (Build.VERSION.SDK_INT >= 29) {
            // SSLSocket has no setApplicationProtocols; ALPN goes through SSLParameters.
            runCatching {
                socket.sslParameters = socket.sslParameters.apply { applicationProtocols = arrayOf(ALPN_PROTOCOL) }
            }
            return
        }
        // API 28 has no SSLParameters application protocols: Conscrypt's own setter, by reflection.
        for (className in listOf("com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt")) {
            val ok = runCatching {
                val cls = Class.forName(className)
                val m = cls.getMethod("setApplicationProtocols", SSLSocket::class.java, Array<String>::class.java)
                m.invoke(null, socket, arrayOf(ALPN_PROTOCOL))
                true
            }.getOrDefault(false)
            if (ok) return
        }
    }

    // RFC 5705 export, 64 bytes, label "adb-label\0", no context. Tries, in order: Conscrypt's
    // static exporter on the socket (both class names), the socket's internal SSLEngine, and a
    // hidden exportKeyingMaterial on the socket itself. Null when none of them works, so the
    // pairing fails loudly instead of running with a wrong password.
    fun exportKeyingMaterial(socket: SSLSocket): ByteArray? {
        for (className in listOf("com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt")) {
            val out = runCatching {
                val cls = Class.forName(className)
                val m = cls.getMethod("exportKeyingMaterial", SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                m.invoke(null, socket, EXPORT_LABEL, null, EXPORT_LENGTH) as? ByteArray
            }.getOrNull()
            if (out != null && out.size == EXPORT_LENGTH) return out
        }
        val engine = findEngine(socket)
        if (engine != null) {
            for (className in listOf("com.android.org.conscrypt.Conscrypt", "org.conscrypt.Conscrypt")) {
                val out = runCatching {
                    val cls = Class.forName(className)
                    val m = cls.getMethod("exportKeyingMaterial", javax.net.ssl.SSLEngine::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                    m.invoke(null, engine, EXPORT_LABEL, null, EXPORT_LENGTH) as? ByteArray
                }.getOrNull()
                if (out != null && out.size == EXPORT_LENGTH) return out
            }
            val out = runCatching {
                val m = engine.javaClass.getMethod("exportKeyingMaterial", String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(engine, EXPORT_LABEL, null, EXPORT_LENGTH) as? ByteArray
            }.getOrNull()
            if (out != null && out.size == EXPORT_LENGTH) return out
        }
        val direct = runCatching {
            var cls: Class<*>? = socket.javaClass
            while (cls != null) {
                val m = cls.declaredMethods.firstOrNull { it.name == "exportKeyingMaterial" } ?: run { cls = cls.superclass; continue }
                m.isAccessible = true
                return@runCatching m.invoke(socket, EXPORT_LABEL, null, EXPORT_LENGTH) as? ByteArray
            }
            null
        }.getOrNull()
        return direct?.takeIf { it.size == EXPORT_LENGTH }
    }

    private fun findEngine(socket: SSLSocket): javax.net.ssl.SSLEngine? {
        var cls: Class<*>? = socket.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (javax.net.ssl.SSLEngine::class.java.isAssignableFrom(f.type)) {
                    return runCatching {
                        f.isAccessible = true
                        f.get(socket) as? javax.net.ssl.SSLEngine
                    }.getOrNull()
                }
            }
            cls = cls.superclass
        }
        return null
    }
}
