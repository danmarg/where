package net.af0.where.e2ee

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw X25519 keypair. Both fields are 32-byte little-endian representations
 * as defined by RFC 7748.
 */
data class RawKeyPair(val priv: ByteArray, val pub: ByteArray) {
    override fun equals(other: Any?): Boolean = other is RawKeyPair && priv.contentEquals(other.priv) && pub.contentEquals(other.pub)

    override fun hashCode(): Int = 31 * priv.contentHashCode() + pub.contentHashCode()
}

/**
 * Per-friendship ratchet state maintained by the sender (Alice).
 * All byte arrays are copies; callers must zero them after use.
 *
 * Fields:
 *   rootKey       – 32-byte root key, updated on every DH ratchet step.
 *   sendChainKey  – 32-byte symmetric chain key; advanced on every location send.
 *   recvChainKey  – 32-byte symmetric chain key; advanced on every location receive.
 *                   Independent from sendChainKey; initialized to the peer's send chain
 *                   so that send and receive ratchets never share key material.
 *   sendToken     – 16-byte opaque token (mailbox address) for outgoing messages.
 *   recvToken     – 16-byte opaque token (mailbox address) for incoming messages.
 *   sendSeq       – Monotonically increasing counter; MUST NOT wrap (session must be
 *                   invalidated and re-keyed if it reaches Long.MAX_VALUE).
 *   recvSeq       – Highest seq received from the peer (for replay rejection).
 *   epoch         – DH ratchet epoch counter (uint32 semantics, stored as Int).
 *   myEkPriv      – 32-byte current ephemeral X25519 private key (deleted after DH ratchet step).
 *   myEkPub       – 32-byte current ephemeral X25519 public key.
 *   theirEkPub    – 32-byte peer's last known ephemeral X25519 public key.
 *   aliceFp       – SHA-256(EK_A.pub) — Alice's session fingerprint.
 *   bobFp         – SHA-256(EK_B.pub) — Bob's session fingerprint.
 *   aliceEkPub    – EK_A.pub — Alice's bootstrap ephemeral public key (stable for session lifetime).
 *   bobEkPub      – EK_B.pub — Bob's bootstrap ephemeral public key (stable for session lifetime).
 *   kBundle       – HKDF(SK, info="Where-v1-BundleAuth") — bundle authentication key.
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
    val epoch: Int,
    @Serializable(with = ByteArrayBase64Serializer::class) val myEkPriv: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val myEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val theirEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobFp: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val bobEkPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val kBundle: ByteArray,
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
            epoch == other.epoch &&
            myEkPriv.contentEquals(other.myEkPriv) &&
            myEkPub.contentEquals(other.myEkPub) &&
            theirEkPub.contentEquals(other.theirEkPub) &&
            aliceFp.contentEquals(other.aliceFp) &&
            bobFp.contentEquals(other.bobFp) &&
            aliceEkPub.contentEquals(other.aliceEkPub) &&
            bobEkPub.contentEquals(other.bobEkPub) &&
            kBundle.contentEquals(other.kBundle)
    }

    override fun hashCode(): Int {
        var h = rootKey.contentHashCode()
        h = 31 * h + sendChainKey.contentHashCode()
        h = 31 * h + recvChainKey.contentHashCode()
        h = 31 * h + sendToken.contentHashCode()
        h = 31 * h + recvToken.contentHashCode()
        h = 31 * h + sendSeq.hashCode()
        h = 31 * h + recvSeq.hashCode()
        h = 31 * h + epoch
        h = 31 * h + myEkPriv.contentHashCode()
        h = 31 * h + myEkPub.contentHashCode()
        h = 31 * h + theirEkPub.contentHashCode()
        h = 31 * h + aliceFp.contentHashCode()
        h = 31 * h + bobFp.contentHashCode()
        h = 31 * h + aliceEkPub.contentHashCode()
        h = 31 * h + bobEkPub.contentHashCode()
        h = 31 * h + kBundle.contentHashCode()
        return h
    }
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Plaintext location payload (before encryption / after decryption). */
data class LocationPlaintext(
    val lat: Double,
    val lng: Double,
    val acc: Double,
    val ts: Long,
)

/**
 * Alice's QR / invite-link payload.
 * Contains Alice's ephemeral public key and a fresh random secret used to derive
 * the discovery token. Only someone who received the QR (out-of-band) knows
 * [discoverySecret], so the discovery mailbox address is not computable by the
 * server or a network observer who later sees EK_A.pub in a KeyExchangeInit.
 */
@Serializable
data class QrPayload(
    // Alice's ephemeral X25519 public key (32 bytes)
    @SerialName("ek_pub")
    @Serializable(with = ByteArrayBase64Serializer::class) val ekPub: ByteArray,
    @SerialName("suggested_name")
    val suggestedName: String,
    // hex(SHA-256(ekPub)[0:8])
    val fingerprint: String,
    // Fresh random 32-byte secret; HKDF IKM for the discovery token (§4.2).
    @SerialName("discovery_secret")
    @Serializable(with = ByteArrayBase64Serializer::class) val discoverySecret: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is QrPayload) return false
        return ekPub.contentEquals(other.ekPub) && suggestedName == other.suggestedName &&
            fingerprint == other.fingerprint && discoverySecret.contentEquals(other.discoverySecret)
    }

    override fun hashCode(): Int {
        var h = ekPub.contentHashCode()
        h = 31 * h + suggestedName.hashCode()
        h = 31 * h + fingerprint.hashCode()
        h = 31 * h + discoverySecret.contentHashCode()
        return h
    }
}

/** Bob's KeyExchangeInit message sent to the mailbox. */
data class KeyExchangeInitMessage(
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
        return token.contentEquals(other.token) && ekPub.contentEquals(other.ekPub) &&
            keyConfirmation.contentEquals(other.keyConfirmation) && suggestedName == other.suggestedName
    }

    override fun hashCode(): Int = token.contentHashCode()
}

/** One OPK entry in a PreKeyBundle. */
data class OPK(val id: Int, val pub: ByteArray) {
    override fun equals(other: Any?): Boolean = other is OPK && id == other.id && pub.contentEquals(other.pub)

    override fun hashCode(): Int = 31 * id + pub.contentHashCode()
}

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
)
