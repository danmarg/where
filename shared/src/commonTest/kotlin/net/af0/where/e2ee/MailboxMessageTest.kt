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
                KeyExchangeInitPayload(
                    ekPub = ByteArray(32),
                    keyConfirmation = ByteArray(32),
                    encryptedName = ByteArray(31) { 0xFF.toByte() },
                    suggestedName = "Alice"
                ),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }

    @Test
    fun `KeyExchangeInitPayload suggestedName is transient`() {
        val payload = KeyExchangeInitPayload(
            ekPub = ByteArray(32),
            keyConfirmation = ByteArray(32),
            encryptedName = ByteArray(31) { 0xAA.toByte() },
            suggestedName = "SecretName"
        )
        val jsonStr = json.encodeToString(MailboxPayload.serializer(), payload)
        
        // Should NOT contain suggestedName
        assertFalse(jsonStr.contains("suggested_name"), "JSON should not contain transient suggested_name")
        assertFalse(jsonStr.contains("SecretName"), "JSON should not contain the secret name value")
        
        // Should contain encrypted_name
        assertTrue(jsonStr.contains("encrypted_name"), "JSON should contain encrypted_name")
        
        // Round-trip should lose suggestedName but keep encryptedName
        val decoded = json.decodeFromString<MailboxPayload>(jsonStr) as KeyExchangeInitPayload
        assertEquals("", decoded.suggestedName)
        assertContentEquals(payload.encryptedName, decoded.encryptedName)
    }
}
