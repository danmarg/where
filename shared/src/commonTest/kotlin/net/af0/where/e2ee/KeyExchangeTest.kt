package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyExchangeTest {
    init {
        initializeE2eeTests()
    }

    // ---------------------------------------------------------------------------
    // QR payload
    // ---------------------------------------------------------------------------

    @Test
    fun `aliceCreateQrPayload produces a valid payload`() {
        val (qr, ekPriv) = KeyExchange.aliceCreateQrPayload("Alice")

        assertEquals(32, qr.ekPub.size)
        assertEquals(16, qr.fingerprint.length) // hex(SHA-256(ekPub)[0:8])

        // ekPriv is 32 bytes and non-zero.
        assertEquals(32, ekPriv.size)
        assertFalse(ekPriv.all { it == 0.toByte() })
    }

    @Test
    fun `aliceCreateQrPayload uses fresh ephemeral key each time`() {
        val (qr1, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val (qr2, _) = KeyExchange.aliceCreateQrPayload("Alice")
        // Different ephemeral keys on each invocation.
        assertNotEquals(qr1.ekPub.toList(), qr2.ekPub.toList())
    }

    // ---------------------------------------------------------------------------
    // Bob processing QR → KeyExchangeInit
    // ---------------------------------------------------------------------------

    @Test
    fun `bobProcessQr produces KeyExchangeInit with correct field sizes`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")

        val (msg, _) = KeyExchange.bobProcessQr(qr, "Bob")

        assertEquals(16, msg.token.size)
        assertEquals(32, msg.ekPub.size)
        assertEquals(32, msg.keyConfirmation.size)
        assertEquals("Bob", msg.suggestedName)
    }

    @Test
    fun `bobProcessQr key_confirmation is valid`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, _) = KeyExchange.bobProcessQr(qr, "Bob")

        // Alice can verify the key_confirmation by computing SK herself
        val sk = x25519(aliceEkPriv, msg.ekPub)
        assertTrue(KeyExchange.verifyKeyConfirmation(sk, qr.ekPub, msg.ekPub, msg.keyConfirmation))
    }

    // ---------------------------------------------------------------------------
    // Alice + Bob derive matching session state
    // ---------------------------------------------------------------------------

    @Test
    fun `alice and bob derive the same routing token`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        assertContentEquals(aliceSession.routingToken, bobSession.routingToken)
    }

    @Test
    fun `alice send chain matches bob recv chain and vice versa`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        assertContentEquals(aliceSession.rootKey, bobSession.rootKey)
        // Alice's send chain must equal Bob's receive chain, and vice versa.
        assertContentEquals(aliceSession.sendChainKey, bobSession.recvChainKey)
        assertContentEquals(aliceSession.recvChainKey, bobSession.sendChainKey)
    }

    @Test
    fun `aliceProcessInit rejects tampered key_confirmation`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, _) = KeyExchange.bobProcessQr(qr, "Bob")
        val badMsg = msg.copy(keyConfirmation = ByteArray(32))

        val threw =
            try {
                KeyExchange.aliceProcessInit(badMsg, aliceEkPriv, qr.ekPub)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        assertTrue(threw)
    }

    // ---------------------------------------------------------------------------
    // Key confirmation
    // ---------------------------------------------------------------------------

    @Test
    fun `key confirmation round-trip`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, _) = KeyExchange.bobProcessQr(qr, "Bob")

        // Both sides compute the same SK.
        val aliceSk = x25519(aliceEkPriv, msg.ekPub)
        val bobSk = x25519(msg.ekPub, qr.ekPub) // just verify the HMAC directly

        // Build confirmation from SK, verify it round-trips.
        val confirmation = KeyExchange.buildKeyConfirmation(aliceSk, qr.ekPub, msg.ekPub)
        assertTrue(KeyExchange.verifyKeyConfirmation(aliceSk, qr.ekPub, msg.ekPub, confirmation))
    }

    @Test
    fun `key confirmation fails with wrong sk`() {
        val ekAPub = ByteArray(32) { 0xAA.toByte() }
        val ekBPub = ByteArray(32) { 0xBB.toByte() }
        val sk = ByteArray(32) { 0xAA.toByte() }
        val wrongSk = ByteArray(32) { 0xBB.toByte() }
        val confirmation = KeyExchange.buildKeyConfirmation(sk, ekAPub, ekBPub)
        assertFalse(KeyExchange.verifyKeyConfirmation(wrongSk, ekAPub, ekBPub, confirmation))
    }

    @Test
    fun `key confirmation fails with wrong ekAPub`() {
        val ekAPub = ByteArray(32) { 0xAA.toByte() }
        val ekBPub = ByteArray(32) { 0xBB.toByte() }
        val wrongEkAPub = ByteArray(32) { 0xCC.toByte() }
        val sk = ByteArray(32) { 0xDD.toByte() }
        val confirmation = KeyExchange.buildKeyConfirmation(sk, ekAPub, ekBPub)
        assertFalse(KeyExchange.verifyKeyConfirmation(sk, wrongEkAPub, ekBPub, confirmation))
    }

    // ---------------------------------------------------------------------------
    // Session fingerprints
    // ---------------------------------------------------------------------------

    @Test
    fun `fingerprint is 32 bytes and deterministic`() {
        val ekPub = ByteArray(32) { it.toByte() }
        val fp1 = fingerprint(ekPub)
        val fp2 = fingerprint(ekPub)
        assertEquals(32, fp1.size)
        assertContentEquals(fp1, fp2)
    }

    @Test
    fun `session aliceFp and bobFp are set correctly`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Both sides derive same aliceFp and bobFp
        assertContentEquals(aliceSession.aliceFp, bobSession.aliceFp)
        assertContentEquals(aliceSession.bobFp, bobSession.bobFp)
        // aliceFp = SHA-256(EK_A.pub)
        assertContentEquals(sha256(qr.ekPub), aliceSession.aliceFp)
        // bobFp = SHA-256(EK_B.pub)
        assertContentEquals(sha256(msg.ekPub), aliceSession.bobFp)
    }

    @Test
    fun `safetyNumber is symmetric`() {
        val ekA = generateX25519KeyPair().pub
        val ekB = generateX25519KeyPair().pub
        val sn1 = safetyNumber(ekA, ekB)
        val sn2 = safetyNumber(ekB, ekA)
        assertContentEquals(sn1, sn2)
    }

    @Test
    fun `safetyNumber differs for different pairs`() {
        val ekA = generateX25519KeyPair().pub
        val ekB = generateX25519KeyPair().pub
        val ekC = generateX25519KeyPair().pub
        val sn1 = safetyNumber(ekA, ekB)
        val sn2 = safetyNumber(ekA, ekC)
        assertNotEquals(sn1.toList(), sn2.toList())
    }

    // ---------------------------------------------------------------------------
    // Discovery token (danmarg/where#5)
    // ---------------------------------------------------------------------------

    @Test
    fun `deriveDiscoveryToken produces 16 bytes`() {
        val ekPub = ByteArray(32) { it.toByte() }
        assertEquals(16, deriveDiscoveryToken(ekPub).size)
    }

    @Test
    fun `deriveDiscoveryToken is deterministic`() {
        val ekPub = ByteArray(32) { it.toByte() }
        assertContentEquals(deriveDiscoveryToken(ekPub), deriveDiscoveryToken(ekPub))
    }

    @Test
    fun `deriveDiscoveryToken differs for distinct ephemeral keys`() {
        val ek1 = generateX25519KeyPair().pub
        val ek2 = generateX25519KeyPair().pub
        assertNotEquals(deriveDiscoveryToken(ek1).toList(), deriveDiscoveryToken(ek2).toList())
    }

    @Test
    fun `QrPayload discoveryToken matches deriveDiscoveryToken on ekPub`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        assertContentEquals(deriveDiscoveryToken(qr.ekPub), qr.discoveryToken())
    }

    @Test
    fun `discovery token is distinct from session routing token`() {
        // Ensures the discovery address and the pairwise session address never collide.
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
        assertNotEquals(qr.discoveryToken().toList(), aliceSession.routingToken.toList())
    }
}
