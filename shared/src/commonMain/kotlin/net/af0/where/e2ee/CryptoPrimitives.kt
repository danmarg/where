package net.af0.where.e2ee

/*
 * Platform-specific cryptographic primitives. Each target (JVM/Android, iOS) provides
 * actual implementations. All byte arrays are raw (no encoding). Callers are responsible
 * for zeroing sensitive material after use.
 */

/** SHA-256 hash. Returns 32 bytes. */
internal expect fun sha256(data: ByteArray): ByteArray

/** SHA-512 hash. Returns 64 bytes. */
internal expect fun sha512(data: ByteArray): ByteArray

/** HMAC-SHA-256 (RFC 2104). Returns 32 bytes. */
internal expect fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray

/** CSPRNG: generate [size] random bytes. */
internal expect fun randomBytes(size: Int): ByteArray

/** Generate a fresh X25519 keypair from the platform CSPRNG. */
expect fun generateX25519KeyPair(): RawKeyPair

/**
 * X25519 scalar multiplication.
 * @param myPriv  32-byte little-endian private scalar
 * @param theirPub 32-byte little-endian public key
 * @return 32-byte shared secret
 */
internal expect fun x25519(
    myPriv: ByteArray,
    theirPub: ByteArray,
): ByteArray

/**
 * AEAD encrypt (ChaCha20-Poly1305 IETF).
 * @param key   32-byte key
 * @param nonce 12-byte nonce
 * @param plaintext arbitrary plaintext
 * @param aad   additional authenticated data
 * @return ciphertext || 16-byte authentication tag (concatenated)
 */
internal expect fun aeadEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray

/**
 * AEAD decrypt (ChaCha20-Poly1305 IETF).
 * @param key   32-byte key
 * @param nonce 12-byte nonce
 * @param ciphertext ciphertext || 16-byte authentication tag
 * @param aad   additional authenticated data (must match what was used during encrypt)
 * @return plaintext, or throws if authentication fails
 * @throws IllegalArgumentException on authentication failure
 */
internal expect fun aeadDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray

/**
 * Securely zeros out the byte array to prevent sensitive material from lingering in memory.
 * Implementation MUST use a method that prevents compiler/JIT elision (e.g. memset_s or fill(0)).
 */
internal expect fun ByteArray.zeroize()
