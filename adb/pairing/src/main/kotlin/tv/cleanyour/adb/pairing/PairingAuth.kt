package tv.cleanyour.adb.pairing

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// The pairing_auth cipher (AOSP adb/pairing_auth): the 64-byte SPAKE2 key feeds HKDF-SHA256
// with a null salt and the fixed info string, giving a 16-byte AES-128-GCM key. Each direction
// encrypts with a 12-byte nonce whose first 8 bytes are a little-endian message counter starting
// at zero; the two directions have independent counters, so both PeerInfo messages reuse
// (key, nonce 0), exactly as AOSP does. The GCM tag (16 bytes) is appended to the ciphertext.
object PairingAuth {
    const val PEER_INFO_SIZE = 8192
    const val TYPE_ADB_RSA_PUB_KEY: Byte = 0
    const val TYPE_ADB_DEVICE_GUID: Byte = 1

    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

    // RFC 5869 HKDF-SHA256: a null salt is 32 zero bytes; one expand block is enough for 16 bytes.
    fun deriveKey(spakeKey64: ByteArray): ByteArray {
        require(spakeKey64.size == 64) { "SPAKE2 keys are 64 bytes" }
        val zeros = ByteArray(32)
        val prk = hmac(zeros, spakeKey64)
        val block = hmac(prk, HKDF_INFO + byteArrayOf(1))
        return block.copyOf(16)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    class CipherStream(key16: ByteArray) {
        private val key = SecretKeySpec(key16.copyOf(), "AES")
        private var encCounter = 0L
        private var decCounter = 0L

        fun encrypt(plaintext: ByteArray): ByteArray {
            val out = cipher(Cipher.ENCRYPT_MODE, nonceFor(encCounter++))
            return out.doFinal(plaintext)
        }

        fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = cipher(Cipher.DECRYPT_MODE, nonceFor(decCounter++))
            return cipher.doFinal(ciphertext)
        }

        private fun cipher(mode: Int, nonce: ByteArray): Cipher {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(mode, key, GCMParameterSpec(128, nonce))
            return c
        }

        private fun nonceFor(counter: Long): ByteArray {
            val nonce = ByteArray(12)
            var v = counter
            for (i in 0 until 8) { nonce[i] = (v and 0xFF).toByte(); v = v ushr 8 }
            return nonce
        }
    }

    // Our PeerInfo: type 0 (the adb authorized-key line: base64 struct, space, comment) in an
    // otherwise zeroed 8192-byte buffer. The line the phone already advertises in the Allow
    // dialog is the key adbd stores on success.
    fun ourPeerInfo(publicKeyLine: String): ByteArray {
        val line = publicKeyLine.toByteArray(Charsets.US_ASCII)
        require(line.size <= PEER_INFO_SIZE - 1) { "adb public key line is too long for PeerInfo" }
        return ByteArray(PEER_INFO_SIZE).also { it[0] = TYPE_ADB_RSA_PUB_KEY; line.copyInto(it, 1) }
    }

    // The TV's PeerInfo: type 1 carries its GUID (an ASCII string, NUL padded); type 0 would be
    // its public key. Either proves the exchange worked; the GUID is what the caller keeps.
    fun parsePeerInfo(plain: ByteArray): ParsedPeerInfo {
        require(plain.size == PEER_INFO_SIZE) { "PeerInfo is $PEER_INFO_SIZE bytes" }
        val data = plain.copyOfRange(1, PEER_INFO_SIZE)
        val text = data.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
        return ParsedPeerInfo(type = plain[0], text = text)
    }

    data class ParsedPeerInfo(val type: Byte, val text: String)
}
