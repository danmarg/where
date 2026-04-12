package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

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

    @Test
    fun `transactional safety on authentication failure`() {
        val (aliceSession, bobSession) = exchangeKeys()

        // Save initial state
        val initialRootKey = bobSession.rootKey.copyOf()
        val initialLocalDhPriv = bobSession.localDhPriv.copyOf()
        val initialRecvChainKey = bobSession.recvChainKey.copyOf()

        // Create a message with a NEW DH key but corrupted ciphertext
        val newAliceDh = generateX25519KeyPair()
        val badMsg = EncryptedMessagePayload(
            dhPub = newAliceDh.pub,
            seq = "1",
            ct = ByteArray(100) { 0xFF.toByte() }
        )

        // Decryption MUST fail
        assertFailsWith<AuthenticationException> {
            Session.decryptMessage(bobSession, badMsg)
        }

        // Verify state remains UNCHANGED
        assertContentEquals(initialRootKey, bobSession.rootKey, "Root key must not change")
        assertContentEquals(initialLocalDhPriv, bobSession.localDhPriv, "Local DH priv must not change")
        assertContentEquals(initialRecvChainKey, bobSession.recvChainKey, "Recv chain key must not change")
        assertEquals(0L, bobSession.recvSeq, "recvSeq must not change")
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
        val target = MAX_GAP + 1 
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
        val dummyCt = ByteArray(PADDING_SIZE + 16)

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
            assertTrue(e.message?.contains("out-of-order window closed") == true, "Message should be rejected as an unrecoverable gap/replay")
        }
    }

    @Test
    fun `corrupted padding is rejected even if length is valid`() {
        // Test padding logic directly.
        val data = "hello".encodeToByteArray()
        val padded = Session.padToFixedSize(data, 16)
        assertEquals(11, padded[15].toInt() and 0xFF) // padCount = 16 - 5 = 11
        
        // Valid case
        val unpadded = Session.unpad(padded)
        assertContentEquals(data, unpadded)
        
        // Corrupted padding byte (not length)
        val corruptedBody = padded.copyOf()
        corruptedBody[8] = 0xEE.toByte() // inside padding area
        try {
            Session.unpad(corruptedBody)
            kotlin.test.fail("Expected IllegalArgumentException for corrupted padding body")
        } catch (e: Exception) {
            // Success: unpad rejected the corruption
        }

        // Corrupted length
        val corruptedLen = padded.copyOf()
        corruptedLen[15] = 0x01.toByte() // says padCount=1 but it's >= 2
        try {
            Session.unpad(corruptedLen)
            kotlin.test.fail("Expected IllegalArgumentException for invalid padCount")
        } catch (e: Exception) {
            // Success
        }
    }

    @Test
    fun testRecycledDhPubRejection() = runTest {
        val aliceStore = E2eeStore(MemoryStorage())
        val bobStore = E2eeStore(MemoryStorage())
        
        // 1. Establish session
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        aliceStore.processKeyExchangeInit(initPayload, "Bob")
        val aliceToBobId = aliceStore.listFriends().first().id
        val bobToAliceId = bobEntry.id

        val aliceRecvToken = aliceStore.getFriend(aliceToBobId)!!.session.recvToken.toHex()
        val bobRecvToken = bobStore.getFriend(bobToAliceId)!!.session.recvToken.toHex()

        // Helper to perform a full turn: Alice sends, Bob receives, Bob sends, Alice receives.
        // This advances the DH ratchet on both sides.
        suspend fun HandshakeTurn() {
            // Alice -> Bob
            val (aState, aMsg) = Session.encryptMessage(aliceStore.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceStore.updateSession(aliceToBobId, aState)
            bobStore.processBatch(bobToAliceId, bobRecvToken, listOf(aMsg))

            // Bob -> Alice
            val (bState, bMsg) = Session.encryptMessage(bobStore.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobStore.updateSession(bobToAliceId, bState)
            aliceStore.processBatch(aliceToBobId, aliceRecvToken, listOf(bMsg))
        }

        // 2. Perform two turns to ensure seenRemoteDhPubs is populated with Bob's old keys.
        // Turn 1: Alice sees Bob's B0, sends A1. Bob sees A1, rotates to B1. Bob sends B1. Alice sees B1, rotates to A2. seen={B0}
        HandshakeTurn()
        // Turn 2: Alice sends A2. Bob sees A2, rotates to B2. Bob sends B2. Alice sees B2, rotates to A3. seen={B0, B1}
        HandshakeTurn()

        // 3. Alice's seenRemoteDhPubs should now contain Bob's initial and intermediate keys
        val bobInitialDh = initPayload.ekPub
        val aliceSession = aliceStore.getFriend(aliceToBobId)!!.session
        
        assertTrue(aliceSession.seenRemoteDhPubs.contains(bobInitialDh.toHex()), "seenRemoteDhPubs should have Bob's initial DH")

        // 4. Attacker tries to send a message (seq=1) using Bob's initial DH key
        val recycledPayload = EncryptedMessagePayload(dhPub = bobInitialDh, seq = "1", ct = ByteArray(32))
        
        try {
            Session.decryptMessage(aliceSession, recycledPayload)
            kotlin.test.fail("Expected ProtocolException for recycled/replayed DH public key")
        } catch (e: ProtocolException) {
            println("[DEBUG] Caught expected exception: ${e.message}")
            assertTrue(e.message!!.contains("dhPub already superseded"), "Error should indicate superseded/old DH: ${e.message}")
        }
    }
}
