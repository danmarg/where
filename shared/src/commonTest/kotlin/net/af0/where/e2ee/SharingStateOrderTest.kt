package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Within one batch, whichever of a Location and a StoppedSharing is later decides whether the peer
 * is sharing. "Later" is by ts (when each was generated), and a same-second tie goes to send order:
 * the ts has one-second resolution, and a stop right after a Location used to tie in the
 * Location's favour and leave the friend shown as sharing.
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
    fun `send order spans a DH ratchet within the batch`() =
        runTest {
            val alice = testE2eeManager(createTestSqlDriver())
            val bob = testE2eeManager(createTestSqlDriver())
            val qr = alice.createInvite("Alice")
            val (init, bobEntry) = bob.processScannedQr(qr, "Bob")
            val aliceEntry = requireNotNull(alice.processKeyExchangeInit(init, "Bob", qr.ekPub))
            // Bob's Location in his bootstrap epoch; then he reads Alice's first frame and ratchets,
            // so his stop is seq 1 of a new epoch, in the same second: only send order puts it last.
            val loc = bob.encryptAndAdvance(bobEntry.id, loc(t)).first
            val fromAlice = alice.encryptAndAdvance(aliceEntry.id, MessagePlaintext.Keepalive()).first
            requireNotNull(bob.processBatch(bobEntry.id, bobEntry.session.recvToken.toHex(), listOf(fromAlice)))
            val stop = bob.encryptAndAdvance(bobEntry.id, MessagePlaintext.StoppedSharing(ts = t)).first
            // The premise: the stop goes to the mailbox Alice still polls, with the Location.
            val bobSession = requireNotNull(bob.getFriend(bobEntry.id)).session
            assertEquals(aliceEntry.session.recvToken.toHex(), bobSession.prevSendToken.toHex())

            // Delivered stop first: the batch is still processed in send order.
            requireNotNull(alice.processBatch(aliceEntry.id, aliceEntry.session.recvToken.toHex(), listOf(stop, loc)))
            assertEquals(t, alice.listFriends().single().stoppedAtTs)
        }

    @Test
    fun `the stop time is the last stop's in send order and not the largest`() =
        runTest {
            // The sender's clock stepped back between the two stops. The last stop ties the
            // Location and wins by send order (stoppedAtTs set means stopped); the largest stop
            // time would be t + 1.
            val friend = aliceAfter(loc(t), MessagePlaintext.StoppedSharing(ts = t + 1), MessagePlaintext.StoppedSharing(ts = t))
            assertEquals(t, friend.stoppedAtTs)
        }

    @Test
    fun `a Location generated before a stop but sent after it loses`() =
        runTest {
            // It waited on the friend's lock while the stop went out (LocationClient.sendLocation).
            assertEquals(t, aliceAfter(MessagePlaintext.StoppedSharing(ts = t), loc(t - 1)).stoppedAtTs)
        }
}
