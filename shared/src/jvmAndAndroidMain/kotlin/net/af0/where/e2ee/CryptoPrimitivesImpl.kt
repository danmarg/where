@file:OptIn(ExperimentalUnsignedTypes::class)

package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
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

internal actual fun ByteArray.zeroize() {
    this.fill(0)
}
