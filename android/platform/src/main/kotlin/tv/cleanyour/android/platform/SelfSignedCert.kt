package tv.cleanyour.android.platform

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Calendar
import java.util.Date

// A minimal DER X.509 v3 certificate, built by hand: the sealed software key has no Keystore
// certificate, and the TLS handshake needs one to present. Only the shape matters — adbd
// does not verify the chain, it only stores the public key — so this is a bare self-signed
// cert (sha256WithRSAEncryption, CN=cleanyourtv, one year) with no extensions, encoded with a
// small DER writer. Kept deliberately dependency-free: bcpkix is in the Gradle cache but is
// NOT in the module allowlist, and adding it needs an RFC.
object SelfSignedCert {
    private fun len(n: Int): ByteArray {
        if (n < 128) return byteArrayOf(n.toByte())
        val bytes = BigInteger.valueOf(n.toLong()).toByteArray()
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes
    }
    private fun tlv(tag: Byte, content: ByteArray): ByteArray = byteArrayOf(tag) + len(content.size) + content
    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.reduce(ByteArray::plus))
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.reduce(ByteArray::plus))
    private fun integer(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return tlv(0x02, if (raw[0].toInt() and 0x80 != 0) byteArrayOf(0) + raw else raw)
    }
    private fun oid(vararg arcs: Long): ByteArray {
        val out = mutableListOf<Byte>()
        out.add((arcs[0] * 40 + arcs[1]).toByte())
        for (arc in arcs.drop(2)) {
            var value = arc
            val stack = ArrayDeque<Byte>()
            stack.addFirst((value and 0x7F).toByte())
            value = value ushr 7
            while (value > 0) {
                stack.addFirst(((value and 0x7F) or 0x80).toByte())
                value = value ushr 7
            }
            out.addAll(stack)
        }
        return tlv(0x06, out.toByteArray())
    }
    private fun utf8(s: String): ByteArray = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
    private fun utcTime(d: Date): ByteArray {
        val cal = Calendar.getInstance().apply { time = d }
        val text = "%02d%02d%02d%02d%02d%02dZ".format(
            cal.get(Calendar.YEAR) % 100, cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
        return tlv(0x17, text.toByteArray(Charsets.US_ASCII))
    }
    private fun bitString(content: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + content)

    fun forKey(keyPair: KeyPair): X509Certificate {
        val rsa = keyPair.public as RSAPublicKey
        val serial = BigInteger(64, java.security.SecureRandom())
        val name = seq(set(seq(oid(2, 5, 4, 3), utf8("cleanyourtv"))))
        val algorithm = seq(oid(1, 2, 840, 113549, 1, 1, 11), tlv(0x05, ByteArray(0)))
        val publicKeyInfo = seq(
            seq(oid(1, 2, 840, 113549, 1, 1, 1), tlv(0x05, ByteArray(0))),
            bitString(seq(integer(rsa.modulus), integer(rsa.publicExponent))),
        )
        val now = Date()
        val notBefore = Date(now.time - 86_400_000L)
        val notAfter = Date(now.time + 365L * 86_400_000L)
        val tbs = seq(
            tlv(0xA0.toByte(), integer(BigInteger.valueOf(2))),
            integer(serial),
            algorithm,
            name,
            seq(utcTime(notBefore), utcTime(notAfter)),
            name,
            publicKeyInfo,
        )
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbs)
        }.sign()
        val certBytes = seq(tbs, algorithm, bitString(sig))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
    }
}
