package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RatchetTest {
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
    fun `kdfCk chain key, message key, and nonce are distinct`() {
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
    fun `kdfRk produces 32+32 byte output`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val dhOutput = ByteArray(32) { (it + 32).toByte() }
        val step = kdfRk(rootKey, dhOutput)
        assertEquals(32, step.newRootKey.size)
        assertEquals(32, step.newChainKey.size)
    }

    @Test
    fun `kdfRk is deterministic`() {
        val rootKey = ByteArray(32) { 0x11.toByte() }
        val dhOutput = ByteArray(32) { 0x22.toByte() }
        val step1 = kdfRk(rootKey, dhOutput)
        val step2 = kdfRk(rootKey, dhOutput)
        assertContentEquals(step1.newRootKey, step2.newRootKey)
        assertContentEquals(step1.newChainKey, step2.newChainKey)
    }

    @Test
    fun `kdfRk different DH outputs produce different results`() {
        val rootKey = ByteArray(32) { 0x11.toByte() }
        val dh1 = ByteArray(32) { 0x22.toByte() }
        val dh2 = ByteArray(32) { 0x33.toByte() }
        val step1 = kdfRk(rootKey, dh1)
        val step2 = kdfRk(rootKey, dh2)
        assertNotEquals(step1.newRootKey.toList(), step2.newRootKey.toList())
        assertNotEquals(step1.newChainKey.toList(), step2.newChainKey.toList())
    }

    @Test
    fun `deriveRoutingToken produces 16 bytes`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val senderFp = ByteArray(32) { 0xAA.toByte() }
        val recipientFp = ByteArray(32) { 0xBB.toByte() }
        val token = deriveRoutingToken(rootKey, epoch = 0, senderFp, recipientFp)
        assertEquals(16, token.size)
    }

    @Test
    fun `deriveRoutingToken differs across epochs`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val senderFp = ByteArray(32) { 0xAA.toByte() }
        val recipientFp = ByteArray(32) { 0xBB.toByte() }
        val t0 = deriveRoutingToken(rootKey, epoch = 0, senderFp, recipientFp)
        val t1 = deriveRoutingToken(rootKey, epoch = 1, senderFp, recipientFp)
        assertNotEquals(t0.toList(), t1.toList())
    }

    @Test
    fun `deriveRoutingToken domain-separated from symmetric ratchet output`() {
        // A routing token and a message key derived from the same root must not be equal.
        val rootKey = ByteArray(32) { 0xCC.toByte() }
        val senderFp = ByteArray(32) { 0xAA.toByte() }
        val recipientFp = ByteArray(32) { 0xBB.toByte() }
        val token = deriveRoutingToken(rootKey, 0, senderFp, recipientFp)
        val step = kdfCk(rootKey)
        // token is 16 bytes, step outputs are 32/12 — compare prefix
        assertNotEquals(token.toList(), step.messageKey.copyOfRange(0, 16).toList())
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
