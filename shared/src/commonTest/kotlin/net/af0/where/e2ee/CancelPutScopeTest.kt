package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What a ratchet's CancelPut deletes (ProtocolCore.Effect.CancelPut): the epoch's transition frame,
 * which the peer's ratchet proves it read, and the earlier frames posted before it; nothing queued
 * behind it. Only the transition frame is addressed to the previous send token apart from the
 * previous epoch's own frames; everything after it, a StoppedSharing included, goes to the current
 * one.
 */
class CancelPutScopeTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `an ordinary ratchet cancels the transition frame and keeps what is queued behind it`() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice")
            val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
            val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
            val bobId = bobEntry.id
            val aliceId = aliceEntry.id
            bob.removeFromOutbox(bobId, init.msgId) // the KeyExchangeInit, delivered above

            suspend fun recvToken(
                m: E2eeManager,
                id: String,
            ) = requireNotNull(m.getFriend(id)).session.recvToken.toHex()

            // Bob, offline, has queued a Location in his first epoch when Alice's first frame
            // arrives. That ratchet proves nothing about his frames, so it cancels nothing.
            val early = bob.encryptAndAdvance(bobId, MessagePlaintext.Location(7.0, 8.0, 9.0, 1L)).first
            val a1 = alice.encryptAndAdvance(aliceId, MessagePlaintext.Keepalive()).first
            requireNotNull(bob.processBatch(bobId, recvToken(bob, bobId), listOf(a1)))
            assertEquals(listOf(early.msgId), bob.getOutbox(bobId).map { it.msgId }, "his first ratchet cancels nothing")

            // Then, still offline, he queues three more: the first of his new epoch (the transition
            // frame), a Location and a StoppedSharing behind it.
            val transition = bob.encryptAndAdvance(bobId, MessagePlaintext.Location(1.0, 2.0, 3.0, 2L)).first
            val location = bob.encryptAndAdvance(bobId, MessagePlaintext.Location(4.0, 5.0, 6.0, 3L)).first
            val stop = bob.encryptAndAdvance(bobId, MessagePlaintext.StoppedSharing(ts = 4L)).first
            val session = requireNotNull(bob.getFriend(bobId)).session
            assertNotEquals(session.prevSendToken, session.sendToken, "Bob is past his bootstrap token")
            val queued = bob.getOutbox(bobId)
            assertEquals(listOf(early.msgId, transition.msgId, location.msgId, stop.msgId), queued.map { it.msgId })
            assertEquals(
                listOf(session.prevSendToken.toHex(), session.prevSendToken.toHex(), session.sendToken.toHex(), session.sendToken.toHex()),
                queued.map { it.token },
                "the first epoch's frames and the transition frame share the previous send token; nothing else does",
            )

            // The outbox drains in order, so by the time Alice can read the transition frame she
            // has the earlier one too. She answers; her answer carries a new key, so Bob ratchets
            // again, and that is the proof the transition frame was read.
            val atAlice = requireNotNull(alice.processBatch(aliceId, recvToken(alice, aliceId), listOf(early, transition)))
            assertEquals(listOf(7.0, 1.0), atAlice.decryptedLocations.map { it.lat })
            val answer = alice.encryptAndAdvance(aliceId, MessagePlaintext.Keepalive()).first
            val received = requireNotNull(bob.processBatch(bobId, recvToken(bob, bobId), listOf(answer)))
            assertEquals(true, received.hadDhRatchet, "Bob ratcheted on Alice's answer")

            assertEquals(
                listOf(location.msgId, stop.msgId),
                bob.getOutbox(bobId).map { it.msgId },
                "the frames Alice has are cancelled; the Location and the StoppedSharing still have to go",
            )
        }
}
