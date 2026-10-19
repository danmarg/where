package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The atomicity contract of ProtocolCore's interpreters (see ProtocolCore): a step's new session
 * and its local effects on the store (outbox rows queued by Put, deleted by CancelPut; the pending
 * invite a ProcessInit consumes) commit together or not at all. Mailbox effects (PUT, DELETE, the
 * token to poll next) happen after the commit and are safe to repeat or lose to a crash.
 */
class AtomicityTest {
    init {
        initializeE2eeTests()
    }

    private class Paired(
        val alice: E2eeManager,
        val aliceDriver: FailingSqlDriver,
        val aliceId: String,
        val bob: E2eeManager,
        val bobId: String,
    )

    private suspend fun pair(): Paired {
        val aliceDriver = FailingSqlDriver(createTestSqlDriver())
        val alice = testE2eeManager(aliceDriver)
        val bob = testE2eeManager(createTestSqlDriver())
        val qr = alice.createInvite("Alice")
        val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
        val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
        return Paired(alice, aliceDriver, aliceEntry.id, bob, bobEntry.id)
    }

    private suspend fun E2eeManager.recvToken(id: String) = requireNotNull(getFriend(id)).session.recvToken.toHex()

    private fun keepalive() = MessagePlaintext.Keepalive()

    /** Alice's queued transition frame, read by Bob, then Bob's reply that makes Alice ratchet. */
    private suspend fun Paired.ratchetSetup(): EncryptedMessagePayload {
        val (a1, _) = alice.encryptAndAdvance(aliceId, keepalive())
        assertEquals(1, alice.getOutbox(aliceId).size)
        assertNotNull(bob.processBatch(bobId, bob.recvToken(bobId), listOf(a1)))
        return bob.encryptAndAdvance(bobId, keepalive()).first
    }

    @Test
    fun `a ratchet's CancelPut rolls back with its session`() =
        runTest {
            val p = pair()
            val b1 = p.ratchetSetup()
            val before = p.alice.recvToken(p.aliceId)

            p.aliceDriver.failOn = "INSERT OR REPLACE INTO Friends"
            assertFailsWith<IllegalStateException> { p.alice.processBatch(p.aliceId, before, listOf(b1)) }
            assertEquals(before, p.alice.recvToken(p.aliceId), "the session must not have moved")
            assertEquals(1, p.alice.getOutbox(p.aliceId).size, "the outbox delete must roll back with it")

            // Retried, both happen.
            assertNotNull(p.alice.processBatch(p.aliceId, before, listOf(b1)))
            assertTrue(p.alice.recvToken(p.aliceId) != before)
            assertTrue(p.alice.getOutbox(p.aliceId).isEmpty())
        }

    @Test
    fun `a failed CancelPut delete rolls back the session too`() =
        runTest {
            val p = pair()
            val b1 = p.ratchetSetup()
            val before = p.alice.recvToken(p.aliceId)
            p.aliceDriver.failOn = "DELETE FROM Outbox WHERE friendId = ? AND token = ?"
            assertFailsWith<IllegalStateException> { p.alice.processBatch(p.aliceId, before, listOf(b1)) }
            assertEquals(before, p.alice.recvToken(p.aliceId), "the session must roll back with the delete")
            assertEquals(1, p.alice.getOutbox(p.aliceId).size)

            assertNotNull(p.alice.processBatch(p.aliceId, before, listOf(b1)))
            assertTrue(p.alice.recvToken(p.aliceId) != before)
            assertTrue(p.alice.getOutbox(p.aliceId).isEmpty())
        }

    @Test
    fun `a failed invite delete rolls back saving the friend too`() =
        runTest {
            val aliceDriver = FailingSqlDriver(createTestSqlDriver())
            val alice = testE2eeManager(aliceDriver)
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice")
            val (init, _) = bob.processScannedQr(qr, "Bob")
            aliceDriver.failOn = "DELETE FROM PendingInvites WHERE ekPub = ?"
            assertFailsWith<IllegalStateException> { alice.processKeyExchangeInit(init, "Bob", qr.ekPub) }
            assertTrue(alice.listFriends().isEmpty(), "the friend must roll back with the invite delete")
            assertEquals(1, alice.listPendingInvites().size)
            assertNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
        }

    @Test
    fun `an init consumes its invite only together with saving the friend`() =
        runTest {
            val aliceDriver = FailingSqlDriver(createTestSqlDriver())
            val alice = testE2eeManager(aliceDriver)
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice")
            val (init, _) = bob.processScannedQr(qr, "Bob")

            aliceDriver.failOn = "INSERT OR REPLACE INTO Friends"
            assertFailsWith<IllegalStateException> { alice.processKeyExchangeInit(init, "Bob", qr.ekPub) }
            assertEquals(1, alice.listPendingInvites().size, "the invite must survive a failed save")
            assertTrue(alice.listFriends().isEmpty())

            assertNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
            assertTrue(alice.listPendingInvites().isEmpty())
            assertEquals(1, alice.listFriends().size)
            // A second delivery of the same init doesn't pair again.
            assertNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
        }

    @Test
    fun `after a crash between saving and acking the batch is a replay and the old mailbox is refused`() =
        runTest {
            val p = pair()
            val b1 = p.ratchetSetup()
            val old = p.alice.recvToken(p.aliceId)
            assertNotNull(p.alice.processBatch(p.aliceId, old, listOf(b1)))
            val (b2, _) = p.bob.encryptAndAdvance(p.bobId, keepalive())
            val current = p.alice.recvToken(p.aliceId)
            assertEquals(1, requireNotNull(p.alice.processBatch(p.aliceId, current, listOf(b2))).processedIds.size)

            // Crash before either DELETE: a fresh manager on the same database.
            val restarted = testE2eeManager(p.aliceDriver)
            assertEquals(current, restarted.recvToken(p.aliceId), "the rotated token was saved")
            // The leftover on the retired mailbox is refused; nothing polls it any more.
            assertNull(restarted.processBatch(p.aliceId, old, listOf(b1)))
            // The leftover on the current one is a replay: acked again, nothing delivered.
            val again = requireNotNull(restarted.processBatch(p.aliceId, current, listOf(b2)))
            assertEquals(listOf(b2.msgId), again.processedIds)
            assertTrue(again.decryptedLocations.isEmpty() && again.anyReplay)
        }

    @Test
    fun `a batch from a mailbox other than the current one changes nothing`() =
        runTest {
            val p = pair()
            val (b1, _) = p.bob.encryptAndAdvance(p.bobId, keepalive())
            val session = requireNotNull(p.alice.getFriend(p.aliceId)).session
            assertNull(p.alice.processBatch(p.aliceId, "00".repeat(16), listOf(b1)))
            assertEquals(session, requireNotNull(p.alice.getFriend(p.aliceId)).session)
            assertFailsWith<StaleMailboxException> {
                ProtocolCore.step(
                    ProtocolCore.State.Paired(session),
                    ProtocolCore.Event.Receive("00".repeat(16), listOf(b1)),
                    0L,
                    SystemRng,
                )
            }
        }
}
