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
        val envelope = ByteArray(77) { (it + 1).toByte() }
        val ct = ByteArray(32) { it.toByte() }
        val original = EncryptedMessagePayload(envelope = envelope, ct = ct)
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<EncryptedMessagePayload>(decoded)
        assertContentEquals(envelope, decoded.envelope)
        assertContentEquals(ct, decoded.ct)
    }

    @Test
    fun `EncryptedMessagePayload type discriminator is EncryptedMessage`() {
        val payload = EncryptedMessagePayload(envelope = ByteArray(77), ct = ByteArray(16))
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
                EncryptedMessagePayload(envelope = ByteArray(77), ct = ByteArray(32)),
                KeyExchangeInitPayload(token = "deadbeef", ekPub = ByteArray(32), keyConfirmation = ByteArray(32), suggestedName = "Alice"),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
