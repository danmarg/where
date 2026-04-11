package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RatchetTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `kdfCk produces 32+32+12 byte split`() {
        val chainKey = ByteArray(32) { it.toByte() }
        val step = kdfCk(chainKey)
        assertEquals(32, step.newChainKey.size)
        assertEquals(32, step.messageKey.size)
        assertEquals(12, step.messageNonce.size)
    }

    @Test
    fun `kdfCk advances chain key deterministically`() {
        val chainKey = ByteArray(32) { 0xAB.toByte() }
        val step1 = kdfCk(chainKey)
        val step2 = kdfCk(chainKey)
        assertContentEquals(step1.newChainKey, step2.newChainKey)
        assertContentEquals(step1.messageKey, step2.messageKey)
        assertContentEquals(step1.messageNonce, step2.messageNonce)
    }

    @Test
    fun kdfCk_chainKey_messageKey_and_nonce_are_distinct() {
        val chainKey = ByteArray(32) { it.toByte() }
        val step = kdfCk(chainKey)
        // Different outputs from a single KDF call must not be equal.
        assertNotEquals(step.newChainKey.toList(), step.messageKey.toList())
        // Nonce is shorter (12 bytes) so compare first 12 bytes of messageKey vs nonce.
        assertNotEquals(step.messageKey.copyOfRange(0, 12).toList(), step.messageNonce.toList())
        assertNotEquals(step.newChainKey.copyOfRange(0, 12).toList(), step.messageNonce.toList())
    }

    @Test
    fun `kdfCk new chain key differs from input chain key`() {
        val chainKey = ByteArray(32) { 0x42.toByte() }
        val step = kdfCk(chainKey)
        assertNotEquals(chainKey.toList(), step.newChainKey.toList())
    }

    @Test
    fun `intToBeBytes round-trip`() {
        assertEquals(0, intToBeBytes(0).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
        assertEquals(1, intToBeBytes(1).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
        assertEquals(256, intToBeBytes(256).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
        assertEquals(Int.MAX_VALUE, intToBeBytes(Int.MAX_VALUE).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) })
    }

    @Test
    fun `longToBeBytes round-trip`() {
        val values = listOf(0L, 1L, 256L, Long.MAX_VALUE, 0x0102030405060708L)
        for (v in values) {
            val encoded = longToBeBytes(v)
            assertEquals(8, encoded.size)
            val decoded = encoded.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            assertEquals(v, decoded)
        }
    }
}
