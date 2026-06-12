package net.af0.where.e2ee

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 serializer for ByteArray — encodes as a standard base64 string in JSON.
 */
@OptIn(ExperimentalEncodingApi::class)
object ByteArrayBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ByteArrayBase64", PrimitiveKind.STRING)

    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    override fun serialize(
        encoder: Encoder,
        value: ByteArray,
    ) = encoder.encodeString(base64.encode(value))

    override fun deserialize(decoder: Decoder): ByteArray = base64.decode(decoder.decodeString())
}

/**
 * Sealed base class for all payloads exchanged through the mailbox API (§9).
 *
 * Encode/decode with a Json instance that has `classDiscriminator = "type"`:
 *   val json = Json { classDiscriminator = "type" }
 *   json.encodeToString(MailboxPayload.serializer(), payload)
 */
@Serializable
sealed class MailboxPayload {
    /** Protocol version (§9). Always 1 for this release. */
    abstract val v: Int

    /** Unique message ID for idempotency (§9). */
    abstract val msgId: String
}

/**
 * An encrypted message frame (Alice ↔ Bob, §9.1).
 *
 * @property v       Protocol version.
 * @property envelope Sender's current DH ratchet public key and metadata.
 * @property ct      ChaCha20-Poly1305 ciphertext + 16-byte tag.
 * @property msgId   Unique ID (sha256(ct)) for server-side idempotency.
 */
@Serializable
@SerialName("EncryptedMessage")
data class EncryptedMessagePayload(
    override val v: Int = PROTOCOL_VERSION,
    @Serializable(with = ByteArrayBase64Serializer::class) val envelope: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
    override val msgId: String = sha256(ct).toHex(),
) : MailboxPayload()

/**
 * Bob's KeyExchangeInit posted to the discovery token address (§4.2).
 *
 * @property v               Protocol version.
 * @property token           Hex-encoded T_AB_0 (16 bytes) — the pairwise routing token Bob computed.
 *                           **Deprecated:** Alice derives T_AB_0 independently and the check is
 *                           redundant with `key_confirmation`. This field is optional and ignored
 *                           by Alice; it will be removed in a future protocol version.
 * @property ekPub           Bob's X25519 ephemeral public key (32 bytes).
 * @property keyConfirmation HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub).
 * @property encryptedName   Bob's suggested display name for Alice, encrypted under K_name.
 * @property suggestedName   Bob's suggested display name for Alice (transient/decrypted).
 * @property msgId           Unique ID (sha256(ekPub)) for server-side idempotency.
 */
@Serializable
@SerialName("KeyExchangeInit")
data class KeyExchangeInitPayload(
    override val v: Int = PROTOCOL_VERSION,
    val token: String? = null,
    @SerialName("ek_pub")
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    @SerialName("key_confirmation")
    @Serializable(with = ByteArrayBase64Serializer::class) val keyConfirmation: ByteArray,
    @SerialName("encrypted_name")
    @Serializable(with = ByteArrayBase64Serializer::class) val encryptedName: ByteArray = ByteArray(0),
    @kotlinx.serialization.Transient var suggestedName: String = "",
    override val msgId: String = sha256(ekPub).toHex(),
) : MailboxPayload() {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitPayload) return false
        return token == other.token && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) &&
            encryptedName.contentEquals(other.encryptedName) &&
            suggestedName == other.suggestedName
    }

    override fun hashCode(): Int {
        var h = token?.hashCode() ?: 0
        h = 31 * h + encryptedName.contentHashCode()
        h = 31 * h + suggestedName.hashCode()
        return h
    }
}
