@file:OptIn(ExperimentalUnsignedTypes::class)

package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.auth.Auth
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.signature.Signature
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------

internal actual fun sha256(data: ByteArray): ByteArray {
    return Hash.sha256(data.toUByteArray()).toByteArray()
}

// ---------------------------------------------------------------------------
// HMAC-SHA-256
// ---------------------------------------------------------------------------

internal actual fun hmacSha256(
    key: ByteArray,
    data: ByteArray,
): ByteArray {
    val blockSeparator = 64
    var k = if (key.size > blockSeparator) {
        sha256(key)
    } else {
        key
    }

    if (k.size < blockSeparator) {
        k = k.copyOf(blockSeparator)
    }

    val ipad = ByteArray(blockSeparator) { i -> (k[i].toInt() xor 0x36).toByte() }
    val opad = ByteArray(blockSeparator) { i -> (k[i].toInt() xor 0x5c).toByte() }

    val innerHash = sha256(ipad + data)
    return sha256(opad + innerHash)
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
    return Box.beforeNM(theirPub.toUByteArray(), myPriv.toUByteArray()).toByteArray()
}

// ---------------------------------------------------------------------------
// Ed25519
// ---------------------------------------------------------------------------

actual fun generateEd25519KeyPair(): RawKeyPair {
    val keyPair = Signature.keypair()
    return RawKeyPair(
        keyPair.secretKey.toByteArray(),
        keyPair.publicKey.toByteArray(),
    )
}

internal actual fun ed25519Sign(
    priv: ByteArray,
    message: ByteArray,
): ByteArray {
    return Signature.detached(message.toUByteArray(), priv.toUByteArray()).toByteArray()
}

internal actual fun ed25519Verify(
    pub: ByteArray,
    message: ByteArray,
    sig: ByteArray,
): Boolean {
    return try {
        Signature.verifyDetached(sig.toUByteArray(), message.toUByteArray(), pub.toUByteArray())
        true
    } catch (_: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// AES-256-GCM (using ChaCha20-Poly1305 from libsodium)
// ---------------------------------------------------------------------------

internal actual fun aesgcmEncrypt(
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

internal actual fun aesgcmDecrypt(
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
