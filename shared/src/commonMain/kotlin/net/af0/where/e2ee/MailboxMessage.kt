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

    override fun serialize(
        encoder: Encoder,
        value: ByteArray,
    ) = encoder.encodeString(Base64.encode(value))

    override fun deserialize(decoder: Decoder): ByteArray = Base64.decode(decoder.decodeString())
}

/**
 * Sealed base class for all payloads exchanged through the mailbox API (§9).
 *
 * Encode/decode with a Json instance that has `classDiscriminator = "type"`:
 *   val json = Json { classDiscriminator = "type" }
 *   json.encodeToString(MailboxPayload.serializer(), payload)
 */
@Serializable
sealed class MailboxPayload

/**
 * An encrypted location frame (Alice → Bob, §9.1).
 *
 * @property epoch  DH ratchet epoch (uint32).
 * @property seq    Monotone counter as a decimal string, to avoid JS uint64 precision loss.
 * @property ct     AES-256-GCM ciphertext + 16-byte tag (base64 on the wire).
 */
@Serializable
@SerialName("EncryptedLocation")
data class EncryptedLocationPayload(
    val epoch: Int,
    val seq: String,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
) : MailboxPayload() {
    /** Parses [seq] as a Long for use in protocol logic. */
    fun seqAsLong(): Long = seq.toLong()
}

/** Wire representation of a single One-Time Pre-Key (id + base64 public key). */
@Serializable
data class OPKWire(
    val id: Int,
    @Serializable(with = ByteArrayBase64Serializer::class) val pub: ByteArray,
) {
    fun toOPK(): OPK = OPK(id = id, pub = pub)
}

/**
 * Bob's authenticated batch of One-Time Pre-Keys (Bob → Alice, §9.3).
 *
 * @property keys  OPKs in the bundle.
 * @property mac   HMAC-SHA-256 over the canonical binary encoding (see [PreKeyBundleOps]).
 */
@Serializable
@SerialName("PreKeyBundle")
data class PreKeyBundlePayload(
    val keys: List<OPKWire>,
    @Serializable(with = ByteArrayBase64Serializer::class) val mac: ByteArray,
) : MailboxPayload() {
    fun toOPKList(): List<OPK> = keys.map { it.toOPK() }
}

/**
 * DH epoch rotation announcement (Alice → Bob, §9.3).
 *
 * @property epoch     New epoch number (uint32).
 * @property opkId     ID of the Bob OPK Alice consumed for this ratchet step.
 * @property newEkPub  Alice's new X25519 ephemeral public key (32 bytes, base64).
 * @property ts        Unix timestamp in seconds (uint64).
 * @property ct        AEAD ciphertext authenticating the rotation (see [Session.buildEpochRotationCt]).
 */
@Serializable
@SerialName("EpochRotation")
data class EpochRotationPayload(
    val epoch: Int,
    @SerialName("opk_id") val opkId: Int,
    @SerialName("new_ek_pub")
    @Serializable(with = ByteArrayBase64Serializer::class) val newEkPub: ByteArray,
    val ts: Long,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
) : MailboxPayload()

/**
 * Optional acknowledgment from Bob after processing an EpochRotation (Bob → Alice, §9.3).
 *
 * @property epochSeen  The epoch number Bob successfully processed.
 * @property ts         Unix timestamp in seconds.
 * @property ct         AEAD ciphertext authenticating the ack (see [Session.buildRatchetAckCt]).
 */
@Serializable
@SerialName("RatchetAck")
data class RatchetAckPayload(
    @SerialName("epoch_seen") val epochSeen: Int,
    val ts: Long,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
) : MailboxPayload()

/**
 * Bob's KeyExchangeInit posted to the discovery token address (§4.2).
 *
 * @property token           Hex-encoded T_AB_0 (16 bytes) — the pairwise routing token Bob computed.
 * @property ekPub           Bob's X25519 ephemeral public key (32 bytes).
 * @property keyConfirmation HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub).
 * @property suggestedName   Bob's suggested display name for Alice.
 */
@Serializable
@SerialName("KeyExchangeInit")
data class KeyExchangeInitPayload(
    val token: String,
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    @SerialName("key_confirmation")
    @Serializable(with = ByteArrayBase64Serializer::class) val keyConfirmation: ByteArray,
    @SerialName("suggested_name") val suggestedName: String = "",
) : MailboxPayload() {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitPayload) return false
        return token == other.token && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) && suggestedName == other.suggestedName
    }
    override fun hashCode(): Int = token.hashCode()
}
