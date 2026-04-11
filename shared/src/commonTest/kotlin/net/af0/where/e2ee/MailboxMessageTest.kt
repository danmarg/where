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
    // KeyExchangeInitPayload round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `KeyExchangeInitPayload serialises and deserialises`() {
        val ekPub = ByteArray(32) { it.toByte() }
        val keyConf = ByteArray(32) { (it + 1).toByte() }
        val original = KeyExchangeInitPayload(token = "deadbeef01234567", ekPub = ekPub, keyConfirmation = keyConf, suggestedName = "Alice")
        val encoded = json.encodeToString(MailboxPayload.serializer(), original)
        val decoded = json.decodeFromString<MailboxPayload>(encoded)
        assertIs<KeyExchangeInitPayload>(decoded)
        assertEquals("deadbeef01234567", decoded.token)
        assertContentEquals(ekPub, decoded.ekPub)
        assertContentEquals(keyConf, decoded.keyConfirmation)
        assertEquals("Alice", decoded.suggestedName)
    }

    // ---------------------------------------------------------------------------
    // Cross-type discrimination
    // ---------------------------------------------------------------------------

    @Test
    fun `payload types round-trip via sealed class deserializer`() {
        val payloads: List<MailboxPayload> =
            listOf(
                EncryptedLocationPayload(seq = "1", ct = ByteArray(32)),
                KeyExchangeInitPayload(token = "deadbeef", ekPub = ByteArray(32), keyConfirmation = ByteArray(32), suggestedName = "Alice"),
            )
        for (payload in payloads) {
            val encoded = json.encodeToString(MailboxPayload.serializer(), payload)
            val decoded = json.decodeFromString<MailboxPayload>(encoded)
            assertEquals(payload::class, decoded::class)
        }
    }
}
