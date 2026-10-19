package net.af0.where.e2ee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Within one batch, whichever of a Location and a StoppedSharing the peer sent later decides
 * whether they are sharing. "Later" is send order, not the self-reported ts: that has one-second
 * resolution, so a stop right after a Location used to tie in the Location's favour and leave the
 * friend shown as sharing, and it can go backwards with the peer's clock.
 */
class SharingStateOrderTest {
    init {
        initializeE2eeTests()
    }

    private val t = 1_767_225_600L

    private fun loc(ts: Long) = MessagePlaintext.Location(lat = 1.0, lng = 2.0, acc = 3.0, ts = ts)

    /** Bob sends [frames] in order; Alice reads them as one batch. Returns Alice's view of Bob. */
    private suspend fun aliceAfter(vararg frames: MessagePlaintext): FriendEntry {
        val alice = testE2eeManager(createTestSqlDriver())
        val bob = testE2eeManager(createTestSqlDriver())
        val qr = alice.createInvite("Alice")
        val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
        val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
        val sent = frames.map { bob.encryptAndAdvance(bobEntry.id, it).first }
        requireNotNull(alice.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), sent))
        return alice.listFriends().single()
    }

    @Test
    fun `a stop in the same second as the Location before it wins`() =
        runTest {
            assertEquals(t, aliceAfter(loc(t), MessagePlaintext.StoppedSharing(ts = t)).stoppedAtTs)
        }

    @Test
    fun `a resume in the same second as the stop before it wins`() =
        runTest {
            assertNull(aliceAfter(MessagePlaintext.StoppedSharing(ts = t), loc(t)).stoppedAtTs)
        }

    @Test
    fun `send order spans a DH ratchet within the batch`() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice")
            val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
            val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
            // Bob's Location in his bootstrap epoch; then he reads Alice's first frame and ratchets,
            // so his stop is seq 1 of a new epoch. It is dated earlier, so only send order puts it last.
            val loc = bob.encryptAndAdvance(bobEntry.id, loc(t)).first
            val fromAlice = alice.encryptAndAdvance(aliceEntry.id, MessagePlaintext.Keepalive()).first
            requireNotNull(bob.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(fromAlice)))
            val stop = bob.encryptAndAdvance(bobEntry.id, MessagePlaintext.StoppedSharing(ts = t - 5)).first
            // The premise: the stop goes to the mailbox Alice still polls, with the Location.
            val bobSession = requireNotNull(bob.getFriend(bobEntry.id)).session
            assertEquals(aliceEntry.session.recvToken.toHex(), bobSession.prevSendToken.toHex())

            // Delivered stop first: the batch is still processed in send order.
            requireNotNull(alice.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), listOf(stop, loc)))
            assertEquals(t - 5, alice.listFriends().single().stoppedAtTs)
        }

    @Test
    fun `send order wins when the peer's clock stepped back`() =
        runTest {
            assertEquals(t - 5, aliceAfter(loc(t), MessagePlaintext.StoppedSharing(ts = t - 5)).stoppedAtTs)
            assertNull(aliceAfter(MessagePlaintext.StoppedSharing(ts = t), loc(t - 5)).stoppedAtTs)
        }

    /** Holds every poll until [gate] completes, so a test can keep a friend's mutex taken. */
    private class GatedMailboxClient(private val inner: MailboxClient) : MailboxClient by inner {
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            gate?.await()
            return inner.poll(baseUrl, token)
        }
    }

    @Test
    fun `a Location that read the sharing state before a stop but queued behind it is not sent`() =
        runTest {
            val mailbox = GatedMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val alice = LocationClient("http://localhost", aliceManager, mailbox)
            val bobManager = testE2eeManager(createTestSqlDriver())
            val bob = LocationClient("http://localhost", bobManager, mailbox)
            val qr = aliceManager.createInvite("Alice")
            val (init, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            bob.postKeyExchangeInit(bobEntry.id, qr, init)
            val pending = alice.pollPendingInvites()
            requireNotNull(aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))

            // A poll holds Alice's mutex for Bob. The stop queues on it first; then a Location
            // call reads Bob as active and queues behind the stop.
            val gate = CompletableDeferred<Unit>()
            mailbox.gate = gate
            val polling = launch { alice.poll(isForeground = true, pausedFriendIds = emptySet()) }
            runCurrent()
            val stopping = launch { alice.sendStoppedSharing() }
            runCurrent()
            val locating = launch { alice.sendLocation(1.0, 2.0) }
            runCurrent()
            mailbox.gate = null
            gate.complete(Unit)
            advanceUntilIdle()
            listOf(polling, stopping, locating).forEach { it.join() }

            alice.syncNow()
            bob.poll(isForeground = true, pausedFriendIds = emptySet())
            assertNotNull(bobManager.listFriends().single().stoppedAtTs, "the Location after the stop resumed sharing")

            // A Location sent after the stop (a real resume) still goes out.
            alice.sendLocation(3.0, 4.0)
            alice.syncNow()
            assertEquals(listOf(3.0), bob.poll(isForeground = true, pausedFriendIds = emptySet()).map { it.lat })
            assertNull(bobManager.listFriends().single().stoppedAtTs)
        }
}
