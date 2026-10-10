package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ReceiveRatchetFailureTest {
    init {
        initializeE2eeTests()
    }

    /**
     * A body-AEAD failure after a valid header must advance recvSeq and the chain key
     * to prevent permanent DH desync (§8.3.1(4)). The failed message's key must NOT
     * be cached — there is no robustness benefit to caching it, since a server willing
     * to deliver a corrupted copy can equally just drop the message.
     */
    @Test
    fun bodyFailAdvancesStateWithoutCachingSeqKey() {
        val (aliceSession, bobSession) = pair()

        val (_, original) =
            Session.encryptMessage(
                aliceSession,
                MessagePlaintext.Location(1.0, 2.0, 3.0, 4L),
            )

        val sessionAad = bobSession.aliceFp + bobSession.bobFp
        val header =
            try {
                Session.decryptHeader(bobSession.headerKey, original.envelope, sessionAad)
            } catch (_: Exception) {
                Session.decryptHeader(bobSession.nextHeaderKey, original.envelope, sessionAad)
            }

        val tampered =
            original.copy(
                ct = original.ct.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() },
            )

        val bobAfterFailure = expectSoftFail(bobSession, tampered, header)

        // recvSeq must advance so the ratchet state stays consistent.
        assertTrue(bobAfterFailure.recvSeq >= 1, "recvSeq should have advanced past the failed message")

        // The seq key must NOT be cached — the message is lost, equivalent to a drop.
        assertFalse(
            bobAfterFailure.skippedMessageKeys.keys.any { it.endsWith(":${header.seq}") },
            "seq=${header.seq} key must not be cached after body-fail",
        )

        // A subsequent attempt to decrypt the (uncorrupted) original is rejected as a replay.
        assertFailsWith<ReplayException> {
            Session.decryptMessage(bobAfterFailure, original, header)
        }
    }

    /**
     * A body-AEAD failure on Alice's very first (epoch-transition) frame must leave Bob's
     * session able to keep conversing in both directions — the same outcome as if the
     * server had dropped the frame (§8.3.1(4)).
     */
    @Test
    fun bodyFailOnFirstTransitionFrameDoesNotBrickSession() {
        var (alice, bob) = pair()
        val (a1, first) = Session.encryptMessage(alice, loc(1))
        alice = a1
        bob = expectSoftFail(bob, tamper(first))

        converse(alice, bob)
    }

    /**
     * Same as above, but for a new-epoch frame in steady state (after both sides have
     * already ratcheted at least once).
     */
    @Test
    fun bodyFailOnSteadyStateNewEpochFrameDoesNotBrickSession() {
        var (alice, bob) = pair()
        repeat(2) { r ->
            val (a, m) = Session.encryptMessage(alice, loc(r))
            alice = a
            bob = Session.decryptMessage(bob, m).first
            val (b, m2) = Session.encryptMessage(bob, loc(100 + r))
            bob = b
            alice = Session.decryptMessage(alice, m2).first
        }
        // Alice has just received Bob's new DH key, so this frame opens a new epoch for Bob.
        val (a, newEpoch) = Session.encryptMessage(alice, loc(50))
        alice = a
        bob = expectSoftFail(bob, tamper(newEpoch))

        converse(alice, bob)
    }

    /**
     * An authenticated transition frame whose plaintext is malformed (bad padding) must
     * still commit the ratchet, like a body-AEAD failure, rather than leaving Bob on the
     * old epoch.
     */
    @Test
    fun malformedPlaintextOnTransitionFrameDoesNotBrickSession() {
        var (alice, bob) = pair()
        val (a1, malformed) = Session.encryptPadded(alice, ByteArray(PADDING_SIZE)) // no 0x80 marker
        alice = a1
        bob = expectSoftFail(bob, malformed)
        assertEquals(1L, bob.recvSeq)

        converse(alice, bob)
    }

    /**
     * State-equivalence invariant (§8.3.1(4)): when a new-epoch frame soft-fails, the
     * previous chain's skipped keys (up to `pn`) are cached exactly as if the frame had
     * been dropped and a later good frame had arrived. (decryptAndSort normally delivers
     * same-batch stragglers first, so this pins the state rather than a common path.)
     */
    @Test
    fun bodyFailOnNewEpochFrameStillCachesPreviousChainGap() {
        var (alice, bob) = pair()
        val (a1, m1) = Session.encryptMessage(alice, loc(1))
        val (a2, m2) = Session.encryptMessage(a1, loc(2)) // straggler: delivered late
        alice = a2
        bob = Session.decryptMessage(bob, m1).first

        val (b1, reply) = Session.encryptMessage(bob, loc(100))
        bob = b1
        alice = Session.decryptMessage(alice, reply).first

        // Alice's next frame opens a new epoch with pn = 2; Bob has only seen seq 1.
        val (_, newEpoch) = Session.encryptMessage(alice, loc(3))
        val sessionAad = bob.aliceFp + bob.bobFp
        val stragglerHeader = Session.decryptHeader(bob.headerKey, m2.envelope, sessionAad)

        bob = expectSoftFail(bob, tamper(newEpoch))

        val (_, pt) = Session.decryptMessage(bob, m2, stragglerHeader)
        assertEquals(loc(2), pt)
    }

    /**
     * An out-of-order frame that authenticates with a cached skipped key but has a
     * malformed plaintext must spend the key (soft-fail) instead of being retried forever.
     */
    @Test
    fun malformedPlaintextWithCachedKeySpendsKey() {
        var (alice, bob) = pair()
        val (a1, m1) = Session.encryptMessage(alice, loc(1))
        val (a2, malformed) = Session.encryptPadded(a1, ByteArray(PADDING_SIZE))
        val (a3, m3) = Session.encryptMessage(a2, loc(3))
        alice = a3
        bob = Session.decryptMessage(bob, m1).first
        val malformedHeader = Session.decryptHeader(bob.headerKey, malformed.envelope, bob.aliceFp + bob.bobFp)
        bob = Session.decryptMessage(bob, m3).first
        assertEquals(1, bob.skippedMessageKeys.size)

        bob = expectSoftFail(bob, malformed, malformedHeader)
        assertTrue(bob.skippedMessageKeys.isEmpty(), "cached key must be spent")
        assertFailsWith<ReplayException> { Session.decryptMessage(bob, malformed, malformedHeader) }

        converse(alice, bob)
    }

    /**
     * Sessions persisted by older builds may carry `"needsRatchet": true` (set by the buggy
     * soft-fail path). The field is gone; such a blob must still load and must NOT trigger an
     * extra send-side ratchet, so a session that hadn't sent since the bug recovers.
     */
    @Test
    fun legacyNeedsRatchetFlagIsIgnoredOnLoad() {
        var (alice, bob) = pair()
        val (a1, tampered) = Session.encryptMessage(alice, loc(1))
        alice = a1
        bob = expectSoftFail(bob, tamper(tampered))

        val blob = E2eeStore.json.encodeToString(SessionState.serializer(), bob)
        val legacyBlob = blob.replaceFirst("{", "{\"needsRatchet\":true,")
        bob = E2eeStore.json.decodeFromString(SessionState.serializer(), legacyBlob)

        converse(alice, bob)
    }

    /** A dropped new-epoch frame must heal the same way a corrupted one does. */
    @Test
    fun droppedNewEpochFrameDoesNotBrickSession() {
        var (alice, bob) = pair()
        val (a1, _) = Session.encryptMessage(alice, loc(1)) // dropped by the server
        alice = a1

        converse(alice, bob)
    }

    private fun pair(): Pair<SessionState, SessionState> {
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (msg, bobSession) = KeyExchange.bobProcessQr(qr, "Bob")
        return KeyExchange.aliceProcessInit(msg, aliceEkPriv, qr.ekPub) to bobSession
    }

    private fun loc(i: Int) = MessagePlaintext.Location(i.toDouble(), 2.0, 3.0, i.toLong())

    private fun tamper(m: EncryptedMessagePayload) = m.copy(ct = m.ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

    private fun expectSoftFail(
        state: SessionState,
        m: EncryptedMessagePayload,
        header: Session.DecryptedHeader? = null,
    ): SessionState =
        try {
            Session.decryptMessage(state, m, header)
            fail("Expected DecryptionExceptionWithState on tampered body")
        } catch (e: DecryptionExceptionWithState) {
            e.newState
        }

    /** Exchanges three round trips in each direction (Bob first) and asserts every frame decrypts. */
    private fun converse(
        aliceStart: SessionState,
        bobStart: SessionState,
    ) {
        var alice = aliceStart
        var bob = bobStart
        for (i in 0 until 3) {
            val (b, fromBob) = Session.encryptMessage(bob, loc(200 + i))
            bob = b
            val (a, ptA) = Session.decryptMessage(alice, fromBob)
            alice = a
            assertEquals(loc(200 + i), ptA, "B->A round $i")

            val (a2, fromAlice) = Session.encryptMessage(alice, loc(300 + i))
            alice = a2
            val (b2, ptB) = Session.decryptMessage(bob, fromAlice)
            bob = b2
            assertEquals(loc(300 + i), ptB, "A->B round $i")
        }
    }
}
