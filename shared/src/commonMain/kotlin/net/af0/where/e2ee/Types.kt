package net.af0.where.e2ee

/**
 * Raw X25519 or Ed25519 keypair. Both fields are 32-byte little-endian representations
 * as defined by RFC 7748 / RFC 8032.
 */
data class RawKeyPair(val priv: ByteArray, val pub: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is RawKeyPair && priv.contentEquals(other.priv) && pub.contentEquals(other.pub)
    override fun hashCode(): Int = 31 * priv.contentHashCode() + pub.contentHashCode()
}

/**
 * The two long-term identity keypairs held by each device:
 *   ik    – X25519 keypair used for Diffie-Hellman key agreement only.
 *   sigIk – Ed25519 keypair used for signatures only.
 *
 * These MUST be generated independently; do NOT derive one from the other.
 */
data class IdentityKeys(val ik: RawKeyPair, val sigIk: RawKeyPair)

/**
 * Per-friendship ratchet state maintained by the sender (Alice).
 * All byte arrays are copies; callers must zero them after use.
 *
 * Fields:
 *   rootKey       – 32-byte root key, updated on every DH ratchet step.
 *   sendChainKey  – 32-byte symmetric chain key; advanced on every location send.
 *   routingToken  – 16-byte opaque token used as the mailbox address.
 *   sendSeq       – Monotonically increasing counter; MUST NOT wrap (session must be
 *                   invalidated and re-keyed if it reaches Long.MAX_VALUE).
 *   recvSeq       – Highest seq received from the peer (for replay rejection).
 *   epoch         – DH ratchet epoch counter (uint32 semantics, stored as Int).
 *   myEkPriv      – 32-byte current ephemeral X25519 private key (deleted after DH ratchet step).
 *   myEkPub       – 32-byte current ephemeral X25519 public key.
 *   theirEkPub    – 32-byte peer's last known ephemeral X25519 public key.
 */
data class SessionState(
    val rootKey: ByteArray,
    val sendChainKey: ByteArray,
    val routingToken: ByteArray,
    val sendSeq: Long,
    val recvSeq: Long,
    val epoch: Int,
    val myEkPriv: ByteArray,
    val myEkPub: ByteArray,
    val theirEkPub: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is SessionState) return false
        return rootKey.contentEquals(other.rootKey) &&
            sendChainKey.contentEquals(other.sendChainKey) &&
            routingToken.contentEquals(other.routingToken) &&
            sendSeq == other.sendSeq &&
            recvSeq == other.recvSeq &&
            epoch == other.epoch &&
            myEkPriv.contentEquals(other.myEkPriv) &&
            myEkPub.contentEquals(other.myEkPub) &&
            theirEkPub.contentEquals(other.theirEkPub)
    }

    override fun hashCode(): Int {
        var h = rootKey.contentHashCode()
        h = 31 * h + sendChainKey.contentHashCode()
        h = 31 * h + routingToken.contentHashCode()
        h = 31 * h + sendSeq.hashCode()
        h = 31 * h + recvSeq.hashCode()
        h = 31 * h + epoch
        h = 31 * h + myEkPriv.contentHashCode()
        h = 31 * h + myEkPub.contentHashCode()
        h = 31 * h + theirEkPub.contentHashCode()
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
 * The sig field is mandatory: Ed25519(SigIK.priv, ikPub || ekPub || sigPub).
 */
data class QrPayload(
    val ikPub: ByteArray,        // Alice's X25519 identity public key (32 bytes)
    val ekPub: ByteArray,        // Alice's ephemeral X25519 public key (32 bytes)
    val sigPub: ByteArray,       // Alice's Ed25519 signing public key (32 bytes)
    val suggestedName: String,
    val fingerprint: String,     // hex(SHA-256(ikPub)[0:10])
    val sig: ByteArray,          // Ed25519 signature (64 bytes)
) {
    override fun equals(other: Any?): Boolean {
        if (other !is QrPayload) return false
        return ikPub.contentEquals(other.ikPub) && ekPub.contentEquals(other.ekPub) &&
            sigPub.contentEquals(other.sigPub) && suggestedName == other.suggestedName &&
            fingerprint == other.fingerprint && sig.contentEquals(other.sig)
    }
    override fun hashCode(): Int =
        31 * (31 * (31 * ikPub.contentHashCode() + ekPub.contentHashCode()) +
            sigPub.contentHashCode()) + sig.contentHashCode()
}

/** Bob's KeyExchangeInit message sent to the mailbox. */
data class KeyExchangeInitMessage(
    val token: ByteArray,    // T_AB_0 (16 bytes) — mailbox address
    val ikPub: ByteArray,    // Bob's X25519 identity public key
    val ekPub: ByteArray,    // Bob's ephemeral X25519 public key
    val sigPub: ByteArray,   // Bob's Ed25519 signing public key
    val sig: ByteArray,      // Ed25519(SigIK.priv, ikPub || ekPub || sigPub)
) {
    override fun equals(other: Any?): Boolean {
        if (other !is KeyExchangeInitMessage) return false
        return token.contentEquals(other.token) && ikPub.contentEquals(other.ikPub) &&
            ekPub.contentEquals(other.ekPub) && sigPub.contentEquals(other.sigPub) &&
            sig.contentEquals(other.sig)
    }
    override fun hashCode(): Int = token.contentHashCode()
}

/** One OPK entry in a PreKeyBundle. */
data class OPK(val id: Int, val pub: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is OPK && id == other.id && pub.contentEquals(other.pub)
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
