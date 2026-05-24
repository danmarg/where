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

enum class RatchetPhase {
    STABLE, // needsRatchet=false — normal operating state
    NEEDS_KEEPALIVE, // needsRatchet=true — received new remoteDhPub, must send keepalive before next poll
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
    @Serializable(with = ByteArrayBase64Serializer::class) val prevLocalDhPub: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val remoteDhPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val localFp: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val remoteFp: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val prevSendToken: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val prevSendChainKey: ByteArray = ByteArray(0),
    @Serializable(with = ByteArrayBase64Serializer::class) val prevSendHeaderKey: ByteArray = ByteArray(0),
    val isAlice: Boolean,
    // REPLAY PROTECTION & OUT-OF-ORDER SUPPORT
    // Map of (remoteDhPubHex + ":" + seq) to [MK (32) || Nonce (12) || PN (8) || Timestamp (8)]
    val skippedMessageKeys: Map<
        String,
        @Serializable(with = ByteArrayBase64Serializer::class)
        ByteArray,
        > = emptyMap(),
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
    /**
     * Creates a deep copy of the session state, ensuring all sensitive ByteArray
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
            prevSendChainKey = prevSendChainKey.copyOf(),
            prevSendHeaderKey = prevSendHeaderKey.copyOf(),
            localDhPub = localDhPub.copyOf(),
            prevLocalDhPub = prevLocalDhPub.copyOf(),
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
            prevLocalDhPub.contentEquals(other.prevLocalDhPub) &&
            remoteDhPub.contentEquals(other.remoteDhPub) &&
            aliceEkPub.contentEquals(other.aliceEkPub) &&
            bobEkPub.contentEquals(other.bobEkPub) &&
            aliceFp.contentEquals(other.aliceFp) &&
            bobFp.contentEquals(other.bobFp) &&
            localFp.contentEquals(other.localFp) &&
            remoteFp.contentEquals(other.remoteFp) &&
            prevSendToken.contentEquals(other.prevSendToken) &&
            prevSendChainKey.contentEquals(other.prevSendChainKey) &&
            prevSendHeaderKey.contentEquals(other.prevSendHeaderKey) &&
            isAlice == other.isAlice &&
            skippedMessageKeys.size == other.skippedMessageKeys.size &&
            skippedMessageKeys.all { (k, v) -> other.skippedMessageKeys[k]?.contentEquals(v) == true } &&
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
        h = 31 * h + prevLocalDhPub.contentHashCode()
        h = 31 * h + remoteDhPub.contentHashCode()
        h = 31 * h + aliceEkPub.contentHashCode()
        h = 31 * h + bobEkPub.contentHashCode()
        h = 31 * h + aliceFp.contentHashCode()
        h = 31 * h + bobFp.contentHashCode()
        h = 31 * h + localFp.contentHashCode()
        h = 31 * h + remoteFp.contentHashCode()
        h = 31 * h + prevSendToken.contentHashCode()
        h = 31 * h + prevSendChainKey.contentHashCode()
        h = 31 * h + prevSendHeaderKey.contentHashCode()
        h = 31 * h + isAlice.hashCode()
        // Content-based hash for collections containing ByteArrays
        var skipHash = 0
        skippedMessageKeys.forEach { (k, v) -> skipHash += 31 * k.hashCode() + v.contentHashCode() }
        h = 31 * h + skipHash
        h = 31 * h + pn.hashCode()
        h = 31 * h + pr.hashCode()
        h = 31 * h + needsRatchet.hashCode()
        h = 31 * h + headerKey.contentHashCode()
        h = 31 * h + sendHeaderKey.contentHashCode()
        h = 31 * h + nextHeaderKey.contentHashCode()
        return h
    }
}

fun SessionState.phase(): RatchetPhase =
    when {
        needsRatchet -> RatchetPhase.NEEDS_KEEPALIVE
        else -> RatchetPhase.STABLE
    }

fun SessionState.assertInvariants() {
    check(skippedMessageKeys.size <= MAX_SKIPPED_KEYS) {
        "skippedMessageKeys overflow: ${skippedMessageKeys.size} > $MAX_SKIPPED_KEYS"
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
    // T_AB_0 (16 bytes) — mailbox address
    @Serializable(with = ByteArrayBase64Serializer::class) val token: ByteArray,
    // Bob's ephemeral X25519 public key
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    // HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
    @Serializable(with = ByteArrayBase64Serializer::class) val keyConfirmation: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val encryptedName: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitMessage) return false
        return protocolVersion == other.protocolVersion && token.contentEquals(other.token) && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) && encryptedName.contentEquals(other.encryptedName)
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

sealed class ConnectionStatus {
    object Ok : ConnectionStatus()

    data class Error(val message: StringDesc) : ConnectionStatus()
}

/** Result of polling for incoming handshakes (KeyExchangeInit). */
data class PendingInviteResult(
    val payload: KeyExchangeInitPayload,
    val scannerEkPub: ByteArray, // The public key of the person who scanned our QR
    val inviteEkPub: ByteArray,  // The public key of the QR code they scanned (ours)
    val multipleScansDetected: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PendingInviteResult
        if (payload != other.payload) return false
        if (!scannerEkPub.contentEquals(other.scannerEkPub)) return false
        if (!inviteEkPub.contentEquals(other.inviteEkPub)) return false
        if (multipleScansDetected != other.multipleScansDetected) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payload.hashCode()
        result = 31 * result + scannerEkPub.contentHashCode()
        result = 31 * result + inviteEkPub.contentHashCode()
        result = 31 * result + multipleScansDetected.hashCode()
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
