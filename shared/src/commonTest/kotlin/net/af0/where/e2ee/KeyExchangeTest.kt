package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyExchangeTest {
    private fun makeIdentity(): IdentityKeys = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())

    // ---------------------------------------------------------------------------
    // QR payload
    // ---------------------------------------------------------------------------

    @Test
    fun `aliceCreateQrPayload produces a valid signed payload`() {
        val alice = makeIdentity()
        val (qr, ekPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")

        assertContentEquals(alice.ik.pub, qr.ikPub)
        assertContentEquals(alice.sigIk.pub, qr.sigPub)
        assertEquals(32, qr.ekPub.size)
        assertEquals(64, qr.sig.size)
        assertEquals(20, qr.fingerprint.length) // hex(SHA-256(ikPub)[0:10])

        // Signature must be valid.
        val signedData = qr.ikPub + qr.ekPub + qr.sigPub
        assertTrue(ed25519Verify(qr.sigPub, signedData, qr.sig))

        // ekPriv is 32 bytes and non-zero.
        assertEquals(32, ekPriv.size)
        assertFalse(ekPriv.all { it == 0.toByte() })
    }

    @Test
    fun `aliceCreateQrPayload uses fresh ephemeral key each time`() {
        val alice = makeIdentity()
        val (qr1, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (qr2, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        // Different ephemeral keys on each invocation.
        assertNotEquals(qr1.ekPub.toList(), qr2.ekPub.toList())
    }

    // ---------------------------------------------------------------------------
    // Bob processing QR → KeyExchangeInit
    // ---------------------------------------------------------------------------

    @Test
    fun `bobProcessQr produces signed KeyExchangeInit with correct field sizes`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val (qr, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (msg, _) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)

        assertEquals(16, msg.token.size)
        assertEquals(32, msg.ikPub.size)
        assertEquals(32, msg.ekPub.size)
        assertEquals(32, msg.sigPub.size)
        assertEquals(64, msg.sig.size)
        assertContentEquals(bob.ik.pub, msg.ikPub)
        assertContentEquals(bob.sigIk.pub, msg.sigPub)
    }

    @Test
    fun `bobProcessQr signature on KeyExchangeInit is valid`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val (qr, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (msg, _) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)

        val signedData = msg.ikPub + msg.ekPub + msg.sigPub
        assertTrue(ed25519Verify(msg.sigPub, signedData, msg.sig))
    }

    @Test
    fun `bobProcessQr rejects tampered QR signature`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val (qr, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val badQr = qr.copy(sig = ByteArray(64)) // zeroed sig
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val threw =
            try {
                KeyExchange.bobProcessQr(badQr, bob, aliceFp, bobFp)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        assertTrue(threw, "Expected bobProcessQr to reject tampered QR signature")
    }

    // ---------------------------------------------------------------------------
    // Alice + Bob derive matching session state
    // ---------------------------------------------------------------------------

    @Test
    fun `alice and bob derive the same routing token`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(msg, alice, aliceEkPriv, aliceFp, bobFp)

        assertContentEquals(aliceSession.routingToken, bobSession.routingToken)
    }

    @Test
    fun `alice and bob derive the same root and chain keys`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(msg, alice, aliceEkPriv, aliceFp, bobFp)

        assertContentEquals(aliceSession.rootKey, bobSession.rootKey)
        assertContentEquals(aliceSession.sendChainKey, bobSession.sendChainKey)
    }

    @Test
    fun `aliceProcessInit rejects tampered KeyExchangeInit signature`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, _) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val badMsg = msg.copy(sig = ByteArray(64))

        val threw =
            try {
                KeyExchange.aliceProcessInit(badMsg, alice, aliceEkPriv, aliceFp, bobFp)
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
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(msg, alice, aliceEkPriv, aliceFp, bobFp)

        // Both sides should derive the same SK (evidenced by identical session keys above).
        // Build confirmation from Bob's side, verify on Alice's side.
        // We compute SK directly to pass to buildKeyConfirmation.
        val bobDh1 = x25519(bobSession.myEkPriv, qr.ikPub)
        val bobDh2 = x25519(bob.ik.priv, qr.ekPub)
        val bobDh3 = x25519(bobSession.myEkPriv, qr.ekPub)
        val bobSk = KeyExchange.deriveSK(bobDh1, bobDh2, bobDh3)

        val aliceDh1 = x25519(alice.ik.priv, msg.ekPub)
        val aliceDh2 = x25519(aliceEkPriv, msg.ikPub)
        val aliceDh3 = x25519(aliceEkPriv, msg.ekPub)
        val aliceSk = KeyExchange.deriveSK(aliceDh1, aliceDh2, aliceDh3)

        assertContentEquals(bobSk, aliceSk)

        val token = bobSession.routingToken
        val ct = KeyExchange.buildKeyConfirmation(bobSk, token)
        assertTrue(KeyExchange.verifyKeyConfirmation(aliceSk, token, ct))
    }

    @Test
    fun `key confirmation fails with wrong sk`() {
        val sk = ByteArray(32) { 0xAA.toByte() }
        val wrongSk = ByteArray(32) { 0xBB.toByte() }
        val token = ByteArray(16) { 0xCC.toByte() }
        val ct = KeyExchange.buildKeyConfirmation(sk, token)
        assertFalse(KeyExchange.verifyKeyConfirmation(wrongSk, token, ct))
    }

    @Test
    fun `key confirmation fails with wrong token`() {
        val sk = ByteArray(32) { 0xAA.toByte() }
        val token = ByteArray(16) { 0xCC.toByte() }
        val wrongToken = ByteArray(16) { 0xDD.toByte() }
        val ct = KeyExchange.buildKeyConfirmation(sk, token)
        assertFalse(KeyExchange.verifyKeyConfirmation(sk, wrongToken, ct))
    }

    // ---------------------------------------------------------------------------
    // Fingerprint and safety number
    // ---------------------------------------------------------------------------

    @Test
    fun `fingerprint is 32 bytes and deterministic`() {
        val ikPub = ByteArray(32) { it.toByte() }
        val sigIkPub = ByteArray(32) { (it + 32).toByte() }
        val fp1 = fingerprint(ikPub, sigIkPub)
        val fp2 = fingerprint(ikPub, sigIkPub)
        assertEquals(32, fp1.size)
        assertContentEquals(fp1, fp2)
    }

    @Test
    fun `safetyNumber is symmetric`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val sn1 = safetyNumber(alice.ik.pub, alice.sigIk.pub, bob.ik.pub, bob.sigIk.pub)
        val sn2 = safetyNumber(bob.ik.pub, bob.sigIk.pub, alice.ik.pub, alice.sigIk.pub)
        assertContentEquals(sn1, sn2)
    }

    @Test
    fun `safetyNumber differs for different pairs`() {
        val a = makeIdentity()
        val b = makeIdentity()
        val c = makeIdentity()
        val sn1 = safetyNumber(a.ik.pub, a.sigIk.pub, b.ik.pub, b.sigIk.pub)
        val sn2 = safetyNumber(a.ik.pub, a.sigIk.pub, c.ik.pub, c.sigIk.pub)
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
        val alice = makeIdentity()
        val (qr, _) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        assertContentEquals(deriveDiscoveryToken(qr.ekPub), qr.discoveryToken())
    }

    @Test
    fun `discovery token is distinct from session routing token`() {
        // Ensures the discovery address and the pairwise session address never collide.
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (initMsg, _) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, alice, aliceEkPriv, aliceFp, bobFp)
        assertNotEquals(qr.discoveryToken().toList(), aliceSession.routingToken.toList())
    }
}
