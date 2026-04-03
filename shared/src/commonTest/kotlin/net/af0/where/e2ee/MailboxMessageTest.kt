package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlin.experimental.ExperimentalNativeApi
import kotlin.test.*

@OptIn(ExperimentalNativeApi::class)
class MailboxMessageTest {
    /** Json instance matching the wire format used by all mailbox messages. */
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    init {
        initializeE2eeTests()
    }

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
        val jsonStr = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(jsonStr.contains("\"type\":\"EncryptedLocation\"")) {
            "Expected type discriminator in: $jsonStr"
        }
    }

    // ---------------------------------------------------------------------------
    // PreKeyBundlePayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `PreKeyBundlePayload serialises and deserialises`() {
        val keys =
            listOf(
                OPKWire(id = 101, pub = ByteArray(32) { 0x01.toByte() }),
                OPKWire(id = 102, pub = ByteArray(32) { 0x02.toByte() }),
            )
        val mac = ByteArray(32) { 0xFF.toByte() }
        val original = PreKeyBundlePayload(keys = keys, mac = mac)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<PreKeyBundlePayload>(decoded)
        assertEquals(2, decoded.keys.size)
        assertEquals(101, decoded.keys[0].id)
        assertContentEquals(keys[0].pub, decoded.keys[0].pub)
        assertContentEquals(mac, decoded.mac)
    }

    @Test
    fun `PreKeyBundlePayload toOPKList converts correctly`() {
        val keys = listOf(OPKWire(id = 5, pub = ByteArray(32) { it.toByte() }))
        val payload = PreKeyBundlePayload(keys = keys, mac = ByteArray(32))
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
        val nonce = ByteArray(12) { 0xEE.toByte() }
        val ct = ByteArray(64) { 0xBB.toByte() }
        val original =
            EpochRotationPayload(
                epoch = 43,
                opkId = 101,
                newEkPub = newEkPub,
                ts = 1711152000L,
                nonce = nonce,
                ct = ct,
            )
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EpochRotationPayload>(decoded)
        assertEquals(43, decoded.epoch)
        assertEquals(101, decoded.opkId)
        assertContentEquals(newEkPub, decoded.newEkPub)
        assertEquals(1711152000L, decoded.ts)
        assertContentEquals(nonce, decoded.nonce)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `EpochRotationPayload uses snake_case field names`() {
        val payload =
            EpochRotationPayload(
                epoch = 1,
                opkId = 5,
                newEkPub = ByteArray(32),
                ts = 0L,
                nonce = ByteArray(12),
                ct = ByteArray(32),
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
        val newEkPub = ByteArray(32) { 0xDD.toByte() }
        val nonce = ByteArray(12) { 0xEE.toByte() }
        val ct = ByteArray(32) { 0xCC.toByte() }
        val original = RatchetAckPayload(epochSeen = 42, ts = 1711152001L, newEkPub = newEkPub, nonce = nonce, ct = ct)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<RatchetAckPayload>(decoded)
        assertEquals(42, decoded.epochSeen)
        assertEquals(1711152001L, decoded.ts)
        assertContentEquals(newEkPub, decoded.newEkPub)
        assertContentEquals(nonce, decoded.nonce)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `RatchetAckPayload uses snake_case field names`() {
        val payload = RatchetAckPayload(epochSeen = 1, ts = 0L, newEkPub = ByteArray(32), nonce = ByteArray(12), ct = ByteArray(32))
        val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(encoded.contains("\"epoch_seen\"")) { "Expected epoch_seen in: $encoded" }
        assert(encoded.contains("\"new_ek_pub\"")) { "Expected new_ek_pub in: $encoded" }
    }

    // ---------------------------------------------------------------------------
    // Cross-type discrimination
    // ---------------------------------------------------------------------------

    @Test
    fun `all four payload types round-trip via sealed class deserializer`() {
        val payloads: List<MailboxPayload> =
            listOf(
                EncryptedLocationPayload(epoch = 1, seq = "1", ct = ByteArray(32)),
                PreKeyBundlePayload(keys = emptyList(), mac = ByteArray(32)),
                EpochRotationPayload(epoch = 1, opkId = 1, newEkPub = ByteArray(32), ts = 0L, nonce = ByteArray(12), ct = ByteArray(32)),
                RatchetAckPayload(epochSeen = 1, ts = 0L, newEkPub = ByteArray(32), nonce = ByteArray(12), ct = ByteArray(32)),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
