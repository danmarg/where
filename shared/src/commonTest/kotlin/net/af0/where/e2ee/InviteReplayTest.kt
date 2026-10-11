package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
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
}
