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
    // X25519 (RFC 7748 test vectors)
    // -----------------------------------------------------------------------

    @Test
    fun `x25519 RFC 7748 section 6 1 vector`() {
        // Alice's private key, a
        val aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        // Bob's public key, X25519(b, 9)
        val bPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        // Their shared secret, K
        val expectedK = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

        assertContentEquals(expectedK, x25519(aPriv, bPub))
    }

    @Test
    fun `x25519 RFC 7748 section 5 2 vector`() {
        // From RFC 7748 Section 5.2.
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoordinate = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        // Expected result from RFC 7748 Section 5.2. Note: starts with c3da5537.
        val expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")

        assertContentEquals(expected, x25519(scalar, uCoordinate))
    }

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
    // ChaCha20-Poly1305 IETF (RFC 8439 test vectors)
    // -----------------------------------------------------------------------

    @Test
    fun `chachapoly RFC 8439 appendix A 5 vector`() {
        val key = hex("1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0")
        val nonce = hex("000000000102030405060708")
        val aad = hex("f33388860000000000004e91")
        // RFC 8439 Appendix A.5 Plaintext
        val ptBytes =
            hex(
                "496e7465726e65742d4472616674732061726520647261667420646f63756d65" +
                    "6e74732076616c696420666f722061206d6178696d756d206f6620736978206d" +
                    "6f6e74687320616e64206d617920626520757064617465642c207265706c6163" +
                    "65642c206f72206f62736f6c65746564206279206f7468657220646f63756d65" +
                    "6e747320617420616e792074696d652e20497420697320696e617070726f7072" +
                    "6961746520746f2075736520496e7465726e65742d4472616674732061732072" +
                    "65666572656e6365206d6174657269616c206f7220746f206369746520746865" +
                    "6d206f74686572207468616e206173202fe2809c776f726b20696e2070726f67" +
                    "726573732e2fe2809d",
            )

        val ciphertext =
            hex(
                "64a0861575861af460f062c79be643bd" +
                    "5e805cfd345cf389f108670ac76c8cb2" +
                    "4c6cfc18755d43eea09ee94e382d26b0" +
                    "bdb7b73c321b0100d4f03b7f355894cf" +
                    "332f830e710b97ce98c8a84abd0b9481" +
                    "14ad176e008d33bd60f982b1ff37c855" +
                    "9797a06ef4f0ef61c186324e2b350638" +
                    "3606907b6a7c02b0f9f6157b53c867e4" +
                    "b9166c767b804d46a59b5216cde7a4e9" +
                    "9040c5a40433225ee282a1b0a06c523e" +
                    "af4534d7f83fa1155b0047718cbc546a" +
                    "0d072b04b3564eea1b422273f548271a" +
                    "0bb2316053fa76991955ebd63159434e" +
                    "cebb4e466dae5a1073a6727627097a10" +
                    "49e617d91d361094fa68f0ff77987130" +
                    "305beaba2eda04df997b714d6c6f2c29" +
                    "a6ad5cb4022b02709b",
            )
        val tag = hex("eead9d67890cbb22392336fea1851f38")
        val expectedCtTag = ciphertext + tag

        val actualCtTag = aeadEncrypt(key, nonce, ptBytes, aad)
        assertContentEquals(expectedCtTag, actualCtTag, "Encryption output must match RFC vector")

        val decryptedPT = aeadDecrypt(key, nonce, actualCtTag, aad)
        assertContentEquals(ptBytes, decryptedPT, "Decryption must return original plaintext")
    }

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

    // -----------------------------------------------------------------------
    // HKDF-SHA-256 (RFC 5869 Test Case 1)
    // -----------------------------------------------------------------------

    @Test
    fun `hkdf RFC 5869 test case 1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val l = 42
        val expectedOkm =
            hex(
                "3cb25f25faacd57a90434f64d0362f2a" +
                    "2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                    "34007208d5b887185865",
            )

        assertContentEquals(expectedOkm, hkdfSha256(ikm, salt, info, l))
    }
}
