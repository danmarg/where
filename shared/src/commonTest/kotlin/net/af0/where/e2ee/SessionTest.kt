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
    // Per-message token rotation (§8.3)
    // ---------------------------------------------------------------------------

    @Test
    fun `sendToken advances after each encrypt`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, _) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (s2, _) = Session.encryptLocation(s1, loc, aliceSession.aliceFp, aliceSession.bobFp)

        assertNotEquals(aliceSession.sendToken.toList(), s1.sendToken.toList(), "Token must change after first encrypt")
        assertNotEquals(s1.sendToken.toList(), s2.sendToken.toList(), "Token must change after second encrypt")
    }

    @Test
    fun `recvToken advances to match next sendToken after decrypt`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        // Alice sends two messages; sendToken advances each time.
        val (aliceS1, ct1) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp)
        val (aliceS2, ct2) = Session.encryptLocation(aliceS1, loc, aliceSession.aliceFp, aliceSession.bobFp)

        // Bob decrypts message 1; his recvToken must match Alice's sendToken after message 1.
        val (bobS1, _) = Session.decryptLocation(bobSession, ct1, aliceS1.sendSeq, bobSession.aliceFp, bobSession.bobFp)
        assertContentEquals(aliceS1.sendToken, bobS1.recvToken, "Bob recvToken must equal Alice's sendToken after msg 1")

        // Bob decrypts message 2; his recvToken must match Alice's sendToken after message 2.
        val (bobS2, _) = Session.decryptLocation(bobS1, ct2, aliceS2.sendSeq, bobS1.aliceFp, bobS1.bobFp)
        assertContentEquals(aliceS2.sendToken, bobS2.recvToken, "Bob recvToken must equal Alice's sendToken after msg 2")
    }

    @Test
    fun `single-token invariant Bob always has exactly one recvToken`() {
        // After decrypting any message, Bob's recvToken is always the token embedded
        // in that message's ciphertext — there is never a second "prev" token to poll.
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(5.0, 6.0, 7.0, 8L)

        var aSess = aliceSession
        var bSess = bobSession
        repeat(10) {
            val prevBobToken = bSess.recvToken
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp)
            val (newB, _) = Session.decryptLocation(bSess, ct, newA.sendSeq, bSess.aliceFp, bSess.bobFp)

            // Token must have changed (fresh random each time)
            assertNotEquals(prevBobToken.toList(), newB.recvToken.toList(), "recvToken must advance after each decrypt")
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
}
