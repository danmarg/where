package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Within one batch, whichever of a Location and a StoppedSharing the peer sent later decides
 * whether they are sharing. "Later" is send order, not the self-reported ts: that has one-second
 * resolution, so a stop right after a Location used to tie in the Location's favour and leave the
 * friend shown as sharing, and it can go backwards with the peer's clock.
 */
class SharingStateOrderTest {
    init {
        initializeE2eeTests()
    }

    private val t = 1_767_225_600L

    private fun loc(ts: Long) = MessagePlaintext.Location(lat = 1.0, lng = 2.0, acc = 3.0, ts = ts)

    /** Bob sends [frames] in order; Alice reads them as one batch. Returns Alice's view of Bob. */
    private suspend fun aliceAfter(vararg frames: MessagePlaintext): FriendEntry {
        val alice = testE2eeManager(createTestSqlDriver())
        val bob = testE2eeManager(createTestSqlDriver())
        val qr = alice.createInvite("Alice")
        val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
        val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
        val sent = frames.map { bob.encryptAndAdvance(bobEntry.id, it).first }
        requireNotNull(alice.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), sent))
        return alice.listFriends().single()
    }

    @Test
    fun `a stop in the same second as the Location before it wins`() =
        runTest {
            assertEquals(t, aliceAfter(loc(t), MessagePlaintext.StoppedSharing(ts = t)).stoppedAtTs)
        }

    @Test
    fun `a resume in the same second as the stop before it wins`() =
        runTest {
            assertNull(aliceAfter(MessagePlaintext.StoppedSharing(ts = t), loc(t)).stoppedAtTs)
        }

    @Test
    fun `send order wins when the peer's clock stepped back`() =
        runTest {
            assertEquals(t - 5, aliceAfter(loc(t), MessagePlaintext.StoppedSharing(ts = t - 5)).stoppedAtTs)
            assertNull(aliceAfter(MessagePlaintext.StoppedSharing(ts = t), loc(t - 5)).stoppedAtTs)
        }
}
