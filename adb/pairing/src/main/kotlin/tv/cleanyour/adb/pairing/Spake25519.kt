package tv.cleanyour.adb.pairing

import java.math.BigInteger
import java.security.SecureRandom

// BoringSSL's SPAKE2 over the Ed25519 group (crypto/curve25519/spake25519.c), as AOSP's
// pairing_auth uses it. The client is Alice ("adb pair client"), the TV is Bob
// ("adb pair server"); the names carry their trailing NUL, as AOSP passes sizeof() of the
// string literals. The password is the pairing code with the TLS exported keying material
// appended (pairing_connection.cpp), not the bare code: the two sides must agree on the full
// password or the PeerInfo decrypt fails, which is how a wrong code surfaces.
class Spake25519 private constructor(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val password: ByteArray,
) {
    enum class Role { ALICE, BOB }

    private lateinit var privateKey: BigInteger
    private lateinit var passwordScalar: BigInteger
    private lateinit var passwordHash: ByteArray
    private lateinit var myMessage: ByteArray

    companion object {
        // AOSP pairing_auth.cpp: kClientName / kServerName, NUL included.
        val CLIENT_NAME: ByteArray = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        val SERVER_NAME: ByteArray = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        fun client(password: ByteArray): Spake25519 = Spake25519(Role.ALICE, CLIENT_NAME, SERVER_NAME, password)
        fun server(password: ByteArray): Spake25519 = Spake25519(Role.BOB, SERVER_NAME, CLIENT_NAME, password)
    }

    // SPAKE2_generate_msg: a random 64-byte private scalar, reduced mod L and times 8 (the
    // cofactor), the blinded point x*G + w*M (Alice) or y*G + w*N (Bob).
    fun generateMessage(random: SecureRandom = SecureRandom()): ByteArray {
        val tmp = ByteArray(64).also { random.nextBytes(it) }
        val reduced = Ed25519.scReduce(tmp)
        privateKey = BigInteger(1, Ed25519.timesEight(reduced).reversedArray())
        val p = Ed25519.scalarMultBase(privateKey)
        passwordHash = Ed25519.sha512(password)
        passwordScalar = Ed25519.passwordScalar(passwordHash)
        val maskPoint = if (role == Role.ALICE) Ed25519.POINT_M else Ed25519.POINT_N
        val mask = Ed25519.scalarMult(passwordScalar, maskPoint)
        myMessage = Ed25519.encode(Ed25519.add(p, mask))
        return myMessage.copyOf()
    }

    // SPAKE2_process_msg: unblind the peer's point with w times the peer's blind point, complete
    // the Diffie-Hellman step, and hash the transcript (each field 8-byte little-endian length
    // prefixed, Alice's name and message first) with SHA-512. Returns the 64-byte shared key.
    fun processMessage(theirMessage: ByteArray): ByteArray {
        require(theirMessage.size == 32) { "SPAKE2 messages are 32 bytes" }
        val theirPoint = Ed25519.decode(theirMessage)
        val peerBlind = if (role == Role.ALICE) Ed25519.POINT_N else Ed25519.POINT_M
        val unblinded = Ed25519.subtract(theirPoint, Ed25519.scalarMult(passwordScalar, peerBlind))
        val shared = Ed25519.scalarMult(privateKey, unblinded)
        val sharedEncoded = Ed25519.encode(shared)
        val transcript = Transcript()
        if (role == Role.ALICE) {
            transcript.add(myName); transcript.add(theirName)
            transcript.add(myMessage); transcript.add(theirMessage)
        } else {
            transcript.add(theirName); transcript.add(myName)
            transcript.add(theirMessage); transcript.add(myMessage)
        }
        transcript.add(sharedEncoded)
        transcript.add(passwordHash)
        return transcript.sha512()
    }

    private class Transcript {
        private val parts = ArrayList<ByteArray>()
        fun add(data: ByteArray) { parts += data }
        fun sha512(): ByteArray {
            val md = java.security.MessageDigest.getInstance("SHA-512")
            for (p in parts) {
                val len = ByteArray(8)
                var v = p.size.toLong()
                for (i in 0 until 8) { len[i] = (v and 0xFF).toByte(); v = v ushr 8 }
                md.update(len)
                md.update(p)
            }
            return md.digest()
        }
    }
}
