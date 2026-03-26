package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreKeyBundleTest {
    private fun makeOPKs(count: Int): List<Pair<OPK, ByteArray>> =
        (1..count).map { i ->
            val kp = generateX25519KeyPair()
            OPK(id = 100 + i, pub = kp.pub) to kp.priv
        }

    // ---------------------------------------------------------------------------
    // signedData canonical encoding
    // ---------------------------------------------------------------------------

    @Test
    fun `signedData length is correct for zero keys`() {
        val token = ByteArray(16) { it.toByte() }
        val data = PreKeyBundleOps.signedData(token, emptyList())
        // v(4) + token(16) + n_keys(4) = 24
        assertEquals(24, data.size)
    }

    @Test
    fun `signedData length is correct for N keys`() {
        val token = ByteArray(16)
        val opks = makeOPKs(5).map { it.first }
        val data = PreKeyBundleOps.signedData(token, opks)
        // 4 + 16 + 4 + 5*(4+32) = 24 + 5*36 = 204
        assertEquals(204, data.size)
    }

    @Test
    fun `signedData sorts OPKs by id regardless of input order`() {
        val token = ByteArray(16)
        val opks = makeOPKs(3).map { it.first }
        val shuffled = listOf(opks[2], opks[0], opks[1]) // out of order
        assertEquals(
            PreKeyBundleOps.signedData(token, opks).toList(),
            PreKeyBundleOps.signedData(token, shuffled).toList(),
        )
    }

    @Test
    fun `signedData is different for different tokens`() {
        val token1 = ByteArray(16) { 0x01.toByte() }
        val token2 = ByteArray(16) { 0x02.toByte() }
        val opks = makeOPKs(1).map { it.first }
        assertFalse(
            PreKeyBundleOps.signedData(token1, opks).contentEquals(
                PreKeyBundleOps.signedData(token2, opks),
            ),
        )
    }

    // ---------------------------------------------------------------------------
    // Build / verify round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `signature round-trip succeeds`() {
        val identity = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())
        val token = ByteArray(16) { 0xAB.toByte() }
        val opks = makeOPKs(3).map { it.first }

        val sig = PreKeyBundleOps.buildSignature(token, opks, identity.sigIk.priv)
        assertTrue(PreKeyBundleOps.verify(token, opks, sig, identity.sigIk.pub))
    }

    @Test
    fun `verify rejects wrong signing key`() {
        val identity = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())
        val other = generateEd25519KeyPair()
        val token = ByteArray(16)
        val opks = makeOPKs(2).map { it.first }

        val sig = PreKeyBundleOps.buildSignature(token, opks, identity.sigIk.priv)
        assertFalse(PreKeyBundleOps.verify(token, opks, sig, other.pub))
    }

    @Test
    fun `verify rejects tampered OPK list`() {
        val identity = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())
        val token = ByteArray(16)
        val opks = makeOPKs(2).map { it.first }
        val tampered = opks.toMutableList().apply { removeAt(0) }

        val sig = PreKeyBundleOps.buildSignature(token, opks, identity.sigIk.priv)
        assertFalse(PreKeyBundleOps.verify(token, tampered, sig, identity.sigIk.pub))
    }

    @Test
    fun `verify rejects tampered token`() {
        val identity = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())
        val token = ByteArray(16) { 0x01.toByte() }
        val badToken = ByteArray(16) { 0x02.toByte() }
        val opks = makeOPKs(2).map { it.first }

        val sig = PreKeyBundleOps.buildSignature(token, opks, identity.sigIk.priv)
        assertFalse(PreKeyBundleOps.verify(badToken, opks, sig, identity.sigIk.pub))
    }

    @Test
    fun `verify accepts OPKs in any order if content is same`() {
        val identity = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())
        val token = ByteArray(16)
        val opks = makeOPKs(3).map { it.first }
        val shuffled = listOf(opks[1], opks[0], opks[2])

        val sig = PreKeyBundleOps.buildSignature(token, opks, identity.sigIk.priv)
        // signedData sorts by id, so signature over opks == signature over shuffled
        assertTrue(PreKeyBundleOps.verify(token, shuffled, sig, identity.sigIk.pub))
    }
}
