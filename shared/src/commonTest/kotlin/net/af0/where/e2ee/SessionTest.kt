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

    private val nextRecvToken = ByteArray(16) { 0x11.toByte() }
    private val emptyOpkPrivGetter: (Int) -> ByteArray? = { null }

    // ---------------------------------------------------------------------------
    // Encrypt / decrypt round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt location round-trip`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)
        val (bobNew, decrypted) = Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
        assertEquals(loc.acc, decrypted.acc)
        assertEquals(loc.ts, decrypted.ts)
        assertContentEquals(nextRecvToken, bobNew.recvToken)
    }

    @Test
    fun `seq advances on each encrypt`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, _) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)
        val (s2, _) = Session.encryptLocation(s1, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)
        val (s3, _) = Session.encryptLocation(s2, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)

        assertEquals(1L, s1.sendSeq)
        assertEquals(2L, s2.sendSeq)
        assertEquals(3L, s3.sendSeq)
    }

    @Test
    fun `each message produces a different ciphertext`() {
        val (aliceSession, _) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, ct1) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)
        val (_, ct2) = Session.encryptLocation(s1, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)

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
            val nextToken = randomBytes(16)
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp, nextToken)
            val (newB, dec) = Session.decryptLocation(bSess, ct, newA.sendSeq, bSess.aliceFp, bSess.bobFp, emptyOpkPrivGetter)
            assertEquals(loc.lat, dec.lat)
            assertContentEquals(nextToken, newB.recvToken)
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

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)
        val seq = aliceNew.sendSeq

        val (bobNew, _) = Session.decryptLocation(bobSession, ct, seq, bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)
        // Second delivery of the same seq must be rejected.
        try {
            Session.decryptLocation(bobNew, ct, seq, bobNew.aliceFp, bobNew.bobFp, emptyOpkPrivGetter)
            kotlin.test.fail("Expected IllegalArgumentException for replay")
        } catch (e: IllegalArgumentException) {
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
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp, nextRecvToken)
            cts += newA.sendSeq to ct
            aSess = newA
        }

        // Deliver in order first.
        for ((seq, ct) in cts) {
            val res = Session.decryptLocation(bSess, ct, seq, bSess.aliceFp, bSess.bobFp, emptyOpkPrivGetter)
            bSess = res.first
        }

        // Re-deliver any of them — all should be rejected.
        for ((seq, ct) in cts) {
            try {
                Session.decryptLocation(bSess, ct, seq, bSess.aliceFp, bSess.bobFp, emptyOpkPrivGetter)
                kotlin.test.fail("Expected IllegalArgumentException for lower seq")
            } catch (e: IllegalArgumentException) {
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

        var aSess = aliceSession
        var lastCt = ByteArray(0)
        val target = 1024 + 1
        repeat(target) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp, nextRecvToken)
            aSess = newA
            lastCt = ct
        }
        val (_, decrypted) = Session.decryptLocation(bobSession, lastCt, aSess.sendSeq, bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)
        assertEquals(loc.lat, decrypted.lat)
    }

    @Test
    fun `MAX_GAP + 1 missed messages is rejected`() {
        val (aliceSession, bobSession) = exchangeKeys()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        var aSess = aliceSession
        var lastCt = ByteArray(0)
        val target = 1024 + 2
        repeat(target) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aSess.aliceFp, aSess.bobFp, nextRecvToken)
            aSess = newA
            lastCt = ct
        }
        try {
            Session.decryptLocation(bobSession, lastCt, aSess.sendSeq, bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)
            kotlin.test.fail("Expected IllegalArgumentException for gap exceeding MAX_GAP")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds maximum") == true)
        }
    }

    @Test
    fun `malicious large seq is rejected immediately without work`() {
        val (_, bobSession) = exchangeKeys()
        val dummyCt = ByteArray(Session.PADDING_SIZE + 16)

        val largeSeq = Long.MAX_VALUE

        val startTime = currentTimeMillis()
        try {
            Session.decryptLocation(bobSession, dummyCt, largeSeq, bobSession.aliceFp, bobSession.bobFp, emptyOpkPrivGetter)
            kotlin.test.fail("Expected IllegalArgumentException for large seq")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds maximum") == true)
        }
        val duration = currentTimeMillis() - startTime

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
        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken)

        val threw =
            try {
                Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, eveFp, bobSession.bobFp, emptyOpkPrivGetter)
                false
            } catch (_: Exception) {
                true
            }
        assertTrue(threw, "Expected decryption to fail with wrong sender fingerprint")
    }

    // ---------------------------------------------------------------------------
    // Integrated DH ratchet
    // ---------------------------------------------------------------------------

    @Test
    fun `integrated DH ratchet produces different root key`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        val (aliceNew, ct) = Session.encryptLocation(
            aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken,
            nextOpkId = 1, nextBobOpkPub = bobOpk.pub, aliceNewEkPriv = aliceNewEk.priv, aliceNewEkPub = aliceNewEk.pub
        )

        val opkPrivGetter: (Int) -> ByteArray? = { id -> if (id == 1) bobOpk.priv else null }
        val (bobNew, _) = Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, bobSession.aliceFp, bobSession.bobFp, opkPrivGetter)

        assertNotEquals(aliceSession.rootKey.toList(), aliceNew.rootKey.toList(), "Root key should change")
        assertContentEquals(aliceNew.rootKey, bobNew.rootKey, "Root keys should match after integrated DH")
        assertContentEquals(aliceNew.sendChainKey, bobNew.recvChainKey, "Chain keys should match after integrated DH")
    }

    @Test
    fun `integrated DH ratchet handles missing OPK gracefully`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        val (aliceNew, ct) = Session.encryptLocation(
            aliceSession, loc, aliceSession.aliceFp, aliceSession.bobFp, nextRecvToken,
            nextOpkId = 1, nextBobOpkPub = bobOpk.pub, aliceNewEkPriv = aliceNewEk.priv, aliceNewEkPub = aliceNewEk.pub
        )

        // Bob doesn't have the OPK
        val opkPrivGetter: (Int) -> ByteArray? = { null }
        val (bobNew, _) = Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, bobSession.aliceFp, bobSession.bobFp, opkPrivGetter)

        assertNotEquals(aliceNew.rootKey.toList(), bobNew.rootKey.toList())
    }
}
