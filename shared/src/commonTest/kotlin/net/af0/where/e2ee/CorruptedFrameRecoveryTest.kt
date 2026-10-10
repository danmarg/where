package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end (LocationClient + E2eeManager + mailbox) check that a frame whose body is
 * corrupted in transit — header intact — does not permanently break the session. Covers
 * token routing and outbox handling around the soft-fail, which Session-level tests skip.
 */
class CorruptedFrameRecoveryTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun sessionRecoversAfterCorruptedNewEpochFrame() =
        runTest {
            val mailbox = ChaosMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val bobManager = testE2eeManager(createTestSqlDriver())

            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            val alice = LocationClient("http://localhost", aliceManager, mailbox).apply { enableAutomatedKeepalives = false }
            val bob = LocationClient("http://localhost", bobManager, mailbox).apply { enableAutomatedKeepalives = false }
            alice.postKeyExchangeInit(bobEntry.id, qr, initPayload)
            aliceManager.processKeyExchangeInit(alice.pollPendingInvites().single().payload, "Bob", qr.ekPub)

            // Alice's first frame opens a new DH epoch for Bob; corrupt its body only.
            alice.sendLocation(1.0, 1.0)
            mailbox.corruptNextPayloadOnly = true
            bob.poll()
            assertNull(bobManager.listFriends().single().lastLng, "the corrupted frame must not have been delivered")

            for (i in 2..4) {
                bob.sendLocation(10.0, 10.0 + i)
                alice.poll()
                assertEquals(10.0 + i, aliceManager.listFriends().single().lastLng, "Alice should see Bob's update $i")

                alice.sendLocation(1.0, i.toDouble())
                bob.poll()
                assertEquals(i.toDouble(), bobManager.listFriends().single().lastLng, "Bob should see Alice's update $i")
            }
        }

    /**
     * A soft-failed frame (header authenticated, body corrupted) is peer activity but not a
     * delivery: it confirms the pairing and refreshes lastRecvTs, delivers no location, and is
     * ACKed (removed from the mailbox). The ACK gives the sender no delivery signal.
     */
    @Test
    fun softFailedFrameCountsAsActivityButNotDelivery() =
        runTest {
            val mailbox = ChaosMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val bobManager = testE2eeManager(createTestSqlDriver())
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            val alice = LocationClient("http://localhost", aliceManager, mailbox).apply { enableAutomatedKeepalives = false }
            val bob = LocationClient("http://localhost", bobManager, mailbox).apply { enableAutomatedKeepalives = false }
            alice.postKeyExchangeInit(bobEntry.id, qr, initPayload)
            aliceManager.processKeyExchangeInit(alice.pollPendingInvites().single().payload, "Bob", qr.ekPub)

            val bobBefore = bobManager.listFriends().single()
            assertFalse(bobBefore.isConfirmed)
            val bobRecvToken = bobBefore.session.recvToken.toHex()
            val aliceBefore = aliceManager.listFriends().single()

            alice.sendLocation(1.0, 1.0)
            TimeSource.setProvider(ClockAhead(120))
            try {
                mailbox.corruptNextPayloadOnly = true
                val delivered = bob.poll()

                val bobAfter = bobManager.listFriends().single()
                assertTrue(delivered.isEmpty(), "nothing delivered: $delivered")
                assertNull(bobAfter.lastLng)
                assertTrue(bobAfter.isConfirmed, "an authenticated frame confirms the pairing")
                assertTrue(bobAfter.lastRecvTs > bobBefore.lastRecvTs, "an authenticated frame refreshes lastRecvTs")
                assertTrue(mailbox.poll("http://localhost", bobRecvToken).isEmpty(), "soft-failed frame is ACKed")

                // The sender's view is untouched by the receiver's ACK.
                val aliceAfter = aliceManager.listFriends().single()
                assertEquals(aliceBefore.lastRecvTs, aliceAfter.lastRecvTs)
                assertTrue(aliceManager.getOutbox(aliceAfter.id).isEmpty())
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    private class ClockAhead(private val seconds: Long) : TimeProvider {
        override fun currentTimeMillis() = DefaultTimeProvider.currentTimeMillis() + seconds * 1000

        override fun currentTimeSeconds() = DefaultTimeProvider.currentTimeSeconds() + seconds

        override fun formatLocalTime(seconds: Long) = DefaultTimeProvider.formatLocalTime(seconds)
    }
}
