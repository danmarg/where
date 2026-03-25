package net.af0.where.e2ee

/**
 * iOS crypto primitive stubs. These are placeholders until CryptoKit interop is wired up.
 *
 * Implementation plan:
 *   sha256, hmacSha256, aesgcmEncrypt, aesgcmDecrypt — CommonCrypto (C API, accessible from
 *   Kotlin/Native via platform.CoreFoundation / platform.Security / kotlinx-cinterop).
 *
 *   generateX25519KeyPair, x25519, generateEd25519KeyPair, ed25519Sign, ed25519Verify —
 *   CryptoKit is Swift-only. Two options:
 *     (a) Expose a Swift helper object from the iOS app layer that the KMP framework calls
 *         via a platform.darwin expect/actual bridge.
 *     (b) Integrate libsodium as a C interop dependency (provides all primitives via cinterop).
 *   Option (b) is recommended for full KMP isolation; option (a) is simpler for the near term.
 *
 *   Until one of these is implemented, the iOS app must either use the Swift-side
 *   LocationSyncService (which already handles crypto natively) and only consume the
 *   KMP data types and protocol constants, OR inject platform implementations via
 *   a CryptoProvider interface.
 */

internal actual fun sha256(data: ByteArray): ByteArray = TODO("iOS: implement via CommonCrypto CCDigest")

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = TODO("iOS: implement via CommonCrypto CCHmac")

actual fun generateX25519KeyPair(): RawKeyPair = TODO("iOS: implement via CryptoKit or libsodium")

internal actual fun x25519(myPriv: ByteArray, theirPub: ByteArray): ByteArray = TODO("iOS: implement via CryptoKit or libsodium")

actual fun generateEd25519KeyPair(): RawKeyPair = TODO("iOS: implement via CryptoKit or libsodium")

internal actual fun ed25519Sign(priv: ByteArray, message: ByteArray): ByteArray = TODO("iOS: implement via CryptoKit or libsodium")

internal actual fun ed25519Verify(pub: ByteArray, message: ByteArray, sig: ByteArray): Boolean = TODO("iOS: implement via CryptoKit or libsodium")

internal actual fun aesgcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray = TODO("iOS: implement via CommonCrypto or CryptoKit")

internal actual fun aesgcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray = TODO("iOS: implement via CommonCrypto or CryptoKit")
