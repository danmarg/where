package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression tests for https://github.com/danmarg/where/issues/369: sendMessageToFriendInternal
 * used to require the existing outbox to fully drain before it would even generate (let alone
 * persist) a new message. A StoppedSharing sent while an older message was stuck offline was
 * therefore never encrypted/queued at all - not "queued behind" the stuck one, just silently
 * dropped - because processOutbox()'s failure aborted before encryptAndAdvance() ever ran.
 *
 * StoppedSharing now passes requireDrainedOutboxFirst=false, so it's always persisted as a new
 * Outbox row regardless of whether the older entry drains successfully - see
 * sendMessageToFriendInternal's doc for why this is safe specifically for StoppedSharing (and
 * deliberately NOT the default for routine Location/Keepalive traffic).
 */
class StoppedSharingOutboxTest {
    init {
        initializeE2eeTests()
    }

    private data class Paired(
        val aliceClient: LocationClient,
        val bobClient: LocationClient,
        val bobManager: E2eeManager,
        val bobFriendId: String,
        val chaosMailbox: ChaosMailboxClient,
    )

    private suspend fun pairedClients(): Paired {
        val realMailbox = MemoryMailboxClient()
        val chaosMailbox = ChaosMailboxClient(realMailbox)

        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, chaosMailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, chaosMailbox)

        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        val aliceFriend = aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)
        requireNotNull(aliceFriend) { "handshake setup failed" }

        return Paired(aliceClient, bobClient, bobManager, aliceFriend.id, chaosMailbox)
    }

    @Test
    fun `a StoppedSharing behind a stuck outbox entry is persisted, not dropped`() =
        runTest {
            val paired = pairedClients()
            val (aliceClient, bobClient, bobManager, bobFriendId, chaosMailbox) = paired

            // Location A gets stuck in the outbox (post fails).
            chaosMailbox.failNextPost = true
            try {
                aliceClient.sendLocation(37.0, -122.0, emptySet())
            } catch (_: Exception) {
                // expected - A is now stuck in the outbox
            }

            // Still offline: sendStoppedSharingToFriend must swallow the delivery failure (as
            // before) but must ALSO have persisted the StoppedSharing message - not silently
            // dropped it because A was still stuck.
            chaosMailbox.failNextPost = true
            aliceClient.sendStoppedSharingToFriend(bobFriendId)

            // Network "recovers": flush the outbox (syncNow(), not a fresh sendLocation() - sharing
            // is off at this point, so nothing would generate a new Location in the real flow).
            // Both queued messages - the recovered stuck Location, then the StoppedSharing -
            // must drain in order.
            aliceClient.syncNow()

            val locations = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, locations.size, "the originally stuck Location must still be delivered")
            assertEquals(37.0, locations[0].lat)

            val bobsViewOfAlice = bobManager.listFriends().first()
            assertNotNull(bobsViewOfAlice.stoppedAtTs, "the StoppedSharing message must have been persisted and eventually delivered")
        }

    @Test
    fun `sendStoppedSharing (master toggle-off fan-out) also persists behind a stuck outbox entry`() =
        runTest {
            val paired = pairedClients()
            val (aliceClient, bobClient, bobManager, _, chaosMailbox) = paired

            // Location A gets stuck in the outbox (post fails).
            chaosMailbox.failNextPost = true
            try {
                aliceClient.sendLocation(37.0, -122.0, emptySet())
            } catch (_: Exception) {
                // expected - A is now stuck in the outbox
            }

            // Still offline: the master-off fan-out path (forEachFriendParallel, its own
            // exception swallowing - distinct code path from sendStoppedSharingToFriend) must
            // also persist the StoppedSharing rather than drop it.
            chaosMailbox.failNextPost = true
            aliceClient.sendStoppedSharing(emptySet())

            aliceClient.syncNow()

            val locations = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, locations.size, "the originally stuck Location must still be delivered")
            assertEquals(37.0, locations[0].lat)

            val bobsViewOfAlice = bobManager.listFriends().first()
            assertNotNull(bobsViewOfAlice.stoppedAtTs, "the StoppedSharing message must have been persisted and eventually delivered")
        }
}
