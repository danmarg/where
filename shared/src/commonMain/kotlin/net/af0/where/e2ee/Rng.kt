package net.af0.where.e2ee

/**
 * Where the protocol's randomness comes from: fresh X25519 key pairs (invites, the handshake, DH
 * ratchet steps) and random bytes (header and suggested-name nonces, discovery secrets).
 *
 * [Session], [KeyExchange] and [ProtocolCore] take one, together with the current time, instead of
 * reaching for the platform CSPRNG or the clock themselves, so they are deterministic functions
 * of their inputs. The app passes [SystemRng]; tests pass a seeded one to replay a transcript
 * byte for byte (see the golden vectors in commonTest/resources/vectors).
 */
interface Rng {
    /** [size] random bytes. */
    fun bytes(size: Int): ByteArray

    /** A fresh X25519 key pair. */
    fun x25519KeyPair(): RawKeyPair
}

/** The platform CSPRNG (libsodium). Everything outside tests uses this. */
object SystemRng : Rng {
    override fun bytes(size: Int): ByteArray = randomBytes(size)

    override fun x25519KeyPair(): RawKeyPair = generateX25519KeyPair()
}
