package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A counterexample found by model checking the protocol (ScheduleInterleavingPropertyTest finds it
 * too), as a test.
 *
 *   1. Alice sends and posts her first frame (her epoch's transition frame, on the bootstrap token).
 *   2. Bob queues a frame of his own, still in the bootstrap epoch; its PUT hasn't happened yet.
 *   3. Bob polls and processes Alice's frame. Alice's key is new, so Bob ratchets.
 *
 * Step 3 used to cancel Bob's outbox entries for his previous send token (§5.4.3), on the theory
 * that a ratchet proves the peer read our transition frame. Bob's first ratchet proves nothing of
 * the kind: Alice ratcheted on her own in aliceProcessInit, before reading anything of Bob's, and
 * Bob's previous send token is still his bootstrap token, the one Alice polls. The queued frame
 * (a Location here; a StoppedSharing is the case that matters) was deleted unsent.
 */
class BootstrapOutboxCancelTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `the core cancels nothing on Bob's first ratchet`() {
        val rngA = DeterministicRng("bootstrap-cancel/alice")
        val rngB = DeterministicRng("bootstrap-cancel/bob")
        val now = 1_767_225_600_000L
        val inviting = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.CreateInvite("Alice"), now, rngA).state
        val qr = (inviting as ProtocolCore.State.Inviting).qr
        val scan = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.ScanQr(qr, "Bob"), now, rngB)
        val init = (scan.effects.single() as ProtocolCore.Effect.Put).payload as KeyExchangeInitPayload
        val alice = ProtocolCore.step(inviting, ProtocolCore.Event.ProcessInit(init), now, rngA).state

        val a1 = ProtocolCore.step(alice, ProtocolCore.Event.Send(MessagePlaintext.Keepalive()), now, rngA)
        val b1 = ProtocolCore.step(scan.state, ProtocolCore.Event.Send(MessagePlaintext.Keepalive()), now, rngB)

        val a1Frame = (a1.effects.single() as ProtocolCore.Effect.Put).payload
        val bobMailbox = (b1.state as ProtocolCore.State.Paired).session.recvToken.toHex()
        val received = ProtocolCore.step(b1.state, ProtocolCore.Event.Receive(bobMailbox, listOf(a1Frame)), now, rngB)
        assertTrue(received.report!!.dhRatchet, "Bob's first receive is a DH ratchet")
        assertTrue(
            received.effects.none { it is ProtocolCore.Effect.CancelPut },
            "Bob has no transition frame to retire on his first ratchet: ${received.effects}",
        )
    }

    private class Paired(
        val mailbox: ChaosMailboxClient,
        val aliceManager: E2eeManager,
        val aliceClient: LocationClient,
        val bobManager: E2eeManager,
        val bobClient: LocationClient,
        val bobFriendId: String,
    )

    private suspend fun pair(): Paired {
        val mailbox = ChaosMailboxClient(MemoryMailboxClient())
        val aliceManager = testE2eeManager(createTestSqlDriver())
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val bobManager = testE2eeManager(createTestSqlDriver())
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        val qr = aliceManager.createInvite("Alice")
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
        val pending = aliceClient.pollPendingInvites()
        requireNotNull(aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))
        return Paired(mailbox, aliceManager, aliceClient, bobManager, bobClient, bobEntry.id)
    }

    @Test
    fun `a frame Bob queued before Alice's first one still arrives`() =
        runTest {
            val p = pair()

            // 1. Alice's first frame is posted.
            p.aliceClient.sendLocation(37.0, -122.0, emptySet())

            // 2. Bob's frame is queued but its PUT fails (offline).
            p.mailbox.failNextPost = true
            try {
                p.bobClient.sendLocation(40.0, -74.0, emptySet())
            } catch (_: Exception) {
                // expected: the frame stays in Bob's outbox
            }
            assertEquals(1, p.bobManager.getOutbox(p.bobFriendId).size)

            // 3. Bob reads Alice's frame and ratchets; his queued frame must survive and drain.
            // (poll() flushes the outbox first; Bob is still offline for that PUT.)
            p.mailbox.failNextPost = true
            p.bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            p.bobClient.syncNow()

            val atAlice = p.aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(listOf(40.0), atAlice.map { it.lat }, "Bob's queued frame was dropped")
        }

    @Test
    fun `a StoppedSharing Bob queued behind a stuck frame still arrives`() =
        runTest {
            val mockTime = MockTimeProvider()
            TimeSource.setProvider(mockTime)
            try {
                stoppedSharingBehindStuckFrame(mockTime)
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    private suspend fun stoppedSharingBehindStuckFrame(mockTime: MockTimeProvider) {
        val p = pair()
        p.aliceClient.sendLocation(37.0, -122.0, emptySet())

        // Bob goes offline: a Location gets stuck, and the StoppedSharing queues behind it (#369).
        // A second apart: the receiver orders the two by their (second-resolution) timestamps.
        p.mailbox.failPostProbability = 1.0
        try {
            p.bobClient.sendLocation(40.0, -74.0, emptySet())
        } catch (_: Exception) {
            // expected: stuck in the outbox
        }
        mockTime.advanceSeconds(1)
        p.bobClient.sendStoppedSharingToFriend(p.bobFriendId)
        assertEquals(2, p.bobManager.getOutbox(p.bobFriendId).size)

        // Still offline, Bob reads Alice's frame and ratchets. Then he reconnects.
        p.bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
        p.mailbox.failPostProbability = 0.0
        p.bobClient.syncNow()

        val atAlice = p.aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
        assertEquals(listOf(40.0), atAlice.map { it.lat }, "Bob's stuck Location was dropped")
        assertNotNull(p.aliceManager.listFriends().single().stoppedAtTs, "Bob's StoppedSharing was dropped")
    }
}
