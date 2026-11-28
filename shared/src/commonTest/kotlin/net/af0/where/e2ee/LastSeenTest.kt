package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** C14: "last seen" is when we received the friend's Location, not the sender's GPS timestamp. */
class LastSeenTest {
    init {
        initializeE2eeTests()
    }

    private val clock = MockTimeProvider().also { TimeSource.setProvider(it) }

    @AfterTest
    fun restoreClock() = TimeSource.setProvider(DefaultTimeProvider)

    private suspend fun paired(): Triple<LocationClient, LocationClient, E2eeManager> {
        val mailbox = MemoryMailboxClient()
        val aliceManager = testE2eeManager(createTestSqlDriver())
        val alice = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")
        val bobManager = testE2eeManager(createTestSqlDriver())
        val bob = LocationClient("http://localhost", bobManager, mailbox)
        val (init, entry) = bobManager.processScannedQr(qr, "Alice")
        bob.postKeyExchangeInit(entry.id, qr, init)
        val pending = alice.pollPendingInvites().single()
        requireNotNull(aliceManager.processKeyExchangeInit(pending.payload, "Bob", pending.inviteEkPub))
        return Triple(alice, bob, bobManager)
    }

    @Test
    fun `processing a Location stamps lastLocationRecvTs with the receive clock`() =
        runTest {
            val (alice, bob, bobManager) = paired()
            assertNull(bobManager.listFriends().single().lastLocationRecvTs)

            val sentAt = clock.currentTimeSeconds()
            alice.sendLocation(1.0, 2.0)
            clock.advanceSeconds(600)
            bob.poll(isForeground = true, pausedFriendIds = emptySet())

            val friend = bobManager.listFriends().single()
            assertEquals(clock.currentTimeSeconds(), friend.lastLocationRecvTs)
            assertEquals(sentAt + 600, friend.lastLocationRecvTs)
            assertEquals(sentAt, friend.lastTs)
            assertEquals(friend.lastLocationRecvTs, friend.lastSeenSeconds)
        }

    @Test
    fun `lastSeenSeconds falls back to the sender timestamp for old entries`() =
        runTest {
            val (_, _, bobManager) = paired()
            val old = bobManager.listFriends().single().copy(lastTs = 42L, lastLocationRecvTs = null)
            assertEquals(42L, old.lastSeenSeconds)
            assertEquals(99L, old.copy(lastLocationRecvTs = 99L).lastSeenSeconds)
        }
}
