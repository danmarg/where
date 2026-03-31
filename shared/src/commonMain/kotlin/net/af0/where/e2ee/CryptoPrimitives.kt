package net.af0.where.e2ee

/*
 * Platform-specific cryptographic primitives. Each target (JVM/Android, iOS) provides
 * actual implementations. All byte arrays are raw (no encoding). Callers are responsible
 * for zeroing sensitive material after use.
 */

/** SHA-256 hash. Returns 32 bytes. */
internal expect fun sha256(data: ByteArray): ByteArray

/** HMAC-SHA-256. Returns 32 bytes. */
internal expect fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray

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

/** Generate a fresh Ed25519 keypair from the platform CSPRNG. */
expect fun generateEd25519KeyPair(): RawKeyPair

/**
 * Ed25519 sign.
 * @param priv 32-byte Ed25519 private key seed
 * @param message arbitrary message bytes
 * @return 64-byte signature
 */
internal expect fun ed25519Sign(
    priv: ByteArray,
    message: ByteArray,
): ByteArray

/**
 * Ed25519 verify.
 * @param pub 32-byte Ed25519 public key
 * @param message message that was signed
 * @param sig 64-byte signature
 * @return true iff signature is valid
 */
internal expect fun ed25519Verify(
    pub: ByteArray,
    message: ByteArray,
    sig: ByteArray,
): Boolean

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
