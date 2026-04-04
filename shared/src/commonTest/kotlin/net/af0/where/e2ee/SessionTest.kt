package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
                kotlin.test.fail("Expected IllegalArgumentException for lower seq")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("replay") == true)
            }
        }
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

        val aliceRotated =
            Session.aliceEpochRotation(
                state = aliceSession,
                aliceNewEkPriv = aliceNewEk.priv,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPub = bobOpk.pub,
                senderFp = aliceSession.aliceFp,
                recipientFp = aliceSession.bobFp,
            )
        val bobRotated =
            Session.bobProcessAliceRotation(
                state = bobSession,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPriv = bobOpk.priv,
                newEpoch = 1,
                senderFp = bobSession.aliceFp,
                recipientFp = bobSession.bobFp,
            )

        assertEquals(1, aliceRotated.epoch)
        assertEquals(1, bobRotated.epoch)
        assertContentEquals(aliceRotated.sendToken, bobRotated.recvToken, "Alice send = Bob recv after rotation")
        assertContentEquals(aliceRotated.recvToken, bobRotated.sendToken, "Alice recv = Bob send after rotation")
        assertNotEquals(aliceSession.sendToken.toList(), aliceRotated.sendToken.toList(), "Token changed after rotation")
        assertContentEquals(aliceRotated.rootKey, bobRotated.rootKey)
        // After epoch rotation Alice's new send chain must equal Bob's new recv chain.
        assertContentEquals(aliceRotated.sendChainKey, bobRotated.recvChainKey)
    }

    @Test
    fun `messages after epoch rotation can be decrypted`() {
        val (aliceSession, bobSession) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()

        val aliceRotated =
            Session.aliceEpochRotation(
                aliceSession,
                aliceNewEk.priv,
                aliceNewEk.pub,
                bobOpk.pub,
                aliceSession.aliceFp,
                aliceSession.bobFp,
            )
        val bobRotated =
            Session.bobProcessAliceRotation(
                bobSession,
                aliceNewEk.pub,
                bobOpk.priv,
                1,
                bobSession.aliceFp,
                bobSession.bobFp,
            )

        val loc = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 5.0, ts = 1711155000L)
        // Alice's epoch is now 1; encrypt uses epoch from state.
        val (aliceAfter, ct) = Session.encryptLocation(aliceRotated, loc, aliceRotated.aliceFp, aliceRotated.bobFp)
        val (_, decrypted) = Session.decryptLocation(bobRotated, ct, aliceAfter.sendSeq, bobRotated.aliceFp, bobRotated.bobFp)

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
    }

    // ---------------------------------------------------------------------------
    // AEAD control message helpers
    // ---------------------------------------------------------------------------

    @Test
    fun `EpochRotation AEAD round-trip`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val routingToken = ByteArray(16) { (it + 1).toByte() }
        val newEkPub = ByteArray(32) { (it + 64).toByte() }
        val senderFp = ByteArray(32) { it.toByte() }
        val recipientFp = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(12) { 0xAA.toByte() }
        val ts = 1711152000L

        val ct = buildEpochRotationCt(
            rootKey = rootKey,
            epoch = 3,
            opkId = 7,
            newEkPub = newEkPub,
            ts = ts,
            nonce = nonce,
            routingToken = routingToken,
            senderFp = senderFp,
            recipientFp = recipientFp,
        )

        val plaintext = decryptEpochRotationCt(
            rootKey = rootKey,
            epoch = 3,
            nonce = nonce,
            ct = ct,
            routingToken = routingToken,
            senderFp = senderFp,
            recipientFp = recipientFp,
        )

        assertEquals(3, plaintext.epoch)
        assertEquals(7, plaintext.opkId)
        assertContentEquals(newEkPub, plaintext.newEkPub)
        assertEquals(ts, plaintext.ts)
    }

    @Test
    fun `EpochRotation AEAD fails with wrong root key`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        val routingToken = ByteArray(16) { 0x01.toByte() }
        val newEkPub = ByteArray(32)
        val senderFp = ByteArray(32)
        val recipientFp = ByteArray(32)
        val nonce = ByteArray(12)

        val ct = buildEpochRotationCt(rootKey, 1, 1, newEkPub, 1000L, nonce, routingToken, senderFp, recipientFp)

        val threw = try {
            decryptEpochRotationCt(wrongKey, 1, nonce, ct, routingToken, senderFp, recipientFp)
            false
        } catch (_: Exception) { true }
        assertTrue(threw, "Expected AEAD to fail with wrong root key")
    }

    @Test
    fun `RatchetAck AEAD round-trip`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val routingToken = ByteArray(16) { (it + 1).toByte() }
        val senderFp = ByteArray(32) { it.toByte() }
        val recipientFp = ByteArray(32) { (it + 1).toByte() }
        val newEkPub = ByteArray(32) { (it + 5).toByte() }
        val nonce = ByteArray(12) { 0xBB.toByte() }
        val ts = 1711152000L

        val ct = buildRatchetAckCt(rootKey, epochSeen = 5, ts = ts, newEkPub = newEkPub, nonce = nonce, routingToken = routingToken, senderFp = senderFp, recipientFp = recipientFp)
        val plaintext = decryptRatchetAckCt(rootKey, epochSeen = 5, nonce = nonce, ct = ct, routingToken = routingToken, senderFp = senderFp, recipientFp = recipientFp)

        assertEquals(5, plaintext.epochSeen)
        assertEquals(ts, plaintext.ts)
        assertContentEquals(newEkPub, plaintext.newEkPub)
    }

    @Test
    fun `RatchetAck AEAD fails with wrong routing token`() {
        val rootKey = ByteArray(32) { it.toByte() }
        val routingToken = ByteArray(16) { 0x01.toByte() }
        val wrongToken = ByteArray(16) { 0x02.toByte() }
        val senderFp = ByteArray(32)
        val recipientFp = ByteArray(32)
        val nonce = ByteArray(12)

        val ct = buildRatchetAckCt(rootKey, epochSeen = 1, ts = 1000L, newEkPub = null, nonce = nonce, routingToken = routingToken, senderFp = senderFp, recipientFp = recipientFp)

        val threw = try {
            decryptRatchetAckCt(rootKey, epochSeen = 1, nonce = nonce, ct = ct, routingToken = wrongToken, senderFp = senderFp, recipientFp = recipientFp)
            false
        } catch (_: Exception) { true }
        assertTrue(threw, "Expected AEAD to fail with wrong routing token")
    }
}
