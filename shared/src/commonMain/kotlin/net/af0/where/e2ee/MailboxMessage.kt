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
sealed class MailboxPayload {
    /** Protocol version (§9). Always 1 for this release. */
    abstract val v: Int
}

/**
 * An encrypted location frame (Alice → Bob, §9.1).
 *
 * @property v    Protocol version.
 * @property seq  Monotone counter as a decimal string, to avoid JS uint64 precision loss.
 * @property ct   ChaCha20-Poly1305 ciphertext + 16-byte tag (base64 on the wire).
 */
@Serializable
@SerialName("EncryptedLocation")
data class EncryptedLocationPayload(
    override val v: Int = 1,
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
 * @property v     Protocol version.
 * @property keys  OPKs in the bundle.
 * @property mac   HMAC-SHA-256 over the canonical binary encoding (see [PreKeyBundleOps]).
 */
@Serializable
@SerialName("PreKeyBundle")
data class PreKeyBundlePayload(
    override val v: Int = 1,
    val keys: List<OPKWire>,
    @Serializable(with = ByteArrayBase64Serializer::class) val mac: ByteArray,
) : MailboxPayload() {
    fun toOPKList(): List<OPK> = keys.map { it.toOPK() }
}

/**
 * DH ratchet rotation announcement (Alice → Bob, §9.3).
 *
 * All rotation parameters (new EK, OPK id, new routing token) are carried inside [ct],
 * which is an AEAD blob encrypted under K_rot (see [buildEpochRotationCt]).
 * K_rot is unique per rotation so a fixed all-zero nonce is safe.
 *
 * @property v   Protocol version.
 * @property ct  AEAD ciphertext authenticating the rotation (see [buildEpochRotationCt]).
 */
@Serializable
@SerialName("EpochRotation")
data class EpochRotationPayload(
    override val v: Int = 1,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
) : MailboxPayload()

/**
 * Acknowledgment from Bob confirming receipt of an EpochRotation (Bob → Alice, §9.3).
 *
 * All ack parameters (Bob's new EK) are carried inside [ct], which is an AEAD blob
 * encrypted under K_ack. K_ack is unique per ack so a fixed all-zero nonce is safe.
 *
 * @property v   Protocol version.
 * @property ct  AEAD ciphertext authenticating the ack (see [buildRatchetAckCt]).
 */
@Serializable
@SerialName("RatchetAck")
data class RatchetAckPayload(
    override val v: Int = 1,
    @Serializable(with = ByteArrayBase64Serializer::class) val ct: ByteArray,
) : MailboxPayload()

/**
 * Bob's KeyExchangeInit posted to the discovery token address (§4.2).
 *
 * @property v               Protocol version.
 * @property token           Hex-encoded T_AB_0 (16 bytes) — the pairwise routing token Bob computed.
 * @property ekPub           Bob's X25519 ephemeral public key (32 bytes).
 * @property keyConfirmation HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub).
 * @property suggestedName   Bob's suggested display name for Alice.
 */
@Serializable
@SerialName("KeyExchangeInit")
data class KeyExchangeInitPayload(
    override val v: Int = 1,
    val token: String,
    @SerialName("ek_pub")
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
