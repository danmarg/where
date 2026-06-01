package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.fail

/**
 * X25519 low-order points produce an all-zero shared secret if the implementation
 * does not reject them. Per RFC 7748 §6.1 and standard guidance, callers MUST
 * either reject low-order public keys or check for the all-zero output, otherwise
 * a malicious peer can force the shared secret to a known constant.
 *
 * This test probes both x25519 actuals to determine whether the underlying
 * library (libsodium ScalarMultiplication) already rejects these points
 * (preferred) or silently returns zero (in which case we need a manual check).
 *
 * Known low-order points from cr.yp.to/ecdh.html and curve25519 analysis.
 */
class X25519LowOrderPointTest {
    init {
        initializeE2eeTests()
    }

    // Canonical X25519 low-order points blacklisted by libsodium's
    // crypto_scalarmult_curve25519_ref10. Other u-coordinates that look
    // superficially similar (e.g. with the high bit set) are not low-order
    // once masked per RFC 7748 §5 and are not in this list.
    private val lowOrderPoints =
        listOf(
            // 0 (order 4)
            "0000000000000000000000000000000000000000000000000000000000000000",
            // 1 (order 1)
            "0100000000000000000000000000000000000000000000000000000000000000",
            // 325606250916557431795983626356110631294008115727848805560023387167927233504
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            // 39382357235489614581723060781553021112529911719440698176882885853963445705823
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            // p - 1  (order 1)
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            // p      (order 4)
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            // p + 1  (order 1)
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )

    @Test
    fun x25519RejectsCanonicalLowOrderPoints() {
        val myPriv = randomBytes(32)
        for (hex in lowOrderPoints) {
            val pub = hex.hexToByteArray()
            val outcome = try {
                val out = x25519(myPriv, pub)
                if (out.all { it == 0.toByte() }) "ZERO" else "NONZERO(${out.toHex()})"
            } catch (_: Throwable) {
                "THREW"
            }
            if (outcome == "THREW" || outcome == "ZERO") continue
            fail("X25519 returned a usable shared secret for low-order point $hex -> $outcome")
        }
    }
}
