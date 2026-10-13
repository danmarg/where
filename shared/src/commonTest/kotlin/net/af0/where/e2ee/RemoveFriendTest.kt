package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LocationClient.removeFriend: removing a friend we're sharing with sends them StoppedSharing, so
 * their app shows us as stopped instead of leaving our last pin to go stale. The friend's outbox
 * outlives the friend row and is delivered (or aged out) by processOutboxes().
 */
class RemoveFriendTest {
    init {
        initializeE2eeTests()
    }

    private data class Paired(
        val aliceClient: LocationClient,
        val aliceManager: E2eeManager,
        val bobClient: LocationClient,
        val bobManager: E2eeManager,
        val bobFriendId: String,
        val chaosMailbox: ChaosMailboxClient,
    )

    private suspend fun pairedClients(): Paired {
        val chaosMailbox = ChaosMailboxClient(MemoryMailboxClient())

        val aliceManager = testE2eeManager(createTestSqlDriver())
        val aliceClient = LocationClient("http://localhost", aliceManager, chaosMailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobManager = testE2eeManager(createTestSqlDriver())
        val bobClient = LocationClient("http://localhost", bobManager, chaosMailbox)

        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        val aliceFriend = aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)
        requireNotNull(aliceFriend) { "handshake setup failed" }

        return Paired(aliceClient, aliceManager, bobClient, bobManager, aliceFriend.id, chaosMailbox)
    }

    private suspend fun Paired.bobsViewOfAlice(): FriendEntry = bobManager.listFriends().single()

    /** Counts posts that reach the mailbox, so a test can tell "dropped" from "delivered but unread". */
    private class CountingMailboxClient(private val inner: MailboxClient) : MailboxClient by inner {
        var posts = 0

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            inner.post(baseUrl, token, payload)
            posts++
        }
    }

    @Test
    fun `removing a friend we share with sends them StoppedSharing`() =
        runTest {
            val p = pairedClients()
            p.aliceClient.sendLocation(37.0, -122.0)
            assertEquals(1, p.bobClient.poll().size)
            assertNull(p.bobsViewOfAlice().stoppedAtTs)

            p.aliceClient.removeFriend(p.bobFriendId)
            assertTrue(p.aliceManager.listFriends().isEmpty())

            p.aliceClient.processOutboxes()
            assertTrue(p.aliceManager.getOrphanedOutbox().isEmpty())
            p.bobClient.poll()
            assertNotNull(p.bobsViewOfAlice().stoppedAtTs)
        }

    @Test
    fun `removal while offline is delivered once back online`() =
        runTest {
            val p = pairedClients()
            p.chaosMailbox.failPostProbability = 1.0

            p.aliceClient.removeFriend(p.bobFriendId)
            p.aliceClient.processOutboxes()

            assertTrue(p.aliceManager.listFriends().isEmpty())
            assertEquals(1, p.aliceManager.getOrphanedOutbox().size, "the StoppedSharing must outlive the friend row")

            p.chaosMailbox.failPostProbability = 0.0
            p.aliceClient.processOutboxes()

            assertTrue(p.aliceManager.getOrphanedOutbox().isEmpty())
            p.bobClient.poll()
            assertNotNull(p.bobsViewOfAlice().stoppedAtTs)
        }

    @Test
    fun `a message stuck before removal is delivered ahead of the StoppedSharing`() =
        runTest {
            val mockTime = MockTimeProvider()
            TimeSource.setProvider(mockTime)
            try {
                val p = pairedClients()
                // Bob sends, Alice receives: Alice's next message is a DH-ratchet transition, posted
                // to the previous token. Bob only learns the new token from it, so if it were
                // dropped the StoppedSharing behind it (posted to the new token) would never be read.
                p.bobClient.sendLocation(1.0, 2.0)
                assertEquals(1, p.aliceClient.poll().size)

                p.chaosMailbox.failPostProbability = 1.0
                try {
                    p.aliceClient.sendLocation(37.0, -122.0)
                } catch (_: Exception) {
                    // expected - stuck in the outbox
                }
                // Same second as the Location, which wins ties on the receiving side: the farewell
                // must still be dated after it.
                p.aliceClient.removeFriend(p.bobFriendId)
                assertEquals(2, p.aliceManager.getOrphanedOutbox().size)

                p.chaosMailbox.failPostProbability = 0.0
                p.aliceClient.processOutboxes()

                val locations = p.bobClient.poll() + p.bobClient.poll()
                assertEquals(listOf(37.0), locations.map { it.lat })
                assertNotNull(p.bobsViewOfAlice().stoppedAtTs)
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    @Test
    fun `removing a paused friend sends nothing new`() =
        runTest {
            val p = pairedClients()

            p.aliceClient.removeFriend(p.bobFriendId, pausedFriendIds = setOf(p.bobFriendId))

            assertTrue(p.aliceManager.listFriends().isEmpty())
            assertTrue(p.aliceManager.getOrphanedOutbox().isEmpty())
            p.aliceClient.processOutboxes()
            p.bobClient.poll()
            assertNull(p.bobsViewOfAlice().stoppedAtTs)
        }

    @Test
    fun `removing a friend with sharing off sends nothing new`() =
        runTest {
            val p = pairedClients()

            p.aliceClient.removeFriend(p.bobFriendId, sharingEnabled = false)

            p.aliceClient.processOutboxes()
            p.bobClient.poll()
            assertNull(p.bobsViewOfAlice().stoppedAtTs)
        }

    @Test
    fun `removing a not-yet-confirmed friend drops their queued KeyExchangeInit`() =
        runTest {
            val chaos = ChaosMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val aliceClient = LocationClient("http://localhost", aliceManager, chaos)
            val qr = aliceManager.createInvite("Alice")
            val bobManager = testE2eeManager(createTestSqlDriver())
            val bobClient = LocationClient("http://localhost", bobManager, chaos)

            // Bob scans while offline: the init stays in his outbox.
            val (initPayload, aliceEntry) = bobManager.processScannedQr(qr, "Bob")
            chaos.failPostProbability = 1.0
            runCatching { bobClient.postKeyExchangeInit(aliceEntry.id, qr, initPayload) }
            assertEquals(1, bobManager.getOutbox(aliceEntry.id).size)

            bobClient.removeFriend(aliceEntry.id)
            assertTrue(bobManager.listFriends().isEmpty())
            assertTrue(bobManager.getOrphanedOutbox().isEmpty(), "the init must not be posted after removal")

            chaos.failPostProbability = 0.0
            bobClient.processOutboxes()
            assertTrue(aliceClient.pollPendingInvites().isEmpty(), "Alice must not get a pairing request")
        }

    @Test
    fun `the scanner removing a friend before hearing back still sends StoppedSharing once the init is through`() =
        runTest {
            val chaos = ChaosMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val aliceClient = LocationClient("http://localhost", aliceManager, chaos)
            val qr = aliceManager.createInvite("Alice")
            val bobManager = testE2eeManager(createTestSqlDriver())
            val bobClient = LocationClient("http://localhost", bobManager, chaos)

            val (initPayload, aliceEntry) = bobManager.processScannedQr(qr, "Bob")
            bobClient.postKeyExchangeInit(aliceEntry.id, qr, initPayload)
            val pending = aliceClient.pollPendingInvites()
            requireNotNull(aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))
            assertTrue(bobManager.listFriends().none { it.isConfirmed }, "Bob hasn't heard from Alice yet")

            bobClient.removeFriend(aliceEntry.id)
            bobClient.processOutboxes()

            aliceClient.poll()
            assertNotNull(aliceManager.listFriends().single().stoppedAtTs)
        }

    @Test
    fun `an undelivered StoppedSharing past server retention is dropped`() =
        runTest {
            val mockTime = MockTimeProvider()
            TimeSource.setProvider(mockTime)
            try {
                val chaos = ChaosMailboxClient(MemoryMailboxClient())
                val counting = CountingMailboxClient(chaos)
                val aliceManager = testE2eeManager(createTestSqlDriver())
                val aliceClient = LocationClient("http://localhost", aliceManager, counting)
                val qr = aliceManager.createInvite("Alice")
                val bobManager = testE2eeManager(createTestSqlDriver())
                val bobClient = LocationClient("http://localhost", bobManager, chaos)
                val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
                bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
                val pending = aliceClient.pollPendingInvites()
                val bob = requireNotNull(aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))

                aliceClient.removeFriend(bob.id)
                assertEquals(1, aliceManager.getOrphanedOutbox().size)

                mockTime.advanceSeconds(FriendEntry.ACK_TIMEOUT_SECONDS + 60)
                chaos.failPostProbability = 0.0
                val postsBefore = counting.posts
                aliceClient.processOutboxes()

                assertTrue(aliceManager.getOrphanedOutbox().isEmpty())
                assertEquals(postsBefore, counting.posts, "an expired row must be dropped, not posted")
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }
}
