@file:OptIn(ExperimentalUnsignedTypes::class)

package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.posix.memset_s

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------

internal actual fun sha256(data: ByteArray): ByteArray {
    return Hash.sha256(data.toUByteArray()).toByteArray()
}

internal actual fun sha512(data: ByteArray): ByteArray {
    return Hash.sha512(data.toUByteArray()).toByteArray()
}

// ---------------------------------------------------------------------------
// HMAC-SHA-256
// ---------------------------------------------------------------------------

@OptIn(ExperimentalForeignApi::class)
internal actual fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val result = ByteArray(CC_SHA256_DIGEST_LENGTH)
    // addressOf(0) throws on an empty array, but an empty key or message is valid HMAC input
    // (Wycheproof has empty messages): pin a 1-byte stand-in and still pass the real length.
    val keyBuf = if (key.isEmpty()) ByteArray(1) else key
    val dataBuf = if (data.isEmpty()) ByteArray(1) else data
    keyBuf.usePinned { keyPinned ->
        dataBuf.usePinned { dataPinned ->
            result.usePinned { resultPinned ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    keyPinned.addressOf(0),
                    key.size.toULong(),
                    dataPinned.addressOf(0),
                    data.size.toULong(),
                    resultPinned.addressOf(0),
                )
            }
        }
    }
    return result
}

// ---------------------------------------------------------------------------
// Random
// ---------------------------------------------------------------------------

internal actual fun randomBytes(size: Int): ByteArray {
    return LibsodiumRandom.buf(size).toByteArray()
}

// ---------------------------------------------------------------------------
// X25519
// ---------------------------------------------------------------------------

actual fun generateX25519KeyPair(): RawKeyPair {
    val keyPair = Box.keypair()
    return RawKeyPair(
        keyPair.secretKey.toByteArray(),
        keyPair.publicKey.toByteArray(),
    )
}

internal actual fun x25519(
    myPriv: ByteArray,
    theirPub: ByteArray,
): ByteArray {
    return ScalarMultiplication.scalarMultiplication(myPriv.toUByteArray(), theirPub.toUByteArray())
        .toByteArray()
}

// ---------------------------------------------------------------------------
// AEAD (ChaCha20-Poly1305 from libsodium)
// ---------------------------------------------------------------------------

internal actual fun aeadEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray {
    requireAeadKeyAndNonce(key, nonce)
    return AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
        plaintext.toUByteArray(),
        aad.toUByteArray(),
        nonce.toUByteArray(),
        key.toUByteArray(),
    ).toByteArray()
}

internal actual fun aeadDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray {
    requireAeadKeyAndNonce(key, nonce)
    return try {
        AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
            ciphertext.toUByteArray(),
            aad.toUByteArray(),
            nonce.toUByteArray(),
            key.toUByteArray(),
        ).toByteArray()
    } catch (e: Exception) {
        throw IllegalArgumentException("AEAD authentication failed", e)
    }
}

// ---------------------------------------------------------------------------
// Memory Hygiene
// ---------------------------------------------------------------------------

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
internal actual fun ByteArray.zeroize() {
    if (this.isEmpty()) return
    this.usePinned { pinned ->
        memset_s(pinned.addressOf(0), this.size.toULong(), 0, this.size.toULong())
    }
}
