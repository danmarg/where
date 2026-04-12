package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionTest {
    init {
        initializeE2eeTests()
    }

    data class ExchangeResult(
        val aliceSession: SessionState,
        val bobSession: SessionState,
    )

    /** Full key exchange: Alice sends, Bob receives. */
    private fun exchangeKeys(): ExchangeResult {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        val aliceSession = KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub)

        return ExchangeResult(aliceSession, bobSession)
    }

    // ---------------------------------------------------------------------------
    // Encrypt / decrypt round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt message round-trip`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

        // Alice sends message 0 (symmetric epoch).
        val (aliceNew, message) = Session.encryptMessage(aliceSession, loc)
        val (_, decrypted) = Session.decryptMessage(bobSession, message)

        assertTrue(decrypted is MessagePlaintext.Location)
        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
        assertEquals(loc.acc, decrypted.acc)
        assertEquals(loc.ts, decrypted.ts)
    }

    @Test
    fun `seq advances on each encrypt`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = MessagePlaintext.Location(0.0, 0.0, 0.0, 0L)

        val (s1, _) = Session.encryptMessage(aliceSession, loc)
        val (s2, _) = Session.encryptMessage(s1, loc)
        val (s3, _) = Session.encryptMessage(s2, loc)

        assertEquals(1L, s1.sendSeq)
        assertEquals(2L, s2.sendSeq)
        assertEquals(3L, s3.sendSeq)
    }

    @Test
    fun `each message produces a different ciphertext`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = MessagePlaintext.Location(0.0, 0.0, 0.0, 0L)

        val (s1, m1) = Session.encryptMessage(aliceSession, loc)
        val (_, m2) = Session.encryptMessage(s1, loc)

        assertNotEquals(m1.ct.toList(), m2.ct.toList())
    }

    @Test
    fun `multiple sequential messages decrypt correctly`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val locs =
            (1..5).map { i ->
                MessagePlaintext.Location(lat = i.toDouble(), lng = i.toDouble(), acc = 1.0, ts = i.toLong())
            }

        var aSess = aliceSession
        var bSess = bobSession
        for (loc in locs) {
            val (newA, message) = Session.encryptMessage(aSess, loc)
            val (newB, dec) = Session.decryptMessage(bSess, message)
            assertTrue(dec is MessagePlaintext.Location)
            assertEquals(loc.lat, dec.lat)
            aSess = newA
            bSess = newB
        }
    }

    // ---------------------------------------------------------------------------
    // Replay rejection
    // ---------------------------------------------------------------------------

    @Test
    fun `replay is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        val (aliceNew, message) = Session.encryptMessage(aliceSession, loc)

        val (bobNew, _) = Session.decryptMessage(bobSession, message)
        // Second delivery of the same message must be rejected.
        try {
            Session.decryptMessage(bobNew, message)
            kotlin.test.fail("Expected ProtocolException for replay")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("replay") == true)
        }
    }

    @Test
    fun `message with lower seq than recvSeq is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(0.0, 0.0, 0.0, 0L)

        var aSess = aliceSession
        var bSess = bobSession
        val messages = mutableListOf<EncryptedMessagePayload>()
        repeat(3) {
            val (newA, message) = Session.encryptMessage(aSess, loc)
            messages += message
            aSess = newA
        }

        // Deliver in order first.
        for (message in messages) {
            val res = Session.decryptMessage(bSess, message)
            bSess = res.first
        }

        // Re-deliver any of them — all should be rejected.
        for (message in messages) {
            try {
                Session.decryptMessage(bSess, message)
                kotlin.test.fail("Expected ProtocolException for lower seq")
            } catch (e: ProtocolException) {
                assertTrue(e.message?.contains("replay") == true)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // MAX_GAP boundary
    // ---------------------------------------------------------------------------

    @Test
    fun `exactly MAX_GAP missed messages is allowed`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        var aSess = aliceSession
        var lastMessage: EncryptedMessagePayload? = null
        val target = 1024 + 1 // MAX_GAP = 1024; seq starts at 1, so we send 1025 msgs
        repeat(target) {
            val (newA, message) = Session.encryptMessage(aSess, loc)
            aSess = newA
            lastMessage = message
        }
        val (_, decrypted) = Session.decryptMessage(bobSession, lastMessage!!)
        assertTrue(decrypted is MessagePlaintext.Location)
        assertEquals(loc.lat, decrypted.lat)
    }

    @Test
    fun `MAX_GAP + 1 missed messages is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        var aSess = aliceSession
        var lastMessage: EncryptedMessagePayload? = null
        val target = MAX_GAP + 2
        repeat(target) {
            val (newA, message) = Session.encryptMessage(aSess, loc)
            aSess = newA
            lastMessage = message
        }
        try {
            Session.decryptMessage(bobSession, lastMessage!!)
            kotlin.test.fail("Expected ProtocolException for gap exceeding MAX_GAP")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("gap too large") == true)
        }
    }

    @Test
    fun `malicious large seq is rejected immediately without work`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val dummyCt = ByteArray(Session.PADDING_SIZE + 16)

        // Attacker sends seq = 2^63 - 1
        val largeSeq = Long.MAX_VALUE
        val message = EncryptedMessagePayload(dhPub = bobSession.remoteDhPub, seq = largeSeq.toString(), ct = dummyCt)

        val startTime = currentTimeMillis()
        try {
            Session.decryptMessage(bobSession, message)
            kotlin.test.fail("Expected ProtocolException for large seq")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("gap too large") == true)
        }
        val duration = currentTimeMillis() - startTime

        // Rejection should be near-instant (no HKDF iterations)
        assertTrue(duration < 100, "Large seq rejection took too long: \${duration}ms")
    }

    // ---------------------------------------------------------------------------
    // DH Ratchet
    // ---------------------------------------------------------------------------

    @Test
    fun `DH ratchet advances correctly`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        // Alice sends message 1. Stays in epoch 0.
        val (alice1, msg1) = Session.encryptMessage(aliceSession, loc)

        // Bob receives message 1. Stays in epoch 0.
        val (bob1, _) = Session.decryptMessage(bobSession, msg1)
        
        // Bob sends message 2. Bob generates new DH key B1, ratchets to epoch 1.
        val (bob2, msg2) = Session.encryptMessage(bob1, loc)

        // Alice receives message 2. She ratchets to Bob's B1 (epoch 1), then generates A1 (epoch 2).
        val (alice2, _) = Session.decryptMessage(alice1, msg2)

        assertNotEquals(aliceSession.rootKey.toHex(), alice2.rootKey.toHex(), "Alice's root key should change")
        
        // Alice sends message 3 (epoch 2).
        val (alice3, msg3) = Session.encryptMessage(alice2, loc)

        // Bob receives message 3. Bob ratchets to Alice's A1 (epoch 2), then generates B2 (epoch 3).
        val (bob3, _) = Session.decryptMessage(bob2, msg3)

        assertNotEquals(bob1.rootKey.toHex(), bob3.rootKey.toHex(), "Bob's root key should change")
        
        val (alice4, msg4) = Session.encryptMessage(alice3, loc)
        val (bob4, dec4) = Session.decryptMessage(bob3, msg4)
        assertTrue(dec4 is MessagePlaintext.Location)
    }

    @Test
    fun `tokens rotate on DH ratchet`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(1.0, 2.0, 3.0, 4L)

        // Capture initial tokens.
        val aliceInitialSendToken = aliceSession.sendToken
        val aliceInitialRecvToken = aliceSession.recvToken

        // Alice sends message 1 (epoch 0)
        val (alice1, msg1) = Session.encryptMessage(aliceSession, loc)
        // Bob receives message 1 (epoch 0)
        val (bob1, _) = Session.decryptMessage(bobSession, msg1)

        // Bob sends message 2 (epoch 1)
        val (bob2, msg2) = Session.encryptMessage(bob1, loc)
        // Alice receives message 2 (ratchets to Bob's epoch 1, then her own epoch 2)
        val (alice2, _) = Session.decryptMessage(alice1, msg2)

        assertNotEquals(aliceInitialSendToken.toHex(), alice2.sendToken.toHex(), "Alice's send token should rotate")
        assertNotEquals(aliceInitialRecvToken.toHex(), alice2.recvToken.toHex(), "Alice's recv token should rotate")
        
        // Alice sends message 3 (epoch 2)
        val (alice3, msg3) = Session.encryptMessage(alice2, loc)
        // Bob receives message 3 (ratchets to Alice's epoch 2, then his own epoch 3)
        val (bob3, _) = Session.decryptMessage(bob2, msg3)

        // Alice sends message 4 (epoch 2)
        val (alice4, msg4) = Session.encryptMessage(alice3, loc)
        // Bob receives message 4 (stays epoch 3)
        val (bob4, _) = Session.decryptMessage(bob3, msg4)
        
        // Eventually consistent.
        assertEquals(alice3.sendToken.toHex(), bob3.recvToken.toHex())
    }

    @Test
    fun `message from earlier epoch is rejected via lastRemoteDhPub`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = MessagePlaintext.Location(0.0, 0.0, 0.0, 0L)

        // Alice sends message 1 (epoch 1)
        val (alice1, msg1) = Session.encryptMessage(aliceSession, loc)
        // Bob receives message 1
        val (bob1, _) = Session.decryptMessage(bobSession, msg1)

        // Bob sends message 2 (epoch 2)
        val (bob2, msg2) = Session.encryptMessage(bob1, loc)
        // Alice receives message 2
        val (alice2, _) = Session.decryptMessage(alice1, msg2)

        // Alice sends message 3 (epoch 3)
        val (alice3, msg3) = Session.encryptMessage(alice2, loc)
        // Bob receives message 3
        val (bob3, _) = Session.decryptMessage(bob2, msg3)

        // Now Bob receives msg1 AGAIN (from epoch 1). 
        // Bob is currently holding state from epoch 3, so msg1's dhPub does not match Bob's current remoteDhPub.
        // It SHOULD be rejected because it matches lastRemoteDhPub instead, WITHOUT crashing due to a spurious DH ratchet.
        try {
            Session.decryptMessage(bob3, msg1)
            kotlin.test.fail("Expected ProtocolException for across-epoch replay")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("across-epoch replay") == true, "Message should be rejected explicitly as across-epoch replay")
        }
    }
}
