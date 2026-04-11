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
 * Per-friendship ratchet state maintained by both sides.
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
 *   myEkPriv      – 32-byte current ephemeral X25519 private key (zeroed after DH step; §5.5).
 *   myEkPub       – 32-byte current ephemeral X25519 public key.
 *   theirEkPub    – 32-byte peer's last known ephemeral X25519 public key.
 *   aliceFp       – SHA-256(EK_A.pub) — Alice's session fingerprint.
 *   bobFp         – SHA-256(EK_B.pub) — Bob's session fingerprint.
 *   aliceEkPub    – EK_A.pub — Alice's bootstrap ephemeral public key (stable for session lifetime).
 *   bobEkPub      – EK_B.pub — Bob's bootstrap ephemeral public key (stable for session lifetime).
 *   kBundle       – HKDF(SK, info="Where-v1-BundleAuth") — bundle authentication key.
 *
 * There is no epoch counter. DH ratchet advancement is ack-triggered (§8.4): Alice stores
 * a PendingRotation alongside the session and commits it when she receives a RatchetAck.
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
    @kotlinx.serialization.Transient val myEkPriv: ByteArray = ByteArray(32),
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

/**
 * A pending DH ratchet rotation initiated by Alice.
 *
 * Alice computes this when she first has an OPK available and no rotation is in flight.
 * She stores it alongside her session and includes [epochRotationCt] in every outgoing
 * POST until she receives a RatchetAck covering the rotation.
 *
 * The rotation uses a two-step DH for mutual PFS (§8.4):
 *   Step 1: KDF_RK(rootKey, DH(aliceNewEk, bobOpk))  → (rootKey1, chainKey_AB)
 *   Step 2: KDF_RK(rootKey1, DH(bobNewEk, aliceNewEk)) → (rootKey2, chainKey_BA)
 * Bob includes bobNewEkPub in his RatchetAck; Alice performs step 2 when committing.
 * [aliceNewEkPriv] is kept alive (serialized) until the RatchetAck is processed.
 *
 * SECURITY NOTE (§5.5): [aliceNewEkPriv] MUST be stored with the same protections as
 * the session root key (e.g., kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly on iOS,
 * StrongBox-backed EncryptedSharedPreferences on Android). Backup exposure of this
 * key compromises the post-compromise security of the pending DH rotation.
 *
 * @property newSession       Intermediate session state (after step 1 only); committed *                            in [Session.aliceProcessRatchetAck] after step 2.
 * @property epochRotationCt  Pre-built AEAD blob to include with each outgoing send.
 * @property opkId            OPK ID consumed for this rotation (for diagnostics).
 * @property aliceNewEkPriv   Alice's ephemeral private key; used for step 2 DH on commit.
 */
@Serializable
data class PendingRotation(
    val newSession: SessionState,
    @Serializable(with = ByteArrayBase64Serializer::class) val epochRotationCt: ByteArray,
    val opkId: Int,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceNewEkPriv: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingRotation &&
            newSession == other.newSession &&
            epochRotationCt.contentEquals(other.epochRotationCt) &&
            opkId == other.opkId &&
            aliceNewEkPriv.contentEquals(other.aliceNewEkPriv)

    override fun hashCode(): Int =
        31 * (31 * newSession.hashCode() + epochRotationCt.contentHashCode()) +
            aliceNewEkPriv.contentHashCode()
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Bob's cached RatchetAck for lost-ack recovery.
 *
 * After Bob processes an EpochRotation he immediately switches his recvToken to T_AB_new,
 * so he will never see Alice's retried EpochRotations (which arrive on T_AB_old).
 * To recover, Bob re-posts the same ack on every poll cycle until he sees Alice's first
 * message on T_AB_new (proving she received the ack and committed).
 *
 * @property ackCt     The AEAD blob to re-post as [RatchetAckPayload].
 * @property sendToken The pre-rotation sendToken (T_BA_old, hex) where the ack must be posted.
 */
@Serializable
data class PendingAck(
    @Serializable(with = ByteArrayBase64Serializer::class) val ackCt: ByteArray,
    val sendToken: String,
    val expectedRecvToken: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PendingAck && ackCt.contentEquals(other.ackCt) &&
            sendToken == other.sendToken && expectedRecvToken == other.expectedRecvToken

    override fun hashCode(): Int {
        var h = ackCt.contentHashCode()
        h = 31 * h + sendToken.hashCode()
        h = 31 * h + expectedRecvToken.hashCode()
        return h
    }
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
