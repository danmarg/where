package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import net.af0.where.db.WhereDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accepting an invite pairs with fresh keys, so accepting the same invite again (a re-opened
 * link, a redelivered deep link, a second scan) would create a duplicate friend.
 */
class InviteReplayTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun acceptingTheSameInviteTwiceIsRejected() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val invite = alice.createInvite("Alice")

            bob.processScannedQr(invite, "Bob")
            assertFailsWith<InviteAlreadyAcceptedException> { bob.processScannedQr(invite, "Bob") }
            assertEquals(1, bob.listFriends().size)
        }

    @Test
    fun rejectedReplayQueuesNothing() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val invite = alice.createInvite("Alice")
            val (_, friend) = bob.processScannedQr(invite, "Bob")
            val queued = bob.getOutbox(friend.id).size

            assertFailsWith<InviteAlreadyAcceptedException> { bob.processScannedQr(invite, "Bob") }
            assertEquals(queued, bob.getOutbox(friend.id).size)
        }

    @Test
    fun aNewInviteFromTheSamePersonStillPairs() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            bob.processScannedQr(alice.createInvite("Alice"), "Bob")
            bob.processScannedQr(alice.createInvite("Alice"), "Bob")
            assertEquals(2, bob.listFriends().size)
        }

    @Test
    fun isFromInviteMatchesOnlyThePairingThatInviteCreated() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val first = alice.createInvite("Alice")
            val second = alice.createInvite("Alice")
            val (_, friend) = bob.processScannedQr(first, "Bob")

            assertTrue(friend.isFromInvite(first))
            assertFalse(friend.isFromInvite(second))
            // Survives a reload from the database (e.g. after an app upgrade).
            assertTrue(bob.listFriends().single().isFromInvite(first))
        }

    @Test
    fun theReplayCheckSurvivesARestart() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val driver = createTestSqlDriver()
            val invite = alice.createInvite("Alice")
            testE2eeManager(driver).processScannedQr(invite, "Bob")

            val restarted = testE2eeManager(driver)
            assertFailsWith<InviteAlreadyAcceptedException> { restarted.processScannedQr(invite, "Bob") }
            assertEquals(1, restarted.listFriends().size)
        }

    @Test
    fun aRemovedFriendsInviteCannotPairAgain() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val driver = createTestSqlDriver()
            val bob = testE2eeManager(driver)
            val invite = alice.createInvite("Alice")
            val (_, friend) = bob.processScannedQr(invite, "Bob")
            bob.deleteFriendKeepingOutbox(friend.id, null)
            assertTrue(bob.listFriends().isEmpty())

            assertFailsWith<InviteAlreadyAcceptedException> { bob.processScannedQr(invite, "Bob") }
            // The record is in the database, so a restart remembers it too.
            assertFailsWith<InviteAlreadyAcceptedException> { testE2eeManager(driver).processScannedQr(invite, "Bob") }
            assertTrue(bob.listFriends().isEmpty())
            // A new invite from the same person is unaffected.
            bob.processScannedQr(alice.createInvite("Alice"), "Bob")
            assertEquals(1, bob.listFriends().size)
        }

    @Test
    fun theConsumedRecordIsPrunedAfterTheInviteExpires() =
        runTest {
            val clock = MockTimeProvider()
            TimeSource.setProvider(clock)
            try {
                val driver = createTestSqlDriver()
                val alice = testE2eeManager(createTestSqlDriver())
                val bob = testE2eeManager(driver)
                val invite = alice.createInvite("Alice")
                val (_, friend) = bob.processScannedQr(invite, "Bob")
                bob.deleteFriendKeepingOutbox(friend.id, null)

                // Past the invite's life the link is refused as expired, not as already accepted.
                clock.advanceSeconds(INVITE_LIFETIME_SECONDS + 3 * INVITE_EXPIRY_GRACE_SECONDS)
                assertFailsWith<InviteExpiredException> { bob.processScannedQr(invite, "Bob") }

                val row = fingerprint(invite.ekPub.toDhPublicKey()).toByteArray()
                val queries = WhereDatabase(driver).consumedInvitesQueries
                assertEquals(1L, queries.isConsumed(row, 0L).executeAsOne(), "kept until a later accept prunes it")
                bob.processScannedQr(alice.createInvite("Alice"), "Bob")
                assertEquals(0L, queries.isConsumed(row, 0L).executeAsOne())
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    @Test
    fun anInviteWithoutAnExpiryStaysConsumed() =
        runTest {
            val clock = MockTimeProvider()
            TimeSource.setProvider(clock)
            try {
                val alice = testE2eeManager(createTestSqlDriver())
                val bob = testE2eeManager(createTestSqlDriver())
                val invite = alice.createInvite("Alice").copy(expiresAt = null)
                val (_, friend) = bob.processScannedQr(invite, "Bob")
                bob.deleteFriendKeepingOutbox(friend.id, null)

                clock.advanceSeconds(3600)
                // Another accepted invite runs the prune; the undated record must survive it.
                bob.processScannedQr(alice.createInvite("Alice"), "Bob")
                assertTrue(bob.isInviteConsumed(invite))
                assertFailsWith<InviteAlreadyAcceptedException> { bob.processScannedQr(invite, "Bob") }
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }
}
