package net.af0.where.e2ee

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stop that fails to be queued is still owed, and poll queues it while we still aren't sharing
 * with that friend (§5.7.2). Alice sends, and Bob reads everything as one batch.
 */
class StopRetryTest {
    init {
        initializeE2eeTests()
    }

    private val clock = MockTimeProvider().also { TimeSource.setProvider(it) }

    @AfterTest
    fun restoreClock() = TimeSource.setProvider(DefaultTimeProvider)

    /** Posts fail while [failing], time out while [timingOut], and wait while [gate] is set. */
    private class GatedMailbox(val inner: MemoryMailboxClient = MemoryMailboxClient()) : MailboxClient by inner {
        var failing = false
        var timingOut = false
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            gate?.await()
            check(!failing) { "injected post failure" }
            if (timingOut) throw WallClockTimeoutCancellationException()
            inner.post(baseUrl, token, payload)
        }
    }

    private class Pair(
        val driver: FailingSqlDriver,
        val gated: GatedMailbox,
        val mailbox: MemoryMailboxClient,
        val aliceManager: E2eeManager,
        val alice: LocationClient,
        val bobManager: E2eeManager,
        val bobId: String,
    )

    private suspend fun paired(): Pair {
        val driver = FailingSqlDriver(createTestSqlDriver())
        val gated = GatedMailbox()
        val mailbox = gated.inner
        val aliceManager = testE2eeManager(driver)
        val alice = LocationClient("http://localhost", aliceManager, gated)
        val qr = aliceManager.createInvite("Alice")
        val bobManager = testE2eeManager(createTestSqlDriver())
        val bob = LocationClient("http://localhost", bobManager, mailbox)
        val (init, entry) = bobManager.processScannedQr(qr, "Bob")
        bob.postKeyExchangeInit(entry.id, qr, init)
        val pending = alice.pollPendingInvites().single()
        val bobId = requireNotNull(aliceManager.processKeyExchangeInit(pending.payload, "Bob", pending.inviteEkPub)).id
        // Bob reads Alice's transition frame, so what she sends next shares one batch.
        alice.sendKeepalive(bobId)
        bob.poll(isForeground = true, pausedFriendIds = emptySet())
        return Pair(driver, gated, mailbox, aliceManager, alice, bobManager, bobId)
    }

    /** Bob's view of Alice after one batch, and the Locations it delivered. */
    private suspend fun Pair.bobReads(): kotlin.Pair<FriendEntry, List<Double>> {
        val friend = bobManager.listFriends().single()
        val frames = mailbox.mailboxes[friend.session.recvToken.toHex()].orEmpty().filterIsInstance<EncryptedMessagePayload>()
        val result = requireNotNull(bobManager.processBatch(friend.id, friend.session.recvToken.toHex(), frames))
        return bobManager.listFriends().single() to result.decryptedLocations.map { it.lat }
    }

    private suspend fun Pair.stopOwed() = alice.isStopOwed(bobId)

    /** Alice sends a Location, then pauses Bob, and the stop fails to be queued. */
    private suspend fun Pair.failedStop() {
        alice.sendLocation(1.0, 1.0)
        driver.failOn = "INSERT INTO Outbox"
        alice.sendStoppedSharingToFriend(bobId)
    }

    @Test
    fun `a queued stop leaves nothing owed`() =
        runTest {
            val p = paired()
            p.alice.sendStoppedSharingToFriend(p.bobId)
            assertFalse(p.stopOwed())
        }

    @Test
    fun `a stop that fails to be queued is sent by the next poll`() =
        runTest {
            val p = paired()
            p.failedStop()
            assertTrue(p.stopOwed())

            p.alice.poll(pausedFriendIds = setOf(p.bobId))

            assertFalse(p.stopOwed())
            val (alice, lats) = p.bobReads()
            assertNotNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0), lats)
        }

    @Test
    fun `a stop to everyone that fails to be queued is sent by the next poll`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            p.driver.failOn = "INSERT INTO Outbox"
            p.alice.sendStoppedSharing()
            p.alice.poll(sharingEnabled = false)
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `an owed stop is not sent once we share with them again`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.alice.poll(pausedFriendIds = emptySet())
            assertNull(p.bobReads().first.stoppedAtTs)
            assertTrue(p.stopOwed())

            // A Location means sharing is on, so no stop is owed any more.
            p.alice.sendLocation(2.0, 2.0)
            assertFalse(p.stopOwed())
        }

    @Test
    fun `a Keepalive does not pay an owed stop`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.alice.sendKeepalive(p.bobId)
            assertTrue(p.stopOwed())
        }

    @Test
    fun `a retried stop drops no Location decided after the pause it pays`() =
        runTest {
            val p = paired()
            p.failedStop()
            // The user resumed sharing with Bob while a poll that read "paused" was running.
            val stamp = p.alice.sharingStamp()
            p.alice.poll(pausedFriendIds = setOf(p.bobId))
            p.alice.sendLocationToFriend(p.bobId, 2.0, 2.0, stamp = stamp)

            val (alice, lats) = p.bobReads()
            assertNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0, 2.0), lats)
        }

    @Test
    fun `a Location decided before a stop that then fails does not pay its debt`() =
        runTest {
            val p = paired()
            // A message stuck in Alice's outbox, so her next send drains it first.
            p.gated.failing = true
            runCatching { p.alice.sendKeepalive(p.bobId) }
            p.gated.failing = false
            val gate = CompletableDeferred<Unit>().also { p.gated.gate = it }

            // A heartbeat passes its check, then waits on the drain; the user pauses Bob meanwhile,
            // and that stop is cancelled while it waits for the heartbeat to finish.
            val heartbeat = launch { p.alice.sendLocationToFriend(p.bobId, 1.0, 1.0) }
            runCurrent()
            val stop = launch { p.alice.sendStoppedSharingToFriend(p.bobId) }
            runCurrent()
            stop.cancel()
            p.gated.gate = null
            gate.complete(Unit)
            heartbeat.join()

            assertTrue(p.stopOwed())
            p.alice.poll(pausedFriendIds = setOf(p.bobId))
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `a retried stop that times out on the way does not fail the poll`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.gated.timingOut = true
            p.alice.poll(pausedFriendIds = setOf(p.bobId))
            // Queued, so no longer owed; the outbox delivers it once posts get through.
            assertFalse(p.stopOwed())
            p.gated.timingOut = false
            p.alice.processOutboxes()
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `a network restore sends an owed stop`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.alice.syncNow(pausedFriendIds = setOf(p.bobId))
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `a stop to someone else does not keep a resume from paying the debt`() =
        runTest {
            val p = paired()
            p.failedStop()
            // Bob is resumed; a stop to another friend goes out before his Location is queued.
            val stamp = p.alice.sharingStamp()
            p.alice.sendStoppedSharingToFriend("someone-else")
            p.alice.sendLocationToFriend(p.bobId, 2.0, 2.0, stamp = stamp)
            assertFalse(p.stopOwed())
        }

    /** Leaves a Keepalive stuck in Alice's outbox, then holds her posts until the returned gate opens. */
    private suspend fun Pair.stuckOutboxAndGate(): CompletableDeferred<Unit> {
        gated.failing = true
        runCatching { alice.sendKeepalive(bobId) }
        gated.failing = false
        return CompletableDeferred<Unit>().also { gated.gate = it }
    }

    private fun Pair.openGate(gate: CompletableDeferred<Unit>) {
        gated.gate = null
        gate.complete(Unit)
    }

    @Test
    fun `a network restore sends an owed stop even if the outbox is still stuck`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.gated.failing = true
            runCatching { p.alice.sendKeepalive(p.bobId) }

            p.alice.syncNow(pausedFriendIds = setOf(p.bobId))

            // Queued behind the stuck Keepalive, though delivering them still fails.
            assertFalse(p.stopOwed())
            assertEquals(2, p.aliceManager.getOutbox(p.bobId).size)
            p.gated.failing = false
            p.alice.processOutboxes()
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `a sync during a stop does not send it twice`() =
        runTest {
            val p = paired()
            p.alice.enableAutomatedKeepalives = false
            val gate = p.stuckOutboxAndGate()
            val postedBefore = p.mailbox.allPosted.size

            // A heartbeat holds Bob's lock while it drains the stuck Keepalive; a sync that reads
            // Bob as paused waits for it, then the stop does.
            val heartbeat = launch { p.alice.sendLocationToFriend(p.bobId, 1.0, 1.0) }
            runCurrent()
            val sync = launch { p.alice.syncNow(pausedFriendIds = setOf(p.bobId)) }
            runCurrent()
            val stop = launch { p.alice.sendStoppedSharingToFriend(p.bobId) }
            runCurrent()
            p.openGate(gate)
            heartbeat.join()
            sync.join()
            stop.join()

            // The stuck Keepalive, the heartbeat and one stop.
            assertEquals(3, p.mailbox.allPosted.size - postedBefore)
            assertFalse(p.stopOwed())
        }

    @Test
    fun `a stop to everyone cancelled before it is queued is still owed`() =
        runTest {
            val p = paired()
            val gate = p.stuckOutboxAndGate()
            val heartbeat = launch { p.alice.sendLocationToFriend(p.bobId, 1.0, 1.0) }
            runCurrent()

            val stop = launch { p.alice.sendStoppedSharing() }
            runCurrent()
            stop.cancel()
            p.openGate(gate)
            heartbeat.join()

            assertTrue(p.stopOwed())
            p.alice.poll(sharingEnabled = false)
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `removing a paused friend who is owed a stop sends it`() =
        runTest {
            val p = paired()
            p.failedStop()
            p.alice.removeFriend(p.bobId, pausedFriendIds = setOf(p.bobId))
            p.alice.processOrphanedOutboxes()
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }

    @Test
    fun `a stop is queued at once even behind a stuck outbox`() =
        runTest {
            val p = paired()
            p.gated.failing = true
            runCatching { p.alice.sendKeepalive(p.bobId) }
            p.gated.failing = false
            val gate = CompletableDeferred<Unit>().also { p.gated.gate = it }

            val stop = launch { p.alice.sendStoppedSharingToFriend(p.bobId) }
            runCurrent()
            // Posts are held up, but the stop is already queued behind the stuck message.
            assertEquals(2, p.aliceManager.getOutbox(p.bobId).size)
            assertFalse(p.stopOwed())

            p.gated.gate = null
            gate.complete(Unit)
            stop.join()
            assertNotNull(p.bobReads().first.stoppedAtTs)
        }
}
