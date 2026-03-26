package net.af0.where.e2ee

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ---------------------------------------------------------------------------
// SHA-256
// ---------------------------------------------------------------------------

internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

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
// X25519 (via BouncyCastle — available on all Android API levels and JVM)
// ---------------------------------------------------------------------------

actual fun generateX25519KeyPair(): RawKeyPair {
    val gen = X25519KeyPairGenerator()
    gen.init(X25519KeyGenerationParameters(SecureRandom()))
    val kp = gen.generateKeyPair()
    val priv = ByteArray(32)
    val pub = ByteArray(32)
    (kp.private as X25519PrivateKeyParameters).encode(priv, 0)
    (kp.public as X25519PublicKeyParameters).encode(pub, 0)
    return RawKeyPair(priv, pub)
}

internal actual fun x25519(
    myPriv: ByteArray,
    theirPub: ByteArray,
): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(X25519PrivateKeyParameters(myPriv, 0))
    val result = ByteArray(32)
    agreement.calculateAgreement(X25519PublicKeyParameters(theirPub, 0), result, 0)
    return result
}

// ---------------------------------------------------------------------------
// Ed25519 (via BouncyCastle)
// ---------------------------------------------------------------------------

actual fun generateEd25519KeyPair(): RawKeyPair {
    val gen = Ed25519KeyPairGenerator()
    gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
    val kp = gen.generateKeyPair()
    val priv = ByteArray(32)
    val pub = ByteArray(32)
    (kp.private as Ed25519PrivateKeyParameters).encode(priv, 0)
    (kp.public as Ed25519PublicKeyParameters).encode(pub, 0)
    return RawKeyPair(priv, pub)
}

internal actual fun ed25519Sign(
    priv: ByteArray,
    message: ByteArray,
): ByteArray {
    val signer = Ed25519Signer()
    signer.init(true, Ed25519PrivateKeyParameters(priv, 0))
    signer.update(message, 0, message.size)
    return signer.generateSignature()
}

internal actual fun ed25519Verify(
    pub: ByteArray,
    message: ByteArray,
    sig: ByteArray,
): Boolean {
    val verifier = Ed25519Signer()
    verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
    verifier.update(message, 0, message.size)
    return try {
        verifier.verifySignature(sig)
    } catch (_: Exception) {
        false
    }
}

// ---------------------------------------------------------------------------
// AES-256-GCM (JCA — available on all supported Android API levels and JVM)
// ---------------------------------------------------------------------------

internal actual fun aesgcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    aad: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}

internal actual fun aesgcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    aad: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad)
    return try {
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        throw IllegalArgumentException("AES-GCM authentication failed", e)
    }
}
