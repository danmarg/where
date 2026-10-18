@file:OptIn(RawKeyAccess::class)

package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypedKeysTest {
    init {
        initializeE2eeTests()
    }

    private fun bytes(
        tag: Int,
        size: Int = 32,
    ): ByteArray = ByteArray(size) { (tag * 31 + it).toByte() }

    @Test
    fun constructorsRejectTheWrongLength() {
        assertFailsWith<IllegalArgumentException> { RootKey(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { HeaderKey(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { MessageNonce(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { SendToken(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { Fingerprint(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { DiscoverySecret(ByteArray(16)) }
    }

    @Test
    fun secretsPrintRedactedAndPublicValuesPrintTheirLength() {
        val key = RootKey(bytes(1))
        assertEquals("RootKey(redacted)", key.toString())
        assertEquals("RootKey(redacted)", "$key")
        assertEquals("SendToken(16 bytes)", SendToken(bytes(2, 16)).toString())
    }

    @Test
    fun secretEqualityIsByContentAndTypeWithATypeOnlyHash() {
        assertEquals(ChainKey(bytes(1)), ChainKey(bytes(1)))
        assertNotEquals(ChainKey(bytes(1)), ChainKey(bytes(2)))
        assertEquals(ChainKey(bytes(1)).hashCode(), ChainKey(bytes(2)).hashCode())
        // Same bytes, different role: not equal.
        assertFalse(RootKey(bytes(1)).equals(ChainKey(bytes(1))))
        assertFalse(SendToken(bytes(1, 16)).equals(RecvToken(bytes(1, 16))))
    }

    @Test
    fun aMessageKeyIsSingleUse() {
        val key = MessageKey(bytes(1))
        val ct = sealMessage(key, MessageNonce(bytes(2, 12)), "hi".encodeToByteArray(), ByteArray(0))
        assertTrue(key.bytes.all { it == 0.toByte() }, "sealMessage must zeroize the key")
        assertFailsWith<IllegalStateException> { sealMessage(key, MessageNonce(bytes(2, 12)), "again".encodeToByteArray(), ByteArray(0)) }

        val opener = MessageKey(bytes(1))
        assertEquals("hi", openMessage(opener, MessageNonce(bytes(2, 12)), ct, ByteArray(0)).decodeToString())
        assertFailsWith<IllegalStateException> { openMessage(opener, MessageNonce(bytes(2, 12)), ct, ByteArray(0)) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun skippedKeysWithoutATimestampLoadAsStaleAndMalformedOnesAreDropped() {
        val json = Json { ignoreUnknownKeys = true }
        val full = SkippedMessageKey.encode(SkippedMessageKey(MessageKey(bytes(1)), MessageNonce(bytes(2, 12)), 1234L))
        val noTimestamp = full.copyOfRange(0, 44)
        val blob =
            """{"a:1":"${Base64.encode(full)}","a:2":"${Base64.encode(noTimestamp)}","a:3":"${Base64.encode(ByteArray(7))}"}"""
        val map = json.decodeFromString(SkippedKeysSerializer, blob)

        assertEquals(setOf("a:1", "a:2"), map.keys)
        assertEquals(1234L, map.getValue("a:1").storedAtMillis)
        assertEquals(0L, map.getValue("a:2").storedAtMillis, "a legacy entry is stamped 0 so the next purge drops it")
        assertEquals(MessageKey(bytes(1)), map.getValue("a:2").messageKey)
        // Round trip writes the packed 52-byte form.
        assertEquals(
            """{"a:1":"${Base64.encode(full)}"}""",
            json.encodeToString(SkippedKeysSerializer, mapOf("a:1" to map.getValue("a:1"))),
        )
    }
}
