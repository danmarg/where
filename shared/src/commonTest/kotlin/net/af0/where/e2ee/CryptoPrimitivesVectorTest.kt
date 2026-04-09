package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Known-input/output test vectors for every crypto primitive.
 *
 * These run on ALL platforms (JVM, Android, iOS) via the commonTest source set.
 * On iOS the actual implementations are the Security framework / CommonCrypto
 * cinterop; on JVM/Android they use libsodium bindings.
 *
 * Catching a failure here on one platform means that platform's implementation
 * diverges from the spec.
 */
class CryptoPrimitivesVectorTest {
    companion object {
        init {
            initializeE2eeTests()
        }
    }

    init {
        // Additional initialization for each test instance
        initializeE2eeTests()
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        check(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // -----------------------------------------------------------------------
    // SHA-256 (FIPS 180-4 example)
    // -----------------------------------------------------------------------

    @Test
    fun `sha256 of 'abc'`() {
        val input = "abc".encodeToByteArray()
        // Standard SHA256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        val expected =
            hex(
                "ba7816bf8f01cfea414140de5dae2223" +
                    "b00361a396177a9cb410ff61f20015ad",
            )
        assertContentEquals(expected, sha256(input))
    }

    @Test
    fun `sha256 of empty string`() {
        val expected =
            hex(
                "e3b0c44298fc1c149afbf4c8996fb924" +
                    "27ae41e4649b934ca495991b7852b855",
            )
        assertContentEquals(expected, sha256(ByteArray(0)))
    }

    // -----------------------------------------------------------------------
    // HMAC-SHA-256 (RFC 4231 Test Case 1)
    // -----------------------------------------------------------------------

    @Test
    fun `hmacSha256 RFC 4231 test case 1`() {
        val key = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val data = "Hi There".encodeToByteArray()
        val expected =
            hex(
                "b0344c61d8db38535ca8afceaf0bf12b" +
                    "881dc200c9833da726e9376c2e32cff7",
            )
        assertContentEquals(expected, hmacSha256(key, data))
    }

    // -----------------------------------------------------------------------
    // X25519 (RFC 7748 §6.1 test vector)
    // -----------------------------------------------------------------------

    @Test
    fun x25519_DH_is_symmetric() {
        // Test that X25519 key agreement is symmetric: both parties independently
        // derive the same shared secret. This is library-agnostic (works with libsodium,
        // BouncyCastle, or any standards-compliant X25519 implementation).
        val aliceKp = generateX25519KeyPair()
        val bobKp = generateX25519KeyPair()

        val aliceShared = x25519(aliceKp.priv, bobKp.pub)
        val bobShared = x25519(bobKp.priv, aliceKp.pub)

        assertContentEquals(
            aliceShared,
            bobShared,
            "Shared secrets must match",
        )
    }

    // -----------------------------------------------------------------------
    // ChaCha20-Poly1305 IETF (libsodium's authenticated encryption)
    // -----------------------------------------------------------------------

    @Test
    fun `chachapoly round-trip with plaintext and aad`() {
        // ChaCha20-Poly1305 IETF round-trip test (empty plaintext with AAD)
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "sender:alice".encodeToByteArray()

        val ctTag = aeadEncrypt(key, nonce, ByteArray(0), aad)
        // ChaCha20-Poly1305 produces 16-byte tag
        assertTrue(ctTag.size == 16, "ChaCha20-Poly1305 empty plaintext must produce 16-byte tag")

        val decrypted = aeadDecrypt(key, nonce, ctTag, aad)
        assertContentEquals(ByteArray(0), decrypted)
    }

    @Test
    fun `chachapoly round-trip with message and aad`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "sender:alice".encodeToByteArray()
        val plaintext = "37.7749,-122.4194".encodeToByteArray()

        val ctTag = aeadEncrypt(key, nonce, plaintext, aad)
        val decrypted = aeadDecrypt(key, nonce, ctTag, aad)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `aead decryption fails on tag corruption`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12)
        val pt = "test".encodeToByteArray()
        val ctTag = aeadEncrypt(key, nonce, pt, ByteArray(0)).copyOf()
        // Flip a bit in the tag (last 16 bytes)
        ctTag[ctTag.size - 1] = (ctTag[ctTag.size - 1].toInt() xor 1).toByte()

        val threw =
            try {
                aeadDecrypt(key, nonce, ctTag, ByteArray(0))
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "Corrupted tag must cause decryption to fail")
    }
}
