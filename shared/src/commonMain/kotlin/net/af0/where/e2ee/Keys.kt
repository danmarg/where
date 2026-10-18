@file:OptIn(RawKeyAccess::class)

package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.GeneralLibsodiumException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Typed byte strings for the protocol's keys, tokens and fingerprints.
 *
 * Every one of these used to be a bare ByteArray, so passing a chain key where a header key
 * belongs, or a root key where a routing token belongs, compiled fine. Each role now has its own
 * type, and the KDFs take and return those types, so that class of mistake is a compile error.
 *
 * Two families:
 *  - [SecretBytes]: keys and other secrets. They print as "redacted", compare in constant time,
 *    and have no hex form.
 *  - [PublicBytes]: public keys, fingerprints and mailbox addresses. Content equality and toHex().
 *    They still print as their length only: a routing token is a mailbox's bearer address, so it
 *    stays out of logs unless a caller asks for toHex().
 *
 * Each type checks its length on construction. The six SessionState fields that sessions stored by
 * older versions hold as empty are nullable instead (see [EmptyAsNullSerializer]). Every type
 * serializes as the same Base64 string its ByteArray did, so the stored JSON is unchanged.
 *
 * The bytes themselves are behind [RawKeyAccess]: only the primitive layer (KDFs, AEAD, codecs)
 * opts in.
 */

/**
 * Marks direct access to the bytes inside a typed key. Opt in only where bytes must cross into a
 * primitive (KDF, AEAD, X25519) or a codec; everywhere else, pass the typed value.
 */
@RequiresOptIn(
    message = "Raw key bytes. Only the primitive layer and codecs may read them; pass the typed value instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
internal annotation class RawKeyAccess

/** Base class of the typed byte strings. */
sealed class KeyBytes(
    @property:RawKeyAccess internal val bytes: ByteArray,
    expectedSize: Int,
) {
    init {
        require(bytes.size == expectedSize) { "${this::class.simpleName} must be $expectedSize bytes, got ${bytes.size}" }
    }

    internal val size: Int get() = bytes.size

    internal fun zeroize() = bytes.zeroize()

    /** A copy of the same type in a fresh buffer, so zeroizing one doesn't wipe the other. */
    internal abstract fun copyOf(): KeyBytes
}

/**
 * A secret. `==` compares in constant time (the same as [constantTimeEquals]); hashCode depends
 * only on the type, so a hash never reveals anything about the key.
 */
sealed class SecretBytes(bytes: ByteArray, expectedSize: Int) : KeyBytes(bytes, expectedSize) {
    /** Content equality in time independent of where the bytes differ. */
    internal fun constantTimeEquals(other: SecretBytes): Boolean = other::class == this::class && constantTimeEquals(bytes, other.bytes)

    override fun equals(other: Any?): Boolean = other is SecretBytes && constantTimeEquals(other)

    override fun hashCode(): Int = this::class.hashCode()

    override fun toString(): String = "${this::class.simpleName}(redacted)"
}

/** A public value: content equality and a hex form. */
sealed class PublicBytes(bytes: ByteArray, expectedSize: Int) : KeyBytes(bytes, expectedSize) {
    fun toHex(): String = bytes.toHex()

    /** A copy of the bytes, for platform code that stores or passes them on (secrets have no such accessor). */
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is PublicBytes && other::class == this::class && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * this::class.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "${this::class.simpleName}(${bytes.size} bytes)"
}

internal const val KEY_SIZE = 32

/** Keying material a routing token may be derived from: the root key, or SK at bootstrap. */
sealed class TokenSeed(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE)

/** Root key: KDF_RK salt; 32 bytes. */
@Serializable(with = RootKey.Serializer::class)
class RootKey(bytes: ByteArray) : TokenSeed(bytes) {
    override fun copyOf() = RootKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<RootKey>("RootKey", ::RootKey)
}

/** Sending or receiving chain key: KDF_CK input; 32 bytes. Discard after each step. */
@Serializable(with = ChainKey.Serializer::class)
class ChainKey(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE) {
    override fun copyOf() = ChainKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<ChainKey>("ChainKey", ::ChainKey)
}

/**
 * A key that seals more than one plaintext, so each seal draws a fresh random nonce
 * ([sealRandomNonce]) instead of taking one from a chain step.
 */
sealed class RandomNonceKey(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE)

/** Header-encryption key for the envelope (#186); 32 bytes. */
@Serializable(with = HeaderKey.Serializer::class)
class HeaderKey(bytes: ByteArray) : RandomNonceKey(bytes) {
    override fun copyOf() = HeaderKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<HeaderKey>("HeaderKey", ::HeaderKey)

    /** Stores null as the empty string sessions from before header encryption hold. */
    internal object NullableSerializer : EmptyAsNullSerializer<HeaderKey>(Serializer)
}

/** Key for the suggested name in KeyExchangeInit, derived from SK; 32 bytes. */
class NameKey(bytes: ByteArray) : RandomNonceKey(bytes) {
    override fun copyOf() = NameKey(bytes.copyOf())
}

/**
 * Per-message AEAD key from KDF_CK; 32 bytes. Single use: [sealMessage] / [openMessage] consume
 * it, zeroizing it afterwards, and a second use throws.
 */
class MessageKey(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE) {
    private var consumed = false

    override fun copyOf() = MessageKey(bytes.copyOf())

    /** Runs [block] on the key bytes, then zeroizes them. Throws if the key was already used. */
    internal fun <T> consume(block: (ByteArray) -> T): T {
        check(!consumed) { "message key already used" }
        consumed = true
        try {
            return block(bytes)
        } finally {
            zeroize()
        }
    }
}

internal const val MESSAGE_NONCE_SIZE = 12

/** Per-message AEAD nonce from KDF_CK; 12 bytes. Derived from the message key, so kept secret too. */
class MessageNonce(bytes: ByteArray) : SecretBytes(bytes, MESSAGE_NONCE_SIZE) {
    override fun copyOf() = MessageNonce(bytes.copyOf())
}

/** X25519 output; 32 bytes. Input keying material only, never a key itself. */
class SharedSecret(bytes: ByteArray) : TokenSeed(bytes) {
    override fun copyOf() = SharedSecret(bytes.copyOf())

    /** True for the all-zero output X25519 gives for a low-order public key (§4.3). */
    internal fun isAllZero(): Boolean = bytes.fold(0) { acc, b -> acc or b.toInt() } == 0
}

/** X25519 private scalar; 32 bytes. */
@Serializable(with = DhPrivateKey.Serializer::class)
class DhPrivateKey(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE) {
    override fun copyOf() = DhPrivateKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<DhPrivateKey>("DhPrivateKey", ::DhPrivateKey)
}

/** The random secret in an invite QR (§4.2), from which the discovery token is derived; 32 bytes. */
@Serializable(with = DiscoverySecret.Serializer::class)
class DiscoverySecret(bytes: ByteArray) : SecretBytes(bytes, KEY_SIZE) {
    override fun copyOf() = DiscoverySecret(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<DiscoverySecret>("DiscoverySecret", ::DiscoverySecret)

    companion object {
        /** A fresh secret from the platform CSPRNG. */
        fun random(rng: Rng = SystemRng): DiscoverySecret = DiscoverySecret(rng.bytes(KEY_SIZE))
    }
}

/** X25519 public key; 32 bytes. */
@Serializable(with = DhPublicKey.Serializer::class)
class DhPublicKey(bytes: ByteArray) : PublicBytes(bytes, SIZE) {
    override fun copyOf() = DhPublicKey(bytes.copyOf())

    internal companion object {
        const val SIZE = KEY_SIZE

        /** A peer-supplied public key, rejected as malformed unless it is exactly [SIZE] bytes. */
        fun fromWire(bytes: ByteArray): DhPublicKey {
            if (bytes.size != SIZE) throw ProtocolException("public key must be $SIZE bytes, got ${bytes.size}")
            return DhPublicKey(bytes)
        }
    }

    internal object Serializer : KeyBytesSerializer<DhPublicKey>("DhPublicKey", ::DhPublicKey)
}

/**
 * An invite's public key: EK_A.pub in the QR (§4.2); 32 bytes. A type of its own rather than a
 * [DhPublicKey], so the invite's key and the scanner's can't be swapped. The apps also use it as
 * the invite's ID.
 */
@Serializable(with = InvitePublicKey.Serializer::class)
class InvitePublicKey(bytes: ByteArray) : PublicBytes(bytes, KEY_SIZE) {
    override fun copyOf() = InvitePublicKey(bytes.copyOf())

    /** The same key as a [DhPublicKey], for X25519 and fingerprints. */
    internal fun toDhPublicKey() = DhPublicKey(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<InvitePublicKey>("InvitePublicKey", ::InvitePublicKey)
}

/** Session fingerprint, SHA-256 of a bootstrap EK.pub; 32 bytes. */
@Serializable(with = Fingerprint.Serializer::class)
class Fingerprint(bytes: ByteArray) : PublicBytes(bytes, KEY_SIZE) {
    override fun copyOf() = Fingerprint(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<Fingerprint>("Fingerprint", ::Fingerprint)

    /** Stores null as the empty string sessions from before localFp/remoteFp existed hold. */
    internal object NullableSerializer : EmptyAsNullSerializer<Fingerprint>(Serializer)
}

internal const val ROUTING_TOKEN_SIZE = 16

/**
 * Mailbox address for one direction of one epoch; 16 bytes. The two directions are distinct types
 * ([SendToken], [RecvToken]), so swapping them is a compile error.
 */
sealed class RoutingToken(bytes: ByteArray) : PublicBytes(bytes, ROUTING_TOKEN_SIZE)

/** A mailbox this party posts to. */
@Serializable(with = SendToken.Serializer::class)
class SendToken(bytes: ByteArray) : RoutingToken(bytes) {
    override fun copyOf() = SendToken(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<SendToken>("SendToken", ::SendToken)
}

/** A mailbox this party polls. */
@Serializable(with = RecvToken.Serializer::class)
class RecvToken(bytes: ByteArray) : RoutingToken(bytes) {
    override fun copyOf() = RecvToken(bytes.copyOf())

    internal object Serializer : KeyBytesSerializer<RecvToken>("RecvToken", ::RecvToken)
}

/** Rendezvous mailbox for an invite's KeyExchangeInit (§4.2); 16 bytes. */
class DiscoveryToken(bytes: ByteArray) : PublicBytes(bytes, ROUTING_TOKEN_SIZE) {
    override fun copyOf() = DiscoveryToken(bytes.copyOf())
}

/** A cached key for a message that hasn't arrived yet (§5.5), and when it was cached. */
class SkippedMessageKey internal constructor(
    internal val messageKey: MessageKey,
    internal val nonce: MessageNonce,
    internal val storedAtMillis: Long,
) {
    internal fun copyOf() = SkippedMessageKey(messageKey.copyOf(), nonce.copyOf(), storedAtMillis)

    internal fun zeroize() {
        messageKey.zeroize()
        nonce.zeroize()
    }

    override fun equals(other: Any?): Boolean =
        other is SkippedMessageKey && messageKey == other.messageKey && nonce == other.nonce && storedAtMillis == other.storedAtMillis

    override fun hashCode(): Int = storedAtMillis.hashCode()

    override fun toString(): String = "SkippedMessageKey(redacted, storedAt=$storedAtMillis)"

    internal companion object {
        /** Stored form: message key (32) || nonce (12) || stored-at epoch millis, big-endian (8). */
        const val SIZE = KEY_SIZE + MESSAGE_NONCE_SIZE + 8

        /** Takes ownership of a chain step's key and nonce (no copy), stamped with [nowMillis]. */
        fun takeFrom(
            step: ChainStep,
            nowMillis: Long,
        ) = SkippedMessageKey(step.messageKey, step.messageNonce, nowMillis)

        fun encode(key: SkippedMessageKey): ByteArray {
            val packed = ByteArray(SIZE)
            key.messageKey.bytes.copyInto(packed, 0)
            key.nonce.bytes.copyInto(packed, KEY_SIZE)
            longToBeBytes(key.storedAtMillis).copyInto(packed, KEY_SIZE + MESSAGE_NONCE_SIZE)
            return packed
        }

        /**
         * Parses the stored form, or returns null for a blob that isn't one. A key-and-nonce blob
         * without the timestamp (from before stale keys were purged) decodes as stored at time 0,
         * so the next purge drops it.
         */
        fun decode(packed: ByteArray): SkippedMessageKey? {
            if (packed.size != SIZE && packed.size != KEY_SIZE + MESSAGE_NONCE_SIZE) return null
            val storedAt = if (packed.size == SIZE) bytesToLong(packed.copyOfRange(KEY_SIZE + MESSAGE_NONCE_SIZE, SIZE)) else 0L
            return SkippedMessageKey(
                MessageKey(packed.copyOfRange(0, KEY_SIZE)),
                MessageNonce(packed.copyOfRange(KEY_SIZE, KEY_SIZE + MESSAGE_NONCE_SIZE)),
                storedAt,
            )
        }
    }
}

/** Serializes a [KeyBytes] as the Base64 string [ByteArrayBase64Serializer] writes for its bytes. */
internal abstract class KeyBytesSerializer<T : KeyBytes>(
    name: String,
    internal val wrap: (ByteArray) -> T,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("net.af0.where.e2ee.$name", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) = ByteArrayBase64Serializer.serialize(encoder, value.bytes)

    override fun deserialize(decoder: Decoder): T = wrap(ByteArrayBase64Serializer.deserialize(decoder))
}

/**
 * Serializes a nullable key as [inner] does, writing null as the empty string and reading the
 * empty string back as null. Fields that older sessions stored as an empty ByteArray use this, so
 * those blobs still load and the stored JSON is unchanged.
 */
internal abstract class EmptyAsNullSerializer<T : KeyBytes>(
    private val inner: KeyBytesSerializer<T>,
) : KSerializer<T?> {
    // Nullable, so kotlinx hands null to serialize() instead of writing JSON null itself.
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun serialize(
        encoder: Encoder,
        value: T?,
    ) = if (value == null) ByteArrayBase64Serializer.serialize(encoder, ByteArray(0)) else inner.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): T? {
        val bytes = ByteArrayBase64Serializer.deserialize(decoder)
        return if (bytes.isEmpty()) null else inner.wrap(bytes)
    }
}

/**
 * The skipped-key cache, stored as a map from cache key to the Base64 of [SkippedMessageKey.SIZE]
 * packed bytes, as before. An entry that can't be parsed (bad Base64 or a bad packed form) is
 * dropped on load, and the others kept: it could never decrypt anything, and failing the whole map
 * would fail the session it belongs to.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object SkippedKeysSerializer : KSerializer<Map<String, SkippedMessageKey>> {
    private val delegate = MapSerializer(String.serializer(), ByteArrayBase64Serializer)

    // Values are read as strings and decoded one at a time, so one bad value can't fail the map.
    private val stringMap = MapSerializer(String.serializer(), String.serializer())
    private val base64 = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: Map<String, SkippedMessageKey>,
    ) {
        val packed = value.mapValues { SkippedMessageKey.encode(it.value) }
        try {
            delegate.serialize(encoder, packed)
        } finally {
            packed.values.forEach { it.zeroize() }
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, SkippedMessageKey> {
        val result = LinkedHashMap<String, SkippedMessageKey>()
        for ((k, encoded) in decoder.decodeSerializableValue(stringMap)) {
            val v =
                try {
                    base64.decode(encoded)
                } catch (e: IllegalArgumentException) {
                    continue
                }
            SkippedMessageKey.decode(v)?.let { result[k] = it }
            v.zeroize()
        }
        return result
    }
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
    fun sendToken(ikm: TokenSeed): SendToken = SendToken(deriveRoutingToken(ikm, sender = local, recipient = remote))

    /** Token this party polls. */
    fun recvToken(ikm: TokenSeed): RecvToken = RecvToken(deriveRoutingToken(ikm, sender = remote, recipient = local))

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

/*
 * The AEAD entry points. Protocol code goes through these rather than aeadEncrypt/aeadDecrypt,
 * so the key type decides the nonce: a chain step's for a message body, a random one otherwise.
 */

internal const val RANDOM_NONCE_SIZE = 12

/** AEAD-encrypts a message body under its one-time key and nonce from KDF_CK; consumes both. */
internal fun sealMessage(
    key: MessageKey,
    nonce: MessageNonce,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray =
    try {
        key.consume { aeadEncrypt(it, nonce.bytes, plaintext, aad) }
    } finally {
        nonce.zeroize()
    }

/** Opens a message body sealed by [sealMessage]; consumes the key and nonce. Throws if authentication fails. */
internal fun openMessage(
    key: MessageKey,
    nonce: MessageNonce,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray =
    try {
        key.consume { aeadDecrypt(it, nonce.bytes, ciphertext, aad) }
    } finally {
        nonce.zeroize()
    }

/** AEAD-encrypts under [key] with a fresh random nonce; returns nonce || ciphertext || tag. */
internal fun sealRandomNonce(
    key: RandomNonceKey,
    plaintext: ByteArray,
    aad: ByteArray,
    rng: Rng = SystemRng,
): ByteArray {
    val nonce = rng.bytes(RANDOM_NONCE_SIZE)
    return nonce + aeadEncrypt(key.bytes, nonce, plaintext, aad)
}

/** Opens nonce || ciphertext || tag from [sealRandomNonce]; throws if authentication fails. */
internal fun openRandomNonce(
    key: RandomNonceKey,
    sealed: ByteArray,
    aad: ByteArray,
): ByteArray {
    require(sealed.size >= RANDOM_NONCE_SIZE) { "sealed payload shorter than its nonce" }
    val nonce = sealed.copyOfRange(0, RANDOM_NONCE_SIZE)
    return aeadDecrypt(key.bytes, nonce, sealed.copyOfRange(RANDOM_NONCE_SIZE, sealed.size), aad)
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

/**
 * X25519 between our private key and their public key. A public key libsodium refuses outright
 * (it does for some low-order points) is an [AuthenticationException], the same as the all-zero
 * result [KeyExchange.requireNonZeroSharedSecret] catches for the others. Any other failure (say,
 * libsodium not initialized) propagates as itself rather than looking like a bad peer key.
 */
internal fun dh(
    localPriv: DhPrivateKey,
    remotePub: DhPublicKey,
): SharedSecret =
    try {
        SharedSecret(x25519(localPriv.bytes, remotePub.bytes))
    } catch (e: GeneralLibsodiumException) {
        throw AuthenticationException("X25519 rejected the public key", e)
    }
