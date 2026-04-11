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
    fun `encrypt and decrypt location round-trip`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (_, decrypted) = Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, bobSession.aliceFp, bobSession.bobFp)

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
        assertEquals(loc.acc, decrypted.acc)
        assertEquals(loc.ts, decrypted.ts)
    }

    @Test
    fun `seq advances on each encrypt`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, _) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (s2, _) = Session.encryptLocation(s1, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (s3, _) = Session.encryptLocation(s2, loc, aliceSession.aliceFp, aliceSession.bobFp)

        assertEquals(1L, s1.sendSeq)
        assertEquals(2L, s2.sendSeq)
        assertEquals(3L, s3.sendSeq)
    }

    @Test
    fun `each message produces a different ciphertext`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, ct1) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (_, ct2) = Session.encryptLocation(s1, loc, aliceSession.aliceFp, aliceSession.bobFp)

        assertNotEquals(ct1.toList(), ct2.toList())
    }

    @Test
    fun `multiple sequential messages decrypt correctly`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val locs =
            (1..5).map { i ->
                LocationPlaintext(lat = i.toDouble(), lng = i.toDouble(), acc = 1.0, ts = i.toLong())
            }

        var aSess = aliceSession
        var bSess = bobSession
        for (loc in locs) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp)
            val (newB, dec) = Session.decryptLocation(bSess, ct, newA.sendSeq, bSess.aliceFp, bSess.bobFp)
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
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val seq = aliceNew.sendSeq

        val (bobNew, _) = Session.decryptLocation(bobSession, ct, seq, bobSession.aliceFp, bobSession.bobFp)
        // Second delivery of the same seq must be rejected.
        try {
            Session.decryptLocation(bobNew, ct, seq, bobNew.aliceFp, bobNew.bobFp)
            kotlin.test.fail("Expected ProtocolException for replay")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("replay") == true)
        }
    }

    @Test
    fun `frame with lower seq than recvSeq is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        var aSess = aliceSession
        var bSess = bobSession
        val cts = mutableListOf<Pair<Long, ByteArray>>()
        repeat(3) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp)
            cts += newA.sendSeq to ct
            aSess = newA
        }

        // Deliver in order first.
        for ((seq, ct) in cts) {
            val res = Session.decryptLocation(bSess, ct, seq, bSess.aliceFp, bSess.bobFp)
            bSess = res.first
        }

        // Re-deliver any of them — all should be rejected.
        for ((seq, ct) in cts) {
            try {
                Session.decryptLocation(bSess, ct, seq, bSess.aliceFp, bSess.bobFp)
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
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        // Advance Alice's chain MAX_GAP + 1 times: skip the first MAX_GAP
        // messages and deliver only the (MAX_GAP + 1)-th.
        var aSess = aliceSession
        var lastCt = ByteArray(0)
        val target = 1024 + 1 // MAX_GAP = 1024; seq starts at 1, so we send 1025 msgs
        repeat(target) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp)
            aSess = newA
            lastCt = ct
        }
        // Bob has recvSeq=0; stepsNeeded = 1025 = MAX_GAP + 1; should be accepted.
        val (_, decrypted) = Session.decryptLocation(bobSession, lastCt, aSess.sendSeq, bobSession.aliceFp, bobSession.bobFp)
        assertEquals(loc.lat, decrypted.lat)
    }

    @Test
    fun `MAX_GAP + 1 missed messages is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        // Send MAX_GAP + 2 messages (seq = 1026); stepsNeeded = 1026 > 1025.
        var aSess = aliceSession
        var lastCt = ByteArray(0)
        val target = 1024 + 2
        repeat(target) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp)
            aSess = newA
            lastCt = ct
        }
        try {
            Session.decryptLocation(bobSession, lastCt, aSess.sendSeq, bobSession.aliceFp, bobSession.bobFp)
            kotlin.test.fail("Expected ProtocolException for gap exceeding MAX_GAP")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("exceeds maximum") == true)
        }
    }

    @Test
    fun `malicious large seq is rejected immediately without work`() {
        val (_, bobSession) = exchangeKeys()
        val dummyCt = ByteArray(Session.PADDING_SIZE + 16)

        // Attacker sends seq = 2^63 - 1
        val largeSeq = Long.MAX_VALUE

        val startTime = currentTimeMillis()
        try {
            Session.decryptLocation(bobSession, dummyCt, largeSeq, bobSession.aliceFp, bobSession.bobFp)
            kotlin.test.fail("Expected ProtocolException for large seq")
        } catch (e: ProtocolException) {
            assertTrue(e.message?.contains("exceeds maximum") == true)
        }
        val duration = currentTimeMillis() - startTime

        // Rejection should be near-instant (no HKDF iterations)
        assertTrue(duration < 100, "Large seq rejection took too long: ${duration}ms")
    }

    // ---------------------------------------------------------------------------
    // AAD integrity
    // ---------------------------------------------------------------------------

    @Test
    fun `ciphertext is bound to sender fingerprint`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val eveFp = sha256(generateX25519KeyPair().pub)

        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)
        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)

        // Decrypting with wrong sender fingerprint must fail.
        val threw =
            try {
                Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, eveFp, bobSession.bobFp)
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "Expected decryption to fail with wrong sender fingerprint")
    }

    // ---------------------------------------------------------------------------
    // Epoch rotation (DH ratchet)
    // ---------------------------------------------------------------------------

    @Test
    fun `epoch rotation produces different routing token and chain key`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()

        val pending =
            Session.aliceEpochRotation(
                state = aliceSession,
                aliceNewEkPriv = aliceNewEk.priv,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPub = bobOpk.pub,
                opkId = 1,
                aliceFp = aliceSession.aliceFp,
                bobFp = aliceSession.bobFp,
            )
        val (bobRotated, ackCt) =
            Session.bobProcessAliceRotation(
                state = bobSession,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPriv = bobOpk.priv,
                aliceFp = bobSession.aliceFp,
                bobFp = bobSession.bobFp,
            )
        // Alice performs step 2 DH on commit — final tokens come from rootKey2.
        val aliceCommitted =
            Session.aliceProcessRatchetAck(
                pendingRotation = pending,
                ackCt = ackCt,
                bobFp = aliceSession.bobFp,
                aliceFp = aliceSession.aliceFp,
            )

        assertContentEquals(aliceCommitted.sendToken, bobRotated.recvToken, "Alice send = Bob recv after rotation")
        assertContentEquals(aliceCommitted.recvToken, bobRotated.sendToken, "Alice recv = Bob send after rotation")
        assertNotEquals(aliceSession.sendToken.toList(), aliceCommitted.sendToken.toList(), "Token changed after rotation")
        assertContentEquals(aliceCommitted.rootKey, bobRotated.rootKey)
        // Step 1: Alice's send chain = Bob's recv chain (chainKey_AB).
        assertContentEquals(aliceCommitted.sendChainKey, bobRotated.recvChainKey, "chainKey_AB: Alice send = Bob recv")
        // Step 2: Bob's send chain = Alice's recv chain (chainKey_BA) — mutual PFS.
        assertContentEquals(bobRotated.sendChainKey, aliceCommitted.recvChainKey, "chainKey_BA: Bob send = Alice recv")
    }

    @Test
    fun `messages after epoch rotation can be decrypted`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()

        val pending =
            Session.aliceEpochRotation(
                aliceSession,
                aliceNewEk.priv,
                aliceNewEk.pub,
                bobOpk.pub,
                opkId = 1,
                aliceFp = aliceSession.aliceFp,
                bobFp = aliceSession.bobFp,
            )
        val (bobRotated, ackCt) =
            Session.bobProcessAliceRotation(
                bobSession,
                aliceNewEk.pub,
                bobOpk.priv,
                aliceFp = bobSession.aliceFp,
                bobFp = bobSession.bobFp,
            )
        val aliceCommitted =
            Session.aliceProcessRatchetAck(
                pendingRotation = pending,
                ackCt = ackCt,
                bobFp = aliceSession.bobFp,
                aliceFp = aliceSession.aliceFp,
            )

        val loc = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 5.0, ts = 1711155000L)
        val (aliceAfter, ct) =
            Session.encryptLocation(aliceCommitted, loc, aliceCommitted.aliceFp, aliceCommitted.bobFp)
        val (_, decrypted) =
            Session.decryptLocation(bobRotated, ct, aliceAfter.sendSeq, bobRotated.aliceFp, bobRotated.bobFp)

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
    }

    // ---------------------------------------------------------------------------
    // AEAD control message helpers
    // ---------------------------------------------------------------------------

    @Test
    fun `EpochRotation AEAD round-trip`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val sendToken = ByteArray(16) { (it + 1).toByte() }
        val newEkPub = ByteArray(32) { (it + 64).toByte() }
        val aliceFp = ByteArray(32) { it.toByte() }
        val bobFp = ByteArray(32) { (it + 1).toByte() }

        val ct =
            buildEpochRotationCt(
                currentRootKey = rootKey,
                opkId = 7,
                newEkPub = newEkPub,
                aliceFp = aliceFp,
                bobFp = bobFp,
                sendToken = sendToken,
            )

        val plaintext =
            decryptEpochRotationCt(
                currentRootKey = rootKey,
                ct = ct,
                aliceFp = aliceFp,
                bobFp = bobFp,
                sendToken = sendToken,
            )

        assertEquals(7, plaintext.opkId)
        assertContentEquals(newEkPub, plaintext.newEkPub)
    }

    @Test
    fun `EpochRotation AEAD fails with wrong root key`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        val sendToken = ByteArray(16) { 0x01.toByte() }
        val newEkPub = ByteArray(32)
        val aliceFp = ByteArray(32)
        val bobFp = ByteArray(32)

        val ct = buildEpochRotationCt(rootKey, opkId = 1, newEkPub = newEkPub, aliceFp = aliceFp, bobFp = bobFp, sendToken = sendToken)

        val threw =
            try {
                decryptEpochRotationCt(wrongKey, ct, aliceFp, bobFp, sendToken)
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "Expected AEAD to fail with wrong root key")
    }

    @Test
    fun `RatchetAck AEAD round-trip`() {
        val intermediateRootKey = ByteArray(32) { it.toByte() }
        val intermediateSendToken = ByteArray(16) { (it + 1).toByte() }
        val bobNewEkPub = ByteArray(32) { (it + 2).toByte() }
        val bobFp = ByteArray(32) { it.toByte() }
        val aliceFp = ByteArray(32) { (it + 1).toByte() }

        val ct =
            buildRatchetAckCt(
                intermediateRootKey = intermediateRootKey,
                bobNewEkPub = bobNewEkPub,
                bobFp = bobFp,
                aliceFp = aliceFp,
                intermediateSendToken = intermediateSendToken,
            )
        val recovered =
            decryptRatchetAckCt(
                intermediateRootKey = intermediateRootKey,
                ct = ct,
                bobFp = bobFp,
                aliceFp = aliceFp,
                intermediateSendToken = intermediateSendToken,
            )
        assertContentEquals(bobNewEkPub, recovered, "bobNewEkPub should round-trip through AEAD")
    }

    @Test
    fun `RatchetAck AEAD fails with wrong routing token`() {
        val intermediateRootKey = ByteArray(32) { it.toByte() }
        val intermediateSendToken = ByteArray(16) { 0x01.toByte() }
        val wrongToken = ByteArray(16) { 0x02.toByte() }
        val bobNewEkPub = ByteArray(32)
        val bobFp = ByteArray(32)
        val aliceFp = ByteArray(32)

        val ct =
            buildRatchetAckCt(
                intermediateRootKey = intermediateRootKey,
                bobNewEkPub = bobNewEkPub,
                bobFp = bobFp,
                aliceFp = aliceFp,
                intermediateSendToken = intermediateSendToken,
            )

        val threw =
            try {
                decryptRatchetAckCt(
                    intermediateRootKey = intermediateRootKey,
                    ct = ct,
                    bobFp = bobFp,
                    aliceFp = aliceFp,
                    intermediateSendToken = wrongToken,
                )
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "Expected AEAD to fail with wrong routing token")
    }

    @Test
    fun `test private keys are zeroed after use`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val aliceNewEk = generateX25519KeyPair()
        val bobOpk = generateX25519KeyPair()

        // aliceEpochRotation must zero the caller-supplied private key buffer.
        // A copy is kept in PendingRotation.aliceNewEkPriv for step 2.
        val pending =
            Session.aliceEpochRotation(
                state = aliceSession,
                aliceNewEkPriv = aliceNewEk.priv,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPub = bobOpk.pub,
                opkId = 1,
                aliceFp = aliceSession.aliceFp,
                bobFp = aliceSession.bobFp,
            )
        assertTrue(aliceNewEk.priv.all { it == 0.toByte() }, "caller's aliceNewEkPriv should be zeroed")
        assertTrue(pending.aliceNewEkPriv.any { it != 0.toByte() }, "PendingRotation must hold a live copy of aliceNewEkPriv")

        // bobProcessAliceRotation must zero the OPK private key buffer
        val (_, ackCt) =
            Session.bobProcessAliceRotation(
                state = bobSession,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPriv = bobOpk.priv,
                aliceFp = bobSession.aliceFp,
                bobFp = bobSession.bobFp,
            )
        assertTrue(bobOpk.priv.all { it == 0.toByte() }, "bobOpkPriv should be zeroed")

        // aliceProcessRatchetAck must zero the live aliceNewEkPriv copy in PendingRotation
        Session.aliceProcessRatchetAck(
            pendingRotation = pending,
            ackCt = ackCt,
            bobFp = aliceSession.bobFp,
            aliceFp = aliceSession.aliceFp,
        )
        assertTrue(pending.aliceNewEkPriv.all { it == 0.toByte() }, "PendingRotation.aliceNewEkPriv should be zeroed after commit")
    }
}
