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
        val original = EncryptedLocationPayload(seq = "12345", ct = ct)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EncryptedLocationPayload>(decoded)
        assertEquals("12345", decoded.seq)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `EncryptedLocationPayload seqAsLong round-trips large uint64`() {
        val payload = EncryptedLocationPayload(seq = "9007199254740993", ct = ByteArray(0))
        assertEquals(9007199254740993L, payload.seqAsLong())
    }

    @Test
    fun `EncryptedLocationPayload type discriminator is EncryptedLocation`() {
        val payload = EncryptedLocationPayload(seq = "1", ct = ByteArray(16))
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
    // Cross-type discrimination
    // ---------------------------------------------------------------------------

    @Test
    fun `all types round-trip via sealed class deserializer`() {
        val payloads: List<MailboxPayload> =
            listOf(
                EncryptedLocationPayload(seq = "1", ct = ByteArray(32)),
                PreKeyBundlePayload(keys = emptyList(), mac = ByteArray(32)),
                KeyExchangeInitPayload(token = "deadbeef", ekPub = ByteArray(32), keyConfirmation = ByteArray(32), suggestedName = "Alice"),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
