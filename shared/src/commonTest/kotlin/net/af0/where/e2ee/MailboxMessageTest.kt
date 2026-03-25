package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Json instance matching the wire format used by all mailbox messages. */
private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

class MailboxMessageTest {

    // ---------------------------------------------------------------------------
    // EncryptedLocationPayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `EncryptedLocationPayload serialises and deserialises`() {
        val ct = ByteArray(32) { it.toByte() }
        val original = EncryptedLocationPayload(epoch = 3, seq = "12345", ct = ct)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EncryptedLocationPayload>(decoded)
        assertEquals(3, decoded.epoch)
        assertEquals("12345", decoded.seq)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `EncryptedLocationPayload seqAsLong round-trips large uint64`() {
        val payload = EncryptedLocationPayload(epoch = 0, seq = "9007199254740993", ct = ByteArray(0))
        assertEquals(9007199254740993L, payload.seqAsLong())
    }

    @Test
    fun `EncryptedLocationPayload type discriminator is EncryptedLocation`() {
        val payload = EncryptedLocationPayload(epoch = 1, seq = "1", ct = ByteArray(16))
        val json_str = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(json_str.contains("\"type\":\"EncryptedLocation\"")) {
            "Expected type discriminator in: $json_str"
        }
    }

    // ---------------------------------------------------------------------------
    // PreKeyBundlePayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `PreKeyBundlePayload serialises and deserialises`() {
        val keys = listOf(
            OPKWire(id = 101, pub = ByteArray(32) { 0x01.toByte() }),
            OPKWire(id = 102, pub = ByteArray(32) { 0x02.toByte() }),
        )
        val sig = ByteArray(64) { 0xFF.toByte() }
        val original = PreKeyBundlePayload(keys = keys, sig = sig)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<PreKeyBundlePayload>(decoded)
        assertEquals(2, decoded.keys.size)
        assertEquals(101, decoded.keys[0].id)
        assertContentEquals(keys[0].pub, decoded.keys[0].pub)
        assertContentEquals(sig, decoded.sig)
    }

    @Test
    fun `PreKeyBundlePayload toOPKList converts correctly`() {
        val keys = listOf(OPKWire(id = 5, pub = ByteArray(32) { it.toByte() }))
        val payload = PreKeyBundlePayload(keys = keys, sig = ByteArray(64))
        val opks = payload.toOPKList()
        assertEquals(1, opks.size)
        assertEquals(5, opks[0].id)
        assertContentEquals(keys[0].pub, opks[0].pub)
    }

    // ---------------------------------------------------------------------------
    // EpochRotationPayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `EpochRotationPayload serialises and deserialises`() {
        val newEkPub = ByteArray(32) { 0xAA.toByte() }
        val sig = ByteArray(64) { 0xBB.toByte() }
        val original = EpochRotationPayload(
            epoch = 43, opkId = 101, newEkPub = newEkPub, ts = 1711152000L, sig = sig
        )
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EpochRotationPayload>(decoded)
        assertEquals(43, decoded.epoch)
        assertEquals(101, decoded.opkId)
        assertContentEquals(newEkPub, decoded.newEkPub)
        assertEquals(1711152000L, decoded.ts)
        assertContentEquals(sig, decoded.sig)
    }

    @Test
    fun `EpochRotationPayload uses snake_case field names`() {
        val payload = EpochRotationPayload(
            epoch = 1, opkId = 5, newEkPub = ByteArray(32), ts = 0L, sig = ByteArray(64)
        )
        val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(encoded.contains("\"opk_id\"")) { "Expected opk_id in: $encoded" }
        assert(encoded.contains("\"new_ek_pub\"")) { "Expected new_ek_pub in: $encoded" }
    }

    // ---------------------------------------------------------------------------
    // RatchetAckPayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `RatchetAckPayload serialises and deserialises`() {
        val sig = ByteArray(64) { 0xCC.toByte() }
        val original = RatchetAckPayload(epochSeen = 42, ts = 1711152001L, sig = sig)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<RatchetAckPayload>(decoded)
        assertEquals(42, decoded.epochSeen)
        assertEquals(1711152001L, decoded.ts)
        assertContentEquals(sig, decoded.sig)
    }

    @Test
    fun `RatchetAckPayload uses snake_case field names`() {
        val payload = RatchetAckPayload(epochSeen = 1, ts = 0L, sig = ByteArray(64))
        val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(encoded.contains("\"epoch_seen\"")) { "Expected epoch_seen in: $encoded" }
    }

    // ---------------------------------------------------------------------------
    // Cross-type discrimination
    // ---------------------------------------------------------------------------

    @Test
    fun `all four payload types round-trip via sealed class deserializer`() {
        val payloads: List<MailboxPayload> = listOf(
            EncryptedLocationPayload(epoch = 1, seq = "1", ct = ByteArray(32)),
            PreKeyBundlePayload(keys = emptyList(), sig = ByteArray(64)),
            EpochRotationPayload(epoch = 1, opkId = 1, newEkPub = ByteArray(32), ts = 0L, sig = ByteArray(64)),
            RatchetAckPayload(epochSeen = 1, ts = 0L, sig = ByteArray(64)),
        )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
