package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
