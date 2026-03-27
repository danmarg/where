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
    fun `MAC round-trip succeeds`() {
        val kBundle = ByteArray(32) { 0xAB.toByte() }
        val token = ByteArray(16) { 0xAB.toByte() }
        val opks = makeOPKs(3).map { it.first }

        val mac = PreKeyBundleOps.buildMac(token, opks, kBundle)
        assertEquals(32, mac.size) // HMAC-SHA-256 = 32 bytes
        assertTrue(PreKeyBundleOps.verify(token, opks, mac, kBundle))
    }

    @Test
    fun `verify rejects wrong kBundle`() {
        val kBundle = ByteArray(32) { 0xAB.toByte() }
        val wrongKBundle = ByteArray(32) { 0xCD.toByte() }
        val token = ByteArray(16)
        val opks = makeOPKs(2).map { it.first }

        val mac = PreKeyBundleOps.buildMac(token, opks, kBundle)
        assertFalse(PreKeyBundleOps.verify(token, opks, mac, wrongKBundle))
    }

    @Test
    fun `verify rejects tampered OPK list`() {
        val kBundle = ByteArray(32) { 0xAB.toByte() }
        val token = ByteArray(16)
        val opks = makeOPKs(2).map { it.first }
        val tampered = opks.toMutableList().apply { removeAt(0) }

        val mac = PreKeyBundleOps.buildMac(token, opks, kBundle)
        assertFalse(PreKeyBundleOps.verify(token, tampered, mac, kBundle))
    }

    @Test
    fun `verify rejects tampered token`() {
        val kBundle = ByteArray(32) { 0xAB.toByte() }
        val token = ByteArray(16) { 0x01.toByte() }
        val badToken = ByteArray(16) { 0x02.toByte() }
        val opks = makeOPKs(2).map { it.first }

        val mac = PreKeyBundleOps.buildMac(token, opks, kBundle)
        assertFalse(PreKeyBundleOps.verify(badToken, opks, mac, kBundle))
    }

    @Test
    fun `verify accepts OPKs in any order if content is same`() {
        val kBundle = ByteArray(32) { 0xAB.toByte() }
        val token = ByteArray(16)
        val opks = makeOPKs(3).map { it.first }
        val shuffled = listOf(opks[1], opks[0], opks[2])

        val mac = PreKeyBundleOps.buildMac(token, opks, kBundle)
        // signedData sorts by id, so MAC over opks == MAC over shuffled
        assertTrue(PreKeyBundleOps.verify(token, shuffled, mac, kBundle))
    }
}
