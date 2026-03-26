package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private fun makeIdentity(): IdentityKeys = IdentityKeys(ik = generateX25519KeyPair(), sigIk = generateEd25519KeyPair())

    data class ExchangeResult(
        val aliceSession: SessionState,
        val bobSession: SessionState,
        val aliceFp: ByteArray,
        val bobFp: ByteArray,
    )

    /** Full key exchange: Alice sends, Bob receives. */
    private fun exchangeKeys(): ExchangeResult {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(msg, alice, aliceEkPriv, aliceFp, bobFp)

        return ExchangeResult(aliceSession, bobSession, aliceFp, bobFp)
    }

    // ---------------------------------------------------------------------------
    // Encrypt / decrypt round-trip
    // ---------------------------------------------------------------------------

    @Test
    fun `encrypt and decrypt location round-trip`() {
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()
        val loc = LocationPlaintext(lat = 37.7749, lng = -122.4194, acc = 15.0, ts = 1711152000L)

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceFp, bobFp)
        val (_, decrypted) = Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, aliceFp, bobFp)!!

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
        assertEquals(loc.acc, decrypted.acc)
        assertEquals(loc.ts, decrypted.ts)
    }

    @Test
    fun `seq advances on each encrypt`() {
        val (aliceSession, _, aliceFp, bobFp) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, _) = Session.encryptLocation(aliceSession, loc, aliceFp, bobFp)
        val (s2, _) = Session.encryptLocation(s1, loc, aliceFp, bobFp)
        val (s3, _) = Session.encryptLocation(s2, loc, aliceFp, bobFp)

        assertEquals(1L, s1.sendSeq)
        assertEquals(2L, s2.sendSeq)
        assertEquals(3L, s3.sendSeq)
    }

    @Test
    fun `each message produces a different ciphertext`() {
        val (aliceSession, _, aliceFp, bobFp) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        val (s1, ct1) = Session.encryptLocation(aliceSession, loc, aliceFp, bobFp)
        val (_, ct2) = Session.encryptLocation(s1, loc, aliceFp, bobFp)

        assertNotEquals(ct1.toList(), ct2.toList())
    }

    @Test
    fun `multiple sequential messages decrypt correctly`() {
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()
        val locs =
            (1..5).map { i ->
                LocationPlaintext(lat = i.toDouble(), lng = i.toDouble(), acc = 1.0, ts = i.toLong())
            }

        var aSess = aliceSession
        var bSess = bobSession
        for (loc in locs) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aliceFp, bobFp)
            val (newB, dec) = Session.decryptLocation(bSess, ct, newA.sendSeq, aliceFp, bobFp)!!
            assertEquals(loc.lat, dec.lat)
            aSess = newA
            bSess = newB
        }
    }

    // ---------------------------------------------------------------------------
    // Replay rejection
    // ---------------------------------------------------------------------------

    @Test
    fun `replay is dropped silently`() {
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()
        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)

        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceFp, bobFp)
        val seq = aliceNew.sendSeq

        val (bobNew, _) = Session.decryptLocation(bobSession, ct, seq, aliceFp, bobFp)!!
        // Second delivery of the same seq must be dropped.
        val result = Session.decryptLocation(bobNew, ct, seq, aliceFp, bobFp)
        assertNull(result, "Replay should be dropped (return null)")
    }

    @Test
    fun `frame with lower seq than recvSeq is dropped`() {
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()
        val loc = LocationPlaintext(0.0, 0.0, 0.0, 0L)

        var aSess = aliceSession
        var bSess = bobSession
        val cts = mutableListOf<Pair<Long, ByteArray>>()
        repeat(3) {
            val (newA, ct) = Session.encryptLocation(aSess, loc, aliceFp, bobFp)
            cts += newA.sendSeq to ct
            aSess = newA
        }

        // Deliver in order first.
        for ((seq, ct) in cts) {
            val res = Session.decryptLocation(bSess, ct, seq, aliceFp, bobFp)
            assertNotNull(res)
            bSess = res.first
        }

        // Re-deliver any of them — all should be dropped.
        for ((seq, ct) in cts) {
            assertNull(Session.decryptLocation(bSess, ct, seq, aliceFp, bobFp))
        }
    }

    // ---------------------------------------------------------------------------
    // AAD integrity
    // ---------------------------------------------------------------------------

    @Test
    fun `ciphertext is bound to sender fingerprint`() {
        val alice = makeIdentity()
        val bob = makeIdentity()
        val eve = makeIdentity()
        val aliceFp = fingerprint(alice.ik.pub, alice.sigIk.pub)
        val bobFp = fingerprint(bob.ik.pub, bob.sigIk.pub)
        val eveFp = fingerprint(eve.ik.pub, eve.sigIk.pub)

        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload(alice, "Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, bob, aliceFp, bobFp)
        val aliceSession = KeyExchange.aliceProcessInit(msg, alice, aliceEkPriv, aliceFp, bobFp)

        val loc = LocationPlaintext(1.0, 2.0, 3.0, 4L)
        val (aliceNew, ct) = Session.encryptLocation(aliceSession, loc, aliceFp, bobFp)

        // Decrypting with wrong sender fingerprint must fail.
        val threw =
            try {
                Session.decryptLocation(bobSession, ct, aliceNew.sendSeq, eveFp, bobFp)
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
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()

        val aliceRotated =
            Session.aliceEpochRotation(
                state = aliceSession,
                aliceNewEkPriv = aliceNewEk.priv,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPub = bobOpk.pub,
                senderFp = aliceFp,
                recipientFp = bobFp,
            )
        val bobRotated =
            Session.bobProcessEpochRotation(
                state = bobSession,
                aliceNewEkPub = aliceNewEk.pub,
                bobOpkPriv = bobOpk.priv,
                newEpoch = 1,
                senderFp = aliceFp,
                recipientFp = bobFp,
            )

        assertEquals(1, aliceRotated.epoch)
        assertEquals(1, bobRotated.epoch)
        assertContentEquals(aliceRotated.routingToken, bobRotated.routingToken)
        assertNotEquals(aliceSession.routingToken.toList(), aliceRotated.routingToken.toList())
        assertContentEquals(aliceRotated.rootKey, bobRotated.rootKey)
        assertContentEquals(aliceRotated.sendChainKey, bobRotated.sendChainKey)
    }

    @Test
    fun `messages after epoch rotation can be decrypted`() {
        val (aliceSession, bobSession, aliceFp, bobFp) = exchangeKeys()

        val bobOpk = generateX25519KeyPair()
        val aliceNewEk = generateX25519KeyPair()

        val aliceRotated =
            Session.aliceEpochRotation(
                aliceSession,
                aliceNewEk.priv,
                aliceNewEk.pub,
                bobOpk.pub,
                aliceFp,
                bobFp,
            )
        val bobRotated =
            Session.bobProcessEpochRotation(
                bobSession,
                aliceNewEk.pub,
                bobOpk.priv,
                1,
                aliceFp,
                bobFp,
            )

        val loc = LocationPlaintext(lat = 48.8566, lng = 2.3522, acc = 5.0, ts = 1711155000L)
        // Alice's epoch is now 1; encrypt uses epoch from state.
        val (aliceAfter, ct) = Session.encryptLocation(aliceRotated, loc, aliceFp, bobFp)
        val (_, decrypted) = Session.decryptLocation(bobRotated, ct, aliceAfter.sendSeq, aliceFp, bobFp)!!

        assertEquals(loc.lat, decrypted.lat)
        assertEquals(loc.lng, decrypted.lng)
    }

    // ---------------------------------------------------------------------------
    // Signed blob helpers
    // ---------------------------------------------------------------------------

    @Test
    fun `epochRotationSignedBlob is 116 bytes`() {
        val blob =
            Session.epochRotationSignedBlob(
                epoch = 43,
                opkId = 101,
                newEkPub = ByteArray(32),
                ts = 1711152000L,
                senderFp = ByteArray(32),
                recipientFp = ByteArray(32),
            )
        assertEquals(116, blob.size)
    }

    @Test
    fun `ratchetAckSignedBlob is 80 bytes`() {
        val blob =
            Session.ratchetAckSignedBlob(
                epochSeen = 43,
                ts = 1711152000L,
                senderFp = ByteArray(32),
                recipientFp = ByteArray(32),
            )
        assertEquals(80, blob.size)
    }

    @Test
    fun `signed blobs can be signed and verified`() {
        val identity = makeIdentity()
        val blob =
            Session.epochRotationSignedBlob(
                epoch = 5,
                opkId = 99,
                newEkPub = ByteArray(32) { it.toByte() },
                ts = 1711152000L,
                senderFp = ByteArray(32) { 0xAA.toByte() },
                recipientFp = ByteArray(32) { 0xBB.toByte() },
            )
        val sig = ed25519Sign(identity.sigIk.priv, blob)
        assertTrue(ed25519Verify(identity.sigIk.pub, blob, sig))
    }
}
