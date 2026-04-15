package net.af0.where.e2ee

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
private val qrJson = Json {
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
    @Serializable(with = ByteArrayBase64Serializer::class) val rootKey: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val sendChainKey: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val recvChainKey: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val sendToken: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val recvToken: ByteArray,
    val sendSeq: Long,
    val recvSeq: Long,
    // SECURITY NOTE: We rely on the app layer to store the serialized SessionState in
    // secure storage (e.g., iOS Keychain / Android EncryptedSharedPreferences) because
    // localDhPriv MUST be persisted across app restarts to allow DH ratcheting.
    @Serializable(with = ByteArrayBase64Serializer::class) val localDhPriv: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val localDhPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val remoteDhPub: ByteArray,
    // The DH public key from the epoch immediately preceding remoteDhPub. Used for
    // bucketed out-of-order message processing to prevent epoch rotation from
    // causing silent message loss if messages from prior epochs arrive late.
    @Serializable(with = ByteArrayBase64Serializer::class) val lastRemoteDhPub: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val prevSendToken: ByteArray,
    val isSendTokenPending: Boolean,
    val isAlice: Boolean,
    // REPLAY PROTECTION & OUT-OF-ORDER SUPPORT
    // Map of (remoteDhPubHex + "_" + seq) to [MK (32) || Nonce (12)]
    val skippedMessageKeys: Map<
        String,
        @Serializable(with = ByteArrayBase64Serializer::class)
        ByteArray,
        > = emptyMap(),
    // Recent DH public keys seen (to reject replays from epochs older than lastRemoteDhPub)
    // Stored as hex strings for O(1) lookup and clean serialization.
    val seenRemoteDhPubs: Set<String> = emptySet(),
    // Previous send and receive chain lengths (§4.4) - used to bind sequence numbers across epochs in AAD.
    val pn: Long = 0,
    val pr: Long = 0,
    // Set to true if we've received a new DH key but haven't ratcheted our send chain yet.
    val needsRatchet: Boolean = false,
    // HEADER ENCRYPTION (#186)
    @Serializable(with = ByteArrayBase64Serializer::class) val headerKey: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val sendHeaderKey: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val nextHeaderKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (other !is SessionState) return false
        return rootKey.contentEquals(other.rootKey) &&
            sendChainKey.contentEquals(other.sendChainKey) &&
            recvChainKey.contentEquals(other.recvChainKey) &&
            sendToken.contentEquals(other.sendToken) &&
            recvToken.contentEquals(other.recvToken) &&
            sendSeq == other.sendSeq &&
            recvSeq == other.recvSeq &&
            localDhPriv.contentEquals(other.localDhPriv) &&
            localDhPub.contentEquals(other.localDhPub) &&
            remoteDhPub.contentEquals(other.remoteDhPub) &&
            lastRemoteDhPub.contentEquals(other.lastRemoteDhPub) &&
            aliceEkPub.contentEquals(other.aliceEkPub) &&
            bobEkPub.contentEquals(other.bobEkPub) &&
            aliceFp.contentEquals(other.aliceFp) &&
            bobFp.contentEquals(other.bobFp) &&
            prevSendToken.contentEquals(other.prevSendToken) &&
            isSendTokenPending == other.isSendTokenPending &&
            isAlice == other.isAlice &&
            skippedMessageKeys.size == other.skippedMessageKeys.size &&
            skippedMessageKeys.all { (k, v) -> other.skippedMessageKeys[k]?.contentEquals(v) == true } &&
            seenRemoteDhPubs == other.seenRemoteDhPubs &&
            pn == other.pn &&
            pr == other.pr &&
            needsRatchet == other.needsRatchet &&
            headerKey.contentEquals(other.headerKey) &&
            sendHeaderKey.contentEquals(other.sendHeaderKey) &&
            nextHeaderKey.contentEquals(other.nextHeaderKey)
    }

    override fun hashCode(): Int {
        var h = rootKey.contentHashCode()
        h = 31 * h + sendChainKey.contentHashCode()
        h = 31 * h + recvChainKey.contentHashCode()
        h = 31 * h + sendToken.contentHashCode()
        h = 31 * h + recvToken.contentHashCode()
        h = 31 * h + sendSeq.hashCode()
        h = 31 * h + recvSeq.hashCode()
        h = 31 * h + localDhPriv.contentHashCode()
        h = 31 * h + localDhPub.contentHashCode()
        h = 31 * h + remoteDhPub.contentHashCode()
        h = 31 * h + lastRemoteDhPub.contentHashCode()
        h = 31 * h + aliceEkPub.contentHashCode()
        h = 31 * h + bobEkPub.contentHashCode()
        h = 31 * h + aliceFp.contentHashCode()
        h = 31 * h + bobFp.contentHashCode()
        h = 31 * h + prevSendToken.contentHashCode()
        h = 31 * h + isSendTokenPending.hashCode()
        h = 31 * h + isAlice.hashCode()
        // Content-based hash for collections containing ByteArrays
        var skipHash = 0
        skippedMessageKeys.forEach { (k, v) -> skipHash += 31 * k.hashCode() + v.contentHashCode() }
        h = 31 * h + skipHash
        h = 31 * h + seenRemoteDhPubs.hashCode()
        h = 31 * h + pn.hashCode()
        h = 31 * h + pr.hashCode()
        h = 31 * h + needsRatchet.hashCode()
        h = 31 * h + headerKey.contentHashCode()
        h = 31 * h + sendHeaderKey.contentHashCode()
        h = 31 * h + nextHeaderKey.contentHashCode()
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
    // hex(SHA-256(ekPub)[0:20])
    @SerialName("fingerprint")
    val fingerprint: String,
    // Fresh random 32-byte secret; HKDF IKM for the discovery token (§4.2).
    @SerialName("discovery_secret")
    @Serializable(with = ByteArrayBase64Serializer::class) val discoverySecret: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is QrPayload) return false
        return protocolVersion == other.protocolVersion && ekPub.contentEquals(other.ekPub) && suggestedName == other.suggestedName &&
            fingerprint == other.fingerprint && discoverySecret.contentEquals(other.discoverySecret)
    }

    override fun hashCode(): Int {
        var h = protocolVersion
        h = 31 * h + ekPub.contentHashCode()
        h = 31 * h + suggestedName.hashCode()
        h = 31 * h + fingerprint.hashCode()
        h = 31 * h + discoverySecret.contentHashCode()
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
            val fragment = if (url.startsWith("where://")) {
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
    // T_AB_0 (16 bytes) — mailbox address
    @Serializable(with = ByteArrayBase64Serializer::class) val token: ByteArray,
    // Bob's ephemeral X25519 public key
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    // HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
    @Serializable(with = ByteArrayBase64Serializer::class) val keyConfirmation: ByteArray,
    val suggestedName: String,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitMessage) return false
        return protocolVersion == other.protocolVersion && token.contentEquals(other.token) && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) && suggestedName == other.suggestedName
    }

    override fun hashCode(): Int {
        var h = protocolVersion
        h = 31 * h + token.contentHashCode()
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

@Serializable
sealed class ConnectionStatus {
    @Serializable
    object Ok : ConnectionStatus()

    @Serializable
    data class Error(val message: String) : ConnectionStatus()
}

/** Result of Alice polling for a pending invite scan (#176). */
data class PendingInviteResult(
    val payload: KeyExchangeInitPayload,
    /** True if multiple people (or multiple scans) were detected in the discovery mailbox. */
    val multipleScansDetected: Boolean,
)

/**
 * A message that is pending delivery (used for transactional safety and recovery).
 */
@Serializable
data class EncryptedOutboxMessage(
    val v: Int = 1,
    val token: String,
    val payload: MailboxPayload,
)

/** Output of a symmetric ratchet step (KDF_CK). */
internal data class ChainStep(
    val newChainKey: ByteArray,
    val messageKey: ByteArray,
    val messageNonce: ByteArray,
)

/** Output of a DH ratchet step (KDF_RK). */
internal data class RatchetStep(
    val newRootKey: ByteArray,
    val newChainKey: ByteArray,
    val newHeaderKey: ByteArray,
)
