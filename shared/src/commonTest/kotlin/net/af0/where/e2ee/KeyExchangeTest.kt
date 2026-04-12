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
        assertEquals(40, qr.fingerprint.length) // hex(SHA-256(ekPub)[0:20])

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
    fun `alice and bob derive matching bidirectional tokens`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // After exchange, Bob is still on epoch 0 (derived from SK).
        // Alice has ratcheted to epoch 1.
        // Bob will move to epoch 1 (and 2) when he receives Alice's first message.

        // Let Alice send a message.
        val (alice1, msg1) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))

        // Bob receives message 1 using Epoch 0 token.
        val (bob1, _) = Session.decryptMessage(bobSession, msg1)

        // After Bob processes Alice's message 1 (A1), he ratchets both his
        // receive and sending chains to Epoch 1.
        // So they now match on the SEND↔RECV tokens.
        assertContentEquals(alice1.sendToken, bob1.recvToken, "Alice sendToken (E1) = Bob recvToken (E1)")
        // Alice has NOT ratcheted her recv chain yet (waiting for B1).
        // Bob has ALREADY ratcheted his send chain (to B1).
        // But Bob's sendToken should be pending, so comunicaction still works.
        assertTrue(bob1.isSendTokenPending)
        assertContentEquals(alice1.recvToken, bob1.prevSendToken, "Alice recvToken (E0) = Bob prevSendToken (E0)")
    }

    @Test
    fun `alice send chain matches bob recv chain and vice versa`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Let Alice send a message.
        val (alice1, msg1) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))

        // Bob receives message 1.
        val (bob1, _) = Session.decryptMessage(bobSession, msg1)

        // Alice's send chain must equal Bob's receive chain.
        assertContentEquals(alice1.sendChainKey, bob1.recvChainKey)

        // Bob's send chain (Epoch 1) does NOT yet match Alice's receive chain (Epoch 0).
        // Alice matches Bob's send chain only AFTER she receives his message and ratchets.
        assertNotEquals(bob1.sendChainKey.toList(), alice1.recvChainKey.toList())
    }

    @Test
    fun `testBootstrapTokenHandshake - Alice first ratcheted message matches Bob bootstrap recvToken`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Alice's session was ratcheted IMMEDIATELY in aliceProcessInit.
        // Her current sendToken is derived from RK1.
        // But her prevSendToken is derived from SK.
        // isSendTokenPending should be true.
        assertTrue(aliceSession.isSendTokenPending)

        // Alice sends her first location.
        // Protocol states she must use prevSendToken for the first message of a new epoch.
        val (_, aliceEnc) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(1.0, 2.0, 3.0, 4L))

        // The token Alice actually posts to at the app layer (LocationClient logic)
        // would be prevSendToken. Let's verify that Alice's prevSendToken matches
        // Bob's initial recvToken.
        assertContentEquals(aliceSession.prevSendToken, bobSession.recvToken)

        // And verify decryption works.
        val (_, pt) = Session.decryptMessage(bobSession, aliceEnc)
        assertTrue(pt is MessagePlaintext.Location)
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
            } catch (_: AuthenticationException) {
                true
            }
        assertTrue(threw)
    }

    @Test
    fun `bobProcessQr rejects tampered fingerprint`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val badQr = qr.copy(fingerprint = "0".repeat(40)) // Tampered fingerprint

        val threw =
            try {
                KeyExchange.bobProcessQr(badQr, "Bob")
                false
            } catch (_: AuthenticationException) {
                true
            }
        assertTrue(threw, "Expected AuthenticationException for tampered fingerprint")
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
    fun `safetyNumber returns 60 bytes`() {
        val ekA = generateX25519KeyPair().pub
        val ekB = generateX25519KeyPair().pub
        assertEquals(60, safetyNumber(ekA, ekB).size)
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

    @Test
    fun `formatSafetyNumber produces three lines of 4 groups of 5 decimal digits`() {
        val ekA = generateX25519KeyPair().pub
        val ekB = generateX25519KeyPair().pub
        val formatted = formatSafetyNumber(safetyNumber(ekA, ekB))
        val lines = formatted.split("\n")
        assertEquals(3, lines.size)
        val groups = lines.flatMap { it.split(" ") }
        assertEquals(12, groups.size)
        for (group in groups) {
            assertEquals(5, group.length)
            assertTrue(group.all { it.isDigit() }, "Expected all digits but got: $group")
            val v = group.toInt()
            assertTrue(v in 0..99999, "Group value out of range: $v")
        }
    }

    @Test
    fun `FriendEntry safetyNumber is stable across DH ratchet steps`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Safety number before rotation
        val snBefore = formatSafetyNumber(safetyNumber(aliceSession.aliceEkPub, aliceSession.bobEkPub))

        // Alice sends message 1
        val (alice1, message1) = Session.encryptMessage(aliceSession, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))

        // Bob receives message 1
        val (bob1, _) = Session.decryptMessage(bobSession, message1)

        // Bob sends message 2 (new DH epoch for Alice)
        val (bob2, message2) = Session.encryptMessage(bob1, MessagePlaintext.Location(0.0, 0.0, 0.0, 0L))

        // Alice receives message 2 (advances DH)
        val (alice2, _) = Session.decryptMessage(alice1, message2)

        // aliceEkPub and bobEkPub must be unchanged after DH ratchet
        assertContentEquals(aliceSession.aliceEkPub, alice2.aliceEkPub)
        assertContentEquals(aliceSession.bobEkPub, alice2.bobEkPub)

        val snAfter = formatSafetyNumber(safetyNumber(alice2.aliceEkPub, alice2.bobEkPub))
        assertEquals(snBefore, snAfter, "Safety number changed after DH ratchet")
    }

    @Test
    fun `session aliceEkPub and bobEkPub are set correctly`() {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        // Bootstrap keys must be the original EK_A.pub and EK_B.pub
        assertContentEquals(qr.ekPub, aliceSession.aliceEkPub)
        assertContentEquals(msg.ekPub, aliceSession.bobEkPub)
        assertContentEquals(qr.ekPub, bobSession.aliceEkPub)
        assertContentEquals(msg.ekPub, bobSession.bobEkPub)
    }

    @Test
    fun `aliceProcessInit detects key substitution via different session keys`() {
        // Alice creates a QR payload (EK_A).
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")

        // Bob (legitimately) processes the QR and creates a KeyExchangeInitMessage (EK_B) and a session.
        val (_, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")

        // Attacker generates their own key pair (EK_M).
        val ekM = generateX25519KeyPair()

        // Attacker computes SK_AM = x25519(ekM.priv, qr.ekPub).
        val skAM = x25519(ekM.priv, qr.ekPub)

        // Attacker constructs a tampered KeyExchangeInitMessage with EK_M.pub and a keyConfirmation
        // correctly computed over (SK_AM, EK_A.pub, EK_M.pub) using KeyExchange.buildKeyConfirmation.
        // Attacker knows the discovery token.
        val tamperedMsg =
            KeyExchangeInitMessage(
                token = deriveDiscoveryToken(qr.discoverySecret),
                ekPub = ekM.pub.copyOf(),
                keyConfirmation = KeyExchange.buildKeyConfirmation(skAM, qr.ekPub, ekM.pub),
                suggestedName = "Attacker",
            )

        // Alice processes the tampered message using KeyExchange.aliceProcessInit and derives a session.
        val aliceSession = KeyExchange.aliceProcessInit(tamperedMsg, aliceEkPriv, qr.ekPub)

        // Verify that Alice's session keys (rootKey) are different from Bob's session keys.
        assertFalse(aliceSession.rootKey.contentEquals(bobSession.rootKey))

        // Verify that Alice's bobEkPub in her session state matches EK_M.pub, not EK_B.pub.
        assertContentEquals(ekM.pub, aliceSession.bobEkPub)
    }

    // ---------------------------------------------------------------------------
    // Discovery token (danmarg/where#5)
    // ---------------------------------------------------------------------------

    @Test
    fun `deriveDiscoveryToken produces 16 bytes`() {
        val secret = ByteArray(32) { it.toByte() }
        assertEquals(16, deriveDiscoveryToken(secret).size)
    }

    @Test
    fun `deriveDiscoveryToken is deterministic`() {
        val secret = ByteArray(32) { it.toByte() }
        assertContentEquals(deriveDiscoveryToken(secret), deriveDiscoveryToken(secret))
    }

    @Test
    fun `deriveDiscoveryToken differs for distinct secrets`() {
        val s1 = ByteArray(32) { it.toByte() }
        val s2 = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(deriveDiscoveryToken(s1).toList(), deriveDiscoveryToken(s2).toList())
    }

    @Test
    fun `QrPayload discoveryToken matches deriveDiscoveryToken on discoverySecret`() {
        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        assertContentEquals(deriveDiscoveryToken(qr.discoverySecret), qr.discoveryToken())
    }

    @Test
    fun `discovery token is distinct from session tokens`() {
        // Ensures the discovery address and the pairwise session address never collide.
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (initMsg, _) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(initMsg, aliceEkPriv, qr.ekPub)
        assertNotEquals(qr.discoveryToken().toList(), aliceSession.sendToken.toList(), "Discovery token ≠ send token")
        assertNotEquals(qr.discoveryToken().toList(), aliceSession.recvToken.toList(), "Discovery token ≠ recv token")
    }
}
