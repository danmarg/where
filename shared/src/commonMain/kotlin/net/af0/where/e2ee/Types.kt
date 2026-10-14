package net.af0.where.e2ee

import dev.icerock.moko.resources.desc.StringDesc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private val qrJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * Raw X25519 keypair. Both fields are 32-byte little-endian representations
 * as defined by RFC 7748.
 */
data class RawKeyPair(val priv: ByteArray, val pub: ByteArray) {
    override fun equals(other: Any?): Boolean = other is RawKeyPair && priv.contentEquals(other.priv) && pub.contentEquals(other.pub)

    override fun hashCode(): Int = 31 * priv.contentHashCode() + pub.contentHashCode()
}

/**
 * Per-friendship ratchet state maintained by both sides.
 * All byte arrays are copies; callers must zero them after use.
 */
@Serializable
data class SessionState(
    val rootKey: RootKey,
    val sendChainKey: ChainKey,
    val recvChainKey: ChainKey,
    val sendToken: RoutingToken,
    val recvToken: RoutingToken,
    val sendSeq: Long,
    val recvSeq: Long,
    // SECURITY NOTE: We rely on the app layer to store the serialized SessionState in
    // secure storage (e.g., iOS Keychain / Android EncryptedSharedPreferences) because
    // localDhPriv MUST be persisted across app restarts to allow DH ratcheting.
    val localDhPriv: DhPrivateKey,
    val localDhPub: DhPublicKey,
    val remoteDhPub: DhPublicKey,
    val aliceEkPub: DhPublicKey,
    val bobEkPub: DhPublicKey,
    val aliceFp: Fingerprint,
    val bobFp: Fingerprint,
    val localFp: Fingerprint = Fingerprint(ByteArray(0)),
    val remoteFp: Fingerprint = Fingerprint(ByteArray(0)),
    val prevSendToken: RoutingToken,
    val prevSendHeaderKey: HeaderKey = HeaderKey(ByteArray(0)),
    val isAlice: Boolean,
    // REPLAY PROTECTION & OUT-OF-ORDER SUPPORT
    // Map of (remoteDhPubHex + ":" + seq) to the cached key for that message
    val skippedMessageKeys: Map<String, SkippedMessageKey> = emptyMap(),
    // Previous send and receive chain lengths (§4.4) - used to bind sequence numbers across epochs in AAD.
    val pn: Long = 0,
    val pr: Long = 0,
    // (A former `needsRatchet` flag is ignored when loading old blobs: ignoreUnknownKeys.)
    // HEADER ENCRYPTION (#186)
    val headerKey: HeaderKey = HeaderKey(ByteArray(0)),
    val sendHeaderKey: HeaderKey = HeaderKey(ByteArray(0)),
    val nextHeaderKey: HeaderKey = HeaderKey(ByteArray(0)),
) {
    /**
     * This party's and the peer's fingerprints, for direction-bound tokens and AAD. Derived from
     * the role rather than read from localFp/remoteFp, which are empty in sessions stored before
     * those fields existed.
     */
    internal val parties: Parties get() = Parties.of(isAlice, aliceFp, bobFp)

    /**
     * Where the message numbered [seq] in the current send chain goes, and the header key that
     * seals it (§4.3). The first message of an epoch goes to the previous epoch's mailbox under the
     * previous header key, because the peer is still polling there; later ones use the current pair.
     */
    internal fun transportFor(seq: Long): Transport =
        if (seq == 1L) Transport(prevSendToken, prevSendHeaderKey) else Transport(sendToken, sendHeaderKey)

    /** Header AAD (#186): both bootstrap fingerprints, Alice's first, the same on both sides. */
    internal fun headerAad(): ByteArray = aliceFp.bytes + bobFp.bytes

    /**
     * Creates a deep copy of the session state, ensuring all key
     * material is duplicated into fresh buffers. This prevents accidental key
     * zeroization from affecting multiple copies of the state (§5.5).
     */
    fun deepCopy(): SessionState =
        this.copy(
            rootKey = rootKey.copyOf(),
            sendChainKey = sendChainKey.copyOf(),
            recvChainKey = recvChainKey.copyOf(),
            sendToken = sendToken.copyOf(),
            recvToken = recvToken.copyOf(),
            localDhPriv = localDhPriv.copyOf(),
            prevSendHeaderKey = prevSendHeaderKey.copyOf(),
            localDhPub = localDhPub.copyOf(),
            remoteDhPub = remoteDhPub.copyOf(),
            aliceEkPub = aliceEkPub.copyOf(),
            bobEkPub = bobEkPub.copyOf(),
            aliceFp = aliceFp.copyOf(),
            bobFp = bobFp.copyOf(),
            localFp = localFp.copyOf(),
            remoteFp = remoteFp.copyOf(),
            prevSendToken = prevSendToken.copyOf(),
            skippedMessageKeys = skippedMessageKeys.mapValues { it.value.copyOf() },
            headerKey = headerKey.copyOf(),
            sendHeaderKey = sendHeaderKey.copyOf(),
            nextHeaderKey = nextHeaderKey.copyOf(),
        )

    /**
     * Wipes every secret-bearing buffer of a state this code exclusively owns (e.g. a
     * speculative ratchet result that was never committed). Never call it on a state whose
     * buffers may be shared with another live state.
     */
    internal fun zeroizeAll() {
        keyFields().forEach { it.zeroize() }
        skippedMessageKeys.values.forEach { it.zeroize() }
    }

    /**
     * Every key field declared in the primary constructor, in declaration order.
     * equals()/hashCode() fold over this instead of each maintaining its own hand-written
     * field list, so a field added to the constructor but missed here fails loudly (wrong
     * equals/hashCode breaks tests immediately) instead of silently. deepCopy() above still
     * needs its own per-field .copyOf() call, since Kotlin's data class copy() requires named
     * arguments and can't be driven from a list — SessionStateDeepCopyTest guards that one
     * against the same field-added-but-missed risk instead.
     */
    private fun keyFields(): Array<KeyBytes> =
        arrayOf(
            rootKey, sendChainKey, recvChainKey, sendToken, recvToken,
            localDhPriv, localDhPub, remoteDhPub, aliceEkPub, bobEkPub,
            aliceFp, bobFp, localFp, remoteFp, prevSendToken,
            prevSendHeaderKey, headerKey, sendHeaderKey, nextHeaderKey,
        )

    override fun equals(other: Any?): Boolean {
        if (other !is SessionState) return false
        val fields = keyFields()
        val otherFields = other.keyFields()
        return fields.indices.all { i -> fields[i] == otherFields[i] } &&
            sendSeq == other.sendSeq &&
            recvSeq == other.recvSeq &&
            isAlice == other.isAlice &&
            skippedMessageKeys == other.skippedMessageKeys &&
            pn == other.pn &&
            pr == other.pr
    }

    override fun hashCode(): Int {
        var h = 0
        for (field in keyFields()) h = 31 * h + field.hashCode()
        h = 31 * h + sendSeq.hashCode()
        h = 31 * h + recvSeq.hashCode()
        h = 31 * h + isAlice.hashCode()
        var skipHash = 0
        skippedMessageKeys.forEach { (k, v) -> skipHash += 31 * k.hashCode() + v.hashCode() }
        h = 31 * h + skipHash
        h = 31 * h + pn.hashCode()
        h = 31 * h + pr.hashCode()
        return h
    }
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

@Serializable
enum class LocationPrecision {
    FINE,
    COARSE,
}

/** Plaintext location payload (before encryption / after decryption). */
@Serializable
data class LocationPlaintext(
    val lat: Double,
    val lng: Double,
    val acc: Double,
    val ts: Long,
    val precision: LocationPrecision = LocationPrecision.FINE,
    val stationary: Boolean = false,
) {
    fun blur(): LocationPlaintext =
        if (precision == LocationPrecision.COARSE) {
            copy(
                lat = kotlin.math.round(lat * 100.0) / 100.0,
                lng = kotlin.math.round(lng * 100.0) / 100.0,
                // ~1.1km minimum accuracy for coarse
                acc = kotlin.math.max(acc, 1100.0),
            )
        } else {
            this
        }
}

/**
 * Alice's QR / invite-link payload.
 * Contains Alice's ephemeral public key and a fresh random secret used to derive
 * the discovery token. Only someone who received the QR (out-of-band) knows
 * [discoverySecret], so the discovery mailbox address is not computable by the
 * server or a network observer who later sees EK_A.pub in a KeyExchangeInit.
 */
@Serializable
data class QrPayload(
    @SerialName("protocol_version")
    val protocolVersion: Int = PROTOCOL_VERSION,
    // Alice's ephemeral X25519 public key (32 bytes)
    @SerialName("ek_pub")
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    @SerialName("suggested_name")
    val suggestedName: String,
    // Fresh random 32-byte secret; HKDF IKM for the discovery token (§4.2).
    @SerialName("discovery_secret")
    @Serializable(with = ByteArrayBase64Serializer::class) val discoverySecret: ByteArray,
    // Epoch seconds after which the inviter may have dropped this invite. Optional: payloads from
    // older clients omit it (never expires), and older clients ignore it. UX metadata only; it is
    // unauthenticated and not a security control (see docs/e2ee-location-sync.md §4.2).
    @SerialName("expires_at")
    val expiresAt: Long? = null,
) {
    /** True if [expiresAt] is set and [nowSeconds] is past it by more than the clock-skew grace. */
    fun isExpired(nowSeconds: Long): Boolean = expiresAt != null && nowSeconds - INVITE_EXPIRY_GRACE_SECONDS > expiresAt

    fun isExpired(): Boolean = isExpired(currentTimeSeconds())

    override fun equals(other: Any?): Boolean {
        if (other !is QrPayload) return false
        return protocolVersion == other.protocolVersion && ekPub.contentEquals(other.ekPub) && suggestedName == other.suggestedName &&
            discoverySecret.contentEquals(other.discoverySecret) && expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var h = protocolVersion
        h = 31 * h + ekPub.contentHashCode()
        h = 31 * h + suggestedName.hashCode()
        h = 31 * h + discoverySecret.contentHashCode()
        h = 31 * h + (expiresAt?.hashCode() ?: 0)
        return h
    }

    /** Encodes the payload as a Base64 URL-safe string for use in invite links. */
    @OptIn(ExperimentalEncodingApi::class)
    fun toUrl(): String {
        val jsonStr = qrJson.encodeToString(serializer(), this)
        val encoded = Base64.UrlSafe.encode(jsonStr.encodeToByteArray())
        return "https://where.af0.net/invite#$encoded"
    }

    companion object {
        /** Decodes a QrPayload from an invite link URL. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromUrl(url: String): QrPayload? {
            // Support both https://where.af0.net/invite#$encoded AND where://invite?q=$encoded
            val fragment =
                if (url.startsWith("where://")) {
                    url.substringAfter("q=", "").substringBefore("&")
                } else {
                    url.substringAfter("#", "")
                }
            if (fragment.isEmpty()) return null
            return try {
                // Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL) handles both padded and unpadded.
                val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
                val decoded = decoder.decode(fragment).decodeToString()
                qrJson.decodeFromString(serializer(), decoded)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Bob's KeyExchangeInit message sent to the mailbox. */
data class KeyExchangeInitMessage(
    val protocolVersion: Int = PROTOCOL_VERSION,
    // Bob's ephemeral X25519 public key
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    // HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
    @Serializable(with = ByteArrayBase64Serializer::class) val keyConfirmation: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val encryptedName: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitMessage) return false
        return protocolVersion == other.protocolVersion && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) && encryptedName.contentEquals(other.encryptedName)
    }

    override fun hashCode(): Int {
        var h = protocolVersion
        h = 31 * h + ekPub.contentHashCode()
        return h
    }
}

@Serializable
sealed class InviteState {
    @Serializable
    object None : InviteState()

    @Serializable
    data class Pending(val qr: QrPayload) : InviteState()
}

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()

    data class Error(val message: StringDesc) : ConnectionStatus()
}

/** Result of polling for incoming handshakes (KeyExchangeInit). */
data class PendingInviteResult(
    val payload: KeyExchangeInitPayload,
    // The public key of the person who scanned our QR
    val scannerEkPub: ByteArray,
    // The public key of the QR code they scanned (ours)
    val inviteEkPub: ByteArray,
    val multipleScansDetected: Boolean,
    val pairingError: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PendingInviteResult
        if (payload != other.payload) return false
        if (!scannerEkPub.contentEquals(other.scannerEkPub)) return false
        if (!inviteEkPub.contentEquals(other.inviteEkPub)) return false
        if (multipleScansDetected != other.multipleScansDetected) return false
        if (pairingError != other.pairingError) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payload.hashCode()
        result = 31 * result + scannerEkPub.contentHashCode()
        result = 31 * result + inviteEkPub.contentHashCode()
        result = 31 * result + multipleScansDetected.hashCode()
        result = 31 * result + (pairingError?.hashCode() ?: 0)
        return result
    }
}

/**
 * A message that is pending delivery (used for transactional safety and recovery).
 */
@Serializable
data class EncryptedOutboxMessage(
    val token: String,
    val payload: MailboxPayload,
    val msgId: String = payload.msgId,
    val createdAt: Long = currentTimeMillis(),
)

/** Mailbox and header key for one outgoing message; see [SessionState.transportFor]. */
internal class Transport(val token: RoutingToken, val headerKey: HeaderKey)

/**
 * An encrypted message, the state to persist after sending it, and the mailbox it must be posted
 * to. Destructures as (state, payload) like the pair it replaced.
 */
data class OutgoingMessage(
    val state: SessionState,
    val payload: EncryptedMessagePayload,
    val destination: RoutingToken,
)

/**
 * What became of one received frame. The cases that authenticated the frame carry the state to
 * persist; the others leave the caller's state as it was. A `when` over this is exhaustive, so a
 * caller can't treat an undeliverable frame like a forgery and drop its state. Only [Session]
 * creates these.
 */
sealed class ReceivedMessage {
    /** Authenticated and decoded. Persist [state] and deliver [plaintext]. */
    @ConsistentCopyVisibility
    data class Delivered internal constructor(
        val state: SessionState,
        val plaintext: MessagePlaintext,
    ) : ReceivedMessage()

    /**
     * The header authenticated but the body failed AEAD or its plaintext was malformed. The
     * ratchet advanced and the message key is spent, so [state] MUST be persisted (§8.3.1(4)):
     * a corrupted frame has to leave the same state as a dropped one. Nothing to deliver.
     */
    class Undeliverable internal constructor(
        val state: SessionState,
        val cause: Throwable,
    ) : ReceivedMessage()

    /** A frame already received. Keep the input state. */
    class Replay internal constructor(val cause: ReplayException) : ReceivedMessage()

    /** Not authenticated as the peer's, or not processable (e.g. too large a gap). Keep the input state. */
    class Rejected internal constructor(val cause: Throwable) : ReceivedMessage()
}

/** Output of a symmetric ratchet step (KDF_CK). */
internal class ChainStep(
    val newChainKey: ChainKey,
    val messageKey: MessageKey,
    val messageNonce: MessageNonce,
)

/** Output of a DH ratchet step (KDF_RK). */
internal class RatchetStep(
    val newRootKey: RootKey,
    val newChainKey: ChainKey,
    val newHeaderKey: HeaderKey,
)
