package net.af0.where.e2ee

/**
 * A seeded [Rng] for tests and the golden vectors: every output is a fixed function of [seed] and
 * how many draws came before, the same on every platform (HKDF-SHA-256 and X25519 only).
 */
class DeterministicRng(seed: String) : Rng {
    private val key = sha256(seed.encodeToByteArray())
    private var counter = 0L

    override fun bytes(size: Int): ByteArray =
        hkdfSha256(ikm = key, salt = null, info = INFO + longToBeBytes(counter++), length = size)

    /** The private key is 32 drawn bytes; X25519 clamps it, as libsodium's own key generation does. */
    override fun x25519KeyPair(): RawKeyPair {
        val priv = bytes(32)
        return RawKeyPair(priv, x25519(priv, BASE_POINT))
    }

    private companion object {
        val INFO = "where-test-rng".encodeToByteArray()
        val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    }
}
