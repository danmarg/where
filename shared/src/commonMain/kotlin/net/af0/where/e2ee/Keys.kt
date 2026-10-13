package net.af0.where.e2ee

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/*
 * Typed byte strings for the protocol's keys, tokens and fingerprints.
 *
 * Every one of these used to be a bare ByteArray, so passing a chain key where a header key
 * belongs, or a root key where a routing token belongs, compiled fine. Each role now has its own
 * type, and the KDFs take and return those types, so that class of mistake is a compile error.
 *
 * Equality is by content within the same type. All of them print as their length only: a routing
 * token is a mailbox's bearer address, so even "public" values stay out of logs unless a caller
 * asks for toHex().
 * Constructors don't check lengths: persisted sessions from older versions hold empty values in
 * some fields (see SessionState), and those must keep loading. Every type serializes as the same
 * Base64 string its ByteArray did, so the stored JSON is unchanged.
 */

/** Base class of the typed byte strings. `==` is not constant-time; see [constantTimeEquals]. */
sealed class KeyBytes(internal val bytes: ByteArray) {
    internal val size: Int get() = bytes.size

    internal fun isEmpty(): Boolean = bytes.isEmpty()

    internal fun zeroize() = bytes.zeroize()

    /** A copy of the same type in a fresh buffer, so zeroizing one doesn't wipe the other. */
    internal abstract fun copyOf(): KeyBytes

    fun toHex(): String = bytes.toHex()

    /** Content equality in time independent of where the bytes differ; use it for secrets. */
    internal fun constantTimeEquals(other: KeyBytes): Boolean = other::class == this::class && constantTimeEquals(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is KeyBytes && other::class == this::class && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * this::class.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "${this::class.simpleName}(${bytes.size} bytes)"
}

/** Keying material a routing token may be derived from: the root key, or SK at bootstrap. */
sealed class TokenSeed(bytes: ByteArray) : KeyBytes(bytes)

/** Root key: KDF_RK salt; 32 bytes. */
@Serializable(with = RootKey.Serializer::class)
class RootKey(bytes: ByteArray) : TokenSeed(bytes) {
    override fun copyOf() = RootKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<RootKey>("RootKey", ::RootKey)
}

/** Sending or receiving chain key: KDF_CK input; 32 bytes. Discard after each step. */
@Serializable(with = ChainKey.Serializer::class)
class ChainKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = ChainKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<ChainKey>("ChainKey", ::ChainKey)
}

/** Header-encryption key for the envelope (#186); 32 bytes. */
@Serializable(with = HeaderKey.Serializer::class)
class HeaderKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = HeaderKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<HeaderKey>("HeaderKey", ::HeaderKey)
}

/** Per-message AEAD key from KDF_CK; 32 bytes. Use once, then zeroize. */
class MessageKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = MessageKey(bytes.copyOf())
}

/** Per-message AEAD nonce from KDF_CK; 12 bytes. */
class MessageNonce(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = MessageNonce(bytes.copyOf())
}

/** X25519 output; 32 bytes. Input keying material only, never a key itself. */
class SharedSecret(bytes: ByteArray) : TokenSeed(bytes) {
    override fun copyOf() = SharedSecret(bytes.copyOf())
}

/** X25519 private scalar; 32 bytes. */
@Serializable(with = DhPrivateKey.Serializer::class)
class DhPrivateKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = DhPrivateKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<DhPrivateKey>("DhPrivateKey", ::DhPrivateKey)
}

/** X25519 public key; 32 bytes. */
@Serializable(with = DhPublicKey.Serializer::class)
class DhPublicKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = DhPublicKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<DhPublicKey>("DhPublicKey", ::DhPublicKey)
}

/** Session fingerprint, SHA-256 of a bootstrap EK.pub; 32 bytes. */
@Serializable(with = Fingerprint.Serializer::class)
class Fingerprint(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = Fingerprint(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<Fingerprint>("Fingerprint", ::Fingerprint)
}

/** Mailbox address for one direction of one epoch; 16 bytes. */
@Serializable(with = RoutingToken.Serializer::class)
class RoutingToken(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = RoutingToken(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<RoutingToken>("RoutingToken", ::RoutingToken)
}

/**
 * A cached key for a message that hasn't arrived yet (§5.5), stored as
 * [message key (32) || nonce (12) || stored-at epoch millis, big-endian (8)].
 */
@Serializable(with = SkippedMessageKey.Serializer::class)
class SkippedMessageKey(bytes: ByteArray) : KeyBytes(bytes) {
    override fun copyOf() = SkippedMessageKey(bytes.copyOf())

    /** Shorter blobs can't be split into key, nonce and timestamp. */
    internal val isWellFormed: Boolean get() = bytes.size >= SIZE

    internal fun messageKey() = MessageKey(bytes.copyOfRange(0, 32))

    internal fun nonce() = MessageNonce(bytes.copyOfRange(32, 44))

    internal fun storedAtMillis(): Long = bytesToLong(bytes.copyOfRange(44, SIZE))

    internal object Serializer : KeyBytesSerializer<SkippedMessageKey>("SkippedMessageKey", ::SkippedMessageKey)

    internal companion object {
        const val SIZE = 52

        /** Packs a chain step's key and nonce with [nowMillis], then zeroizes the step's copies. */
        fun takeFrom(
            step: ChainStep,
            nowMillis: Long,
        ): SkippedMessageKey {
            val packed = ByteArray(SIZE)
            step.messageKey.bytes.copyInto(packed, 0)
            step.messageNonce.bytes.copyInto(packed, 32)
            longToBeBytes(nowMillis).copyInto(packed, 44)
            step.messageKey.zeroize()
            step.messageNonce.zeroize()
            return SkippedMessageKey(packed)
        }
    }
}

/** Serializes a [KeyBytes] as the Base64 string [ByteArrayBase64Serializer] writes for its bytes. */
internal abstract class KeyBytesSerializer<T : KeyBytes>(
    name: String,
    private val wrap: (ByteArray) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("net.af0.where.e2ee.$name", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = ByteArrayBase64Serializer.serialize(encoder, value.bytes)

    override fun deserialize(decoder: Decoder): T = wrap(ByteArrayBase64Serializer.deserialize(decoder))
}

/**
 * The session's two fingerprints from this party's side. Routing tokens and message AAD bind
 * (sender, recipient) in that order; building both directions here, from named local and remote
 * fingerprints, keeps call sites from passing the pair the wrong way round.
 */
internal class Parties(
    val local: Fingerprint,
    val remote: Fingerprint,
) {
    /** Token this party sends to. */
    fun sendToken(ikm: TokenSeed): RoutingToken = deriveRoutingToken(ikm, sender = local, recipient = remote)

    /** Token this party polls. */
    fun recvToken(ikm: TokenSeed): RoutingToken = deriveRoutingToken(ikm, sender = remote, recipient = local)

    /** AAD of a message this party sends. */
    fun sendAad(
        seq: Long,
        dhPub: DhPublicKey,
        ackRemoteDhPub: DhPublicKey,
    ): ByteArray = buildMessageAad(sender = local, recipient = remote, seq, dhPub, ackRemoteDhPub)

    /** AAD of a message this party receives. */
    fun recvAad(
        seq: Long,
        dhPub: DhPublicKey,
        ackRemoteDhPub: DhPublicKey,
    ): ByteArray = buildMessageAad(sender = remote, recipient = local, seq, dhPub, ackRemoteDhPub)

    companion object {
        fun of(
            isAlice: Boolean,
            aliceFp: Fingerprint,
            bobFp: Fingerprint,
        ) = if (isAlice) Parties(aliceFp, bobFp) else Parties(bobFp, aliceFp)
    }
}

/** Compares [a] and [b] without exiting early at the first difference. */
internal fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean {
    var diff = a.size xor b.size
    for (i in 0 until minOf(a.size, b.size)) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** X25519 between our private key and their public key. */
internal fun dh(
    localPriv: DhPrivateKey,
    remotePub: DhPublicKey,
): SharedSecret = SharedSecret(x25519(localPriv.bytes, remotePub.bytes))
