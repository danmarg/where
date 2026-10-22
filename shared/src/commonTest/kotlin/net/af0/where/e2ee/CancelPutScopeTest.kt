package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What a ratchet's CancelPut deletes (ProtocolCore.Effect.CancelPut): the epoch's transition frame,
 * which the peer's ratchet proves it read, and nothing queued behind it. Only that frame is
 * addressed to the previous send token; everything after it, a StoppedSharing included, goes to
 * the current one.
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
            bob.removeFromOutbox(bobId, init.msgId) // the KeyExchangeInit, delivered above
            val aliceId = aliceEntry.id

            suspend fun recvToken(
                m: E2eeManager,
                id: String,
            ) = requireNotNull(m.getFriend(id)).session.recvToken.toHex()

            // Bob's first ratchet (on Alice's first frame) proves nothing and cancels nothing.
            val a1 = alice.encryptAndAdvance(aliceId, MessagePlaintext.Keepalive()).first
            requireNotNull(bob.processBatch(bobId, recvToken(bob, bobId), listOf(a1)))

            // Bob, offline, queues three: the first of his new epoch (the transition frame), then a
            // Location and a StoppedSharing behind it.
            val transition = bob.encryptAndAdvance(bobId, MessagePlaintext.Location(1.0, 2.0, 3.0, 1L)).first
            val location = bob.encryptAndAdvance(bobId, MessagePlaintext.Location(4.0, 5.0, 6.0, 2L)).first
            val stop = bob.encryptAndAdvance(bobId, MessagePlaintext.StoppedSharing(ts = 3L)).first
            val session = requireNotNull(bob.getFriend(bobId)).session
            assertNotEquals(session.prevSendToken, session.sendToken, "Bob is past his bootstrap token")
            val queued = bob.getOutbox(bobId)
            assertEquals(listOf(transition.msgId, location.msgId, stop.msgId), queued.map { it.msgId })
            assertEquals(
                listOf(session.prevSendToken.toHex(), session.sendToken.toHex(), session.sendToken.toHex()),
                queued.map { it.token },
                "only the transition frame is addressed to the previous send token",
            )

            // Alice reads the transition frame and answers; her answer carries a new key, so Bob
            // ratchets again, and that is the proof the transition frame was read.
            requireNotNull(alice.processBatch(aliceId, recvToken(alice, aliceId), listOf(transition)))
            val answer = alice.encryptAndAdvance(aliceId, MessagePlaintext.Keepalive()).first
            val received = requireNotNull(bob.processBatch(bobId, recvToken(bob, bobId), listOf(answer)))
            assertEquals(true, received.hadDhRatchet, "Bob ratcheted on Alice's answer")

            assertEquals(
                listOf(location.msgId, stop.msgId),
                bob.getOutbox(bobId).map { it.msgId },
                "the transition frame is cancelled; the Location and the StoppedSharing still have to go",
            )
        }
}
