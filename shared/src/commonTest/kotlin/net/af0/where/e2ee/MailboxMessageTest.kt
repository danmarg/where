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
    // EncryptedMessagePayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `EncryptedMessagePayload serialises and deserialises`() {
        val dhPub = ByteArray(32) { (it + 1).toByte() }
        val ct = ByteArray(32) { it.toByte() }
        val original = EncryptedMessagePayload(dhPub = dhPub, seq = "12345", ct = ct)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EncryptedMessagePayload>(decoded)
        assertContentEquals(dhPub, decoded.dhPub)
        assertEquals("12345", decoded.seq)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `EncryptedMessagePayload seqAsLong round-trips large uint64`() {
        val payload = EncryptedMessagePayload(dhPub = ByteArray(32), seq = "9007199254740993", ct = ByteArray(0))
        assertEquals(9007199254740993L, payload.seqAsLong())
    }

    @Test
    fun `EncryptedMessagePayload type discriminator is EncryptedMessage`() {
        val payload = EncryptedMessagePayload(dhPub = ByteArray(32), seq = "1", ct = ByteArray(16))
        val jsonStr = json.encodeToString(MailboxPayload.serializer(), payload)
        assert(jsonStr.contains("\"type\":\"EncryptedMessage\"")) {
            "Expected type discriminator in: $jsonStr"
        }
    }

    // ---------------------------------------------------------------------------
    // Cross-type discrimination
    // ---------------------------------------------------------------------------

    @Test
    fun `all payload types round-trip via sealed class deserializer`() {
        val payloads: List<MailboxPayload> =
            listOf(
                EncryptedMessagePayload(dhPub = ByteArray(32), seq = "1", ct = ByteArray(32)),
                KeyExchangeInitPayload(token = "deadbeef", ekPub = ByteArray(32), keyConfirmation = ByteArray(32), suggestedName = "Alice"),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
