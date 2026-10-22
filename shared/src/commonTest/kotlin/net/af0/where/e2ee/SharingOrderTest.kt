package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The receiver takes "sharing" or "stopped" from whichever of our Locations and stops came last
 * (by ts within one batch, a Location winning a tie). The sender keeps that right on its own
 * (§5.7.2), so these pass against the receiver as it was before: Alice sends, and Bob reads
 * everything as one batch with the clock frozen.
 */
class SharingOrderTest {
    init {
        initializeE2eeTests()
    }

    private val clock = MockTimeProvider().also { TimeSource.setProvider(it) }

    @AfterTest
    fun restoreClock() = TimeSource.setProvider(DefaultTimeProvider)

    private class Pair(
        val mailbox: MemoryMailboxClient,
        val alice: LocationClient,
        val bob: LocationClient,
        val bobManager: E2eeManager,
        val bobId: String,
    )

    private suspend fun paired(): Pair {
        val mailbox = MemoryMailboxClient()
        val aliceManager = testE2eeManager(createTestSqlDriver())
        val alice = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")
        val bobManager = testE2eeManager(createTestSqlDriver())
        val bob = LocationClient("http://localhost", bobManager, mailbox)
        val (init, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bob.postKeyExchangeInit(bobEntry.id, qr, init)
        val pending = alice.pollPendingInvites().single()
        val bobId = requireNotNull(aliceManager.processKeyExchangeInit(pending.payload, "Bob", pending.inviteEkPub)).id
        // Alice's first frame is her epoch's transition frame, on the mailbox Bob polls before it;
        // once he has read it, what she sends next shares one mailbox and so one batch.
        alice.sendKeepalive(bobId)
        bob.poll(isForeground = true, pausedFriendIds = emptySet())
        return Pair(mailbox, alice, bob, bobManager, bobId)
    }

    /** Bob's view of Alice after one batch, and the Locations it delivered. */
    private suspend fun Pair.bobReads(): kotlin.Pair<FriendEntry, List<Double>> {
        val friend = bobManager.listFriends().single()
        val frames = mailbox.mailboxes[friend.session.recvToken.toHex()].orEmpty().filterIsInstance<EncryptedMessagePayload>()
        val result = requireNotNull(bobManager.processBatch(friend.id, friend.session.recvToken.toHex(), frames))
        return bobManager.listFriends().single() to result.decryptedLocations.map { it.lat }
    }

    @Test
    fun `a stop in the same second as the Location before it wins`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            p.alice.sendStoppedSharingToFriend(p.bobId)
            val (alice, lats) = p.bobReads()
            assertNotNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0), lats)
        }

    @Test
    fun `a resume in the same second as the stop before it wins`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            p.alice.sendStoppedSharingToFriend(p.bobId)
            p.alice.sendLocation(2.0, 2.0)
            val (alice, lats) = p.bobReads()
            assertNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0, 2.0), lats)
        }

    @Test
    fun `a Location decided on before a stop is not sent after it`() =
        runTest {
            val p = paired()
            // The caller read "sharing" (and took its stamp), then the user paused this friend.
            val stamp = p.alice.sharingStamp()
            p.alice.sendStoppedSharingToFriend(p.bobId)
            p.alice.sendLocation(1.0, 1.0, stamp = stamp)
            p.alice.sendLocationToFriend(p.bobId, 2.0, 2.0, stamp = stamp)
            val (alice, lats) = p.bobReads()
            assertNotNull(alice.stoppedAtTs)
            assertTrue(lats.isEmpty())
        }

    @Test
    fun `a stop is dated after the Location before it even if the clock stepped back`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            clock.advanceMillis(-10_000)
            p.alice.sendStoppedSharingToFriend(p.bobId)
            val (alice, lats) = p.bobReads()
            assertNotNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0), lats)
        }

    @Test
    fun `a Keepalive between a stop and a resume does not date the resume before the stop`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            p.alice.sendStoppedSharingToFriend(p.bobId)
            p.alice.sendKeepalive(p.bobId)
            p.alice.sendLocation(2.0, 2.0)
            val (alice, lats) = p.bobReads()
            assertNull(alice.stoppedAtTs)
            assertEquals(listOf(1.0, 2.0), lats)
        }

    @Test
    fun `a Location decided on before a stop to everyone is not sent after it`() =
        runTest {
            val p = paired()
            val stamp = p.alice.sharingStamp()
            p.alice.sendStoppedSharing()
            p.alice.sendLocation(1.0, 1.0, stamp = stamp)
            val (alice, lats) = p.bobReads()
            assertNotNull(alice.stoppedAtTs)
            assertTrue(lats.isEmpty())
        }
}
