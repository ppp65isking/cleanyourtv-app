// In-app ADB pairing (0.2 workstream, D9): the wire protocol of AOSP `adb pair`
// (packages/modules/adb/pairing_connection + pairing_auth), so a TV that only offers wireless
// debugging can be paired from this phone, with no computer in between.
//
// The protocol, as read from the AOSP sources:
//   - TLS 1.3, ALPN "adb", both sides self-signed (the server cert is unverified, and our cert
//     carries the RSA key adbd stores on success). The TLS layer itself lives in
//     :android:platform (AdbPairingTls); this module only sees its streams.
//   - The SPAKE2 password is the 6-digit pairing code, with 64 bytes of TLS exported keying
//     material (label "adb-label\0") appended, so the PAKE is bound to this TLS session.
//   - SPAKE2 is BoringSSL's spake25519 (Ed25519 group, the fixed M/N points, the SHA-512
//     transcript), NOT the RFC 9382 variant: a generic SPAKE2 library will not interop.
//   - The SPAKE2 output (64 bytes) feeds HKDF-SHA256 (salt null, info
//     "adb pairing_auth aes-128-gcm key", L 16) for the AES-128-GCM PeerInfo exchange.
//   - Packets are framed as version (1 byte, 1), type (1 byte), payload size (4 bytes, big-endian).
//     Type 0 is the SPAKE2 message (a 32-byte point), type 1 is the encrypted 8192-byte PeerInfo.
//
// This module is pure Kotlin (rule 2, rule 6: coroutines only). The curve arithmetic is
// BigInteger extended coordinates: slow next to a native implementation, but a pairing runs it
// a handful of times, and it keeps the module free of a crypto dependency. Every constant and
// step below is checked against BoringSSL's crypto/curve25519/spake25519.c; the module has no
// test target in this repo (tests are not run on this branch), so the author verified it with a
// scratch harness (Alice/Bob agreement, M/N round-trip, PeerInfo round-trip) instead.

package tv.cleanyour.adb.pairing

import java.math.BigInteger
import java.security.MessageDigest

// Ed25519 arithmetic in extended coordinates (Hisil et al., a = -1), over BigInteger. Only what
// SPAKE2 needs: decode/encode a compressed point, add, subtract, scalar multiply, and the
// scalar reduce the protocol uses. Points are validated on decode: a peer message that is not a
// curve point is rejected, as BoringSSL's ge_frombytes does.
object Ed25519 {
    val PRIME_P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    val ORDER_L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    // d = -121665 / 121666 mod p.
    val CURVE_D: BigInteger = PRIME_P.subtract(BigInteger.valueOf(121665))
        .multiply(BigInteger.valueOf(121666).modInverse(PRIME_P)).mod(PRIME_P)
    private val SQRT_MINUS_ONE: BigInteger = BigInteger.TWO.modPow(PRIME_P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), PRIME_P)

    // The base point, from RFC 8032: y = 4/5, x the even root.
    val BASE: Point = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(PRIME_P)).mod(PRIME_P)
        val x = recoverX(y, false) ?: throw IllegalStateException("base point does not decode")
        Point(x, y, BigInteger.ONE, x.multiply(y).mod(PRIME_P))
    }

    // The fixed SPAKE2 points, as BoringSSL's spake25519.c prints them (try-and-increment from the
    // seeds "edwards25519 point generation seed (M)" and "... (N)").
    val POINT_M: Point = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
    val POINT_N: Point = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))

    fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
        companion object {
            val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        }
    }

    private fun mod(v: BigInteger): BigInteger = v.mod(PRIME_P)

    // x from y on the curve x^2 = (y^2 - 1) / (d y^2 + 1); p = 5 mod 8, so the square root is
    // u^((p+3)/8), times sqrt(-1) when that squares to -u. Null when y is not on the curve.
    fun recoverX(y: BigInteger, sign: Boolean): BigInteger? {
        val y2 = y.multiply(y).mod(PRIME_P)
        val u = y2.subtract(BigInteger.ONE).mod(PRIME_P)
        val v = CURVE_D.multiply(y2).add(BigInteger.ONE).mod(PRIME_P)
        val xx = u.multiply(v.modInverse(PRIME_P)).mod(PRIME_P)
        var x = xx.modPow(PRIME_P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), PRIME_P)
        if (x.multiply(x).mod(PRIME_P) != xx) {
            x = x.multiply(SQRT_MINUS_ONE).mod(PRIME_P)
            if (x.multiply(x).mod(PRIME_P) != xx) return null
        }
        if (x.testBit(0) != sign) x = PRIME_P.subtract(x)
        return x
    }

    fun decode(bytes: ByteArray): Point {
        require(bytes.size == 32) { "Ed25519 points are 32 bytes" }
        val sign = bytes[31].toInt() and 0x80 != 0
        val raw = bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7F).toByte() }
        val y = BigInteger(1, raw.reversedArray())
        require(y < PRIME_P) { "point y out of range" }
        val x = recoverX(y, sign) ?: throw IllegalArgumentException("point is not on the curve")
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(PRIME_P))
    }

    fun encode(p: Point): ByteArray {
        val zInv = p.z.modInverse(PRIME_P)
        val x = p.x.multiply(zInv).mod(PRIME_P)
        val y = p.y.multiply(zInv).mod(PRIME_P)
        val out = y.toByteArray().let { b ->
            // BigInteger.toByteArray is big-endian with a sign byte; take the low 32 bytes,
            // little-endian: out[0] is the least significant byte.
            val le = ByteArray(32)
            val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
            for (i in src.indices) le[i] = src[src.size - 1 - i]
            le
        }
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    fun add(p: Point, q: Point): Point {
        val a = mod(p.y.subtract(p.x)).multiply(mod(q.y.subtract(q.x)))
        val b = mod(p.y.add(p.x)).multiply(mod(q.y.add(q.x)))
        val c = mod(p.t.multiply(q.t).multiply(CURVE_D).multiply(BigInteger.TWO))
        val d = mod(p.z.multiply(q.z).multiply(BigInteger.TWO))
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(mod(e.multiply(f)), mod(g.multiply(h)), mod(f.multiply(g)), mod(e.multiply(h)))
    }

    fun double(p: Point): Point {
        val a = mod(p.x.multiply(p.x))
        val b = mod(p.y.multiply(p.y))
        val c = mod(p.z.multiply(p.z).multiply(BigInteger.TWO))
        val d = PRIME_P.subtract(a) // a = -1, so d = -A
        val e = mod(p.x.add(p.y)).let { it.multiply(it).mod(PRIME_P) }.subtract(a).subtract(b)
        val g = d.add(b)
        val f = g.subtract(c)
        val h = d.subtract(b)
        return Point(mod(e.multiply(f)), mod(g.multiply(h)), mod(f.multiply(g)), mod(e.multiply(h)))
    }

    fun negate(p: Point): Point = Point(mod(PRIME_P.subtract(p.x)), p.y, p.z, mod(PRIME_P.subtract(p.t)))

    fun subtract(p: Point, q: Point): Point = add(p, negate(q))

    // Double-and-add, most significant bit first; the scalar may use all 256 bits (the password
    // scalar after the x8 hack can reach 8L).
    fun scalarMult(scalar: BigInteger, p: Point): Point {
        var r = Point.IDENTITY
        for (i in 255 downTo 0) {
            r = double(r)
            if (scalar.testBit(i)) r = add(r, p)
        }
        return r
    }

    fun scalarMultBase(scalar: BigInteger): Point = scalarMult(scalar, BASE)

    // x25519_sc_reduce: a 64-byte little-endian integer reduced mod L, as 32 little-endian bytes.
    fun scReduce(bytes64: ByteArray): ByteArray {
        require(bytes64.size == 64)
        return toLe32(BigInteger(1, bytes64.reversedArray()).mod(ORDER_L))
    }

    // The SPAKE2 "password scalar hack" (spake25519.c): after the reduce, add L, 2L, 4L when the
    // low bits are set (each test reads the updated scalar), so the result is divisible by 8 and
    // the mask lands in the prime-order subgroup.
    fun passwordScalar(passwordHash64: ByteArray): BigInteger {
        require(passwordHash64.size == 64)
        var s = BigInteger(1, scReduce(passwordHash64).reversedArray())
        if (s.testBit(0)) s += ORDER_L
        if (s.testBit(1)) s += ORDER_L.shiftLeft(1)
        if (s.testBit(2)) s += ORDER_L.shiftLeft(2)
        check(!s.testBit(0) && !s.testBit(1) && !s.testBit(2)) { "password scalar is not divisible by 8" }
        return s
    }

    // left_shift_3 of spake25519.c: the 32-byte scalar times 8, inside 32 bytes.
    fun timesEight(scalar32: ByteArray): ByteArray {
        require(scalar32.size == 32)
        val out = ByteArray(32)
        var carry = 0
        for (i in 0 until 32) {
            val v = (scalar32[i].toInt() and 0xFF) shl 3 or carry
            out[i] = v.toByte()
            carry = v ushr 8
        }
        return out
    }

    fun toLe32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val le = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        for (i in src.indices) le[i] = src[src.size - 1 - i]
        return le
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }
}
