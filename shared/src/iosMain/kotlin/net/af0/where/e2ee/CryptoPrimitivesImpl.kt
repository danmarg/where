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

internal actual fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    // Platform-specific HMAC implementation using standard SHA-256.
    // RFC 2104 compliant.
    val blockSeparator = 64
    // Ensure we work on a copy to avoid mutating the caller's buffer when zeroing.
    var k =
        when {
            key.size > blockSeparator -> sha256(key)
            key.size < blockSeparator -> key.copyOf(blockSeparator)
            else -> key.copyOf()
        }

    val ipad = ByteArray(blockSeparator) { i -> (k[i].toInt() xor 0x36).toByte() }
    val opad = ByteArray(blockSeparator) { i -> (k[i].toInt() xor 0x5c).toByte() }

    val innerHash = sha256(ipad + data)
    val result = sha256(opad + innerHash)

    // Security: zero out sensitive material
    k.zeroize()
    ipad.zeroize()
    opad.zeroize()
    innerHash.zeroize()

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
