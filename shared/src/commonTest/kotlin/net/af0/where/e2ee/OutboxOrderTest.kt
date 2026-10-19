package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import net.af0.where.db.WhereDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * The outbox drains in the order frames were queued, whatever the wall clock does. It used to be
 * ordered by createdAt (wall-clock ms), so a clock stepped back between two sends put the later
 * frame first. The transition rule needs strict FIFO (§5.4.1, §5.4.3): if an epoch's transition
 * frame overtakes an earlier frame to the same mailbox, the peer ratchets and stops polling that
 * mailbox, and the earlier frame is either cancelled unsent or posted where no one reads it.
 * (formal/tla/WhereSync.tla models the outbox as a FIFO queue.)
 */
class OutboxOrderTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun `the outbox drains in queue order when the clock steps back`() =
        runTest {
            val mockTime = MockTimeProvider()
            TimeSource.setProvider(mockTime)
            try {
                val alice = testE2eeManager(createTestSqlDriver())
                val bob = testE2eeManager(createTestSqlDriver())
                val qr = alice.createInvite("Alice")
                val (_, bobEntry) = bob.processScannedQr(qr, "Bob")
                bob.removeFromOutbox(bobEntry.id, bob.getOutbox(bobEntry.id).single().msgId) // the init

                val (first, _) = bob.encryptAndAdvance(bobEntry.id, MessagePlaintext.Keepalive())
                mockTime.advanceSeconds(-60)
                val (second, _) = bob.encryptAndAdvance(bobEntry.id, MessagePlaintext.Keepalive())

                assertEquals(listOf(first.msgId, second.msgId), bob.getOutbox(bobEntry.id).map { it.msgId })
            } finally {
                TimeSource.setProvider(DefaultTimeProvider)
            }
        }

    @Test
    fun `a repeated msgId neither moves nor replaces a queued frame`() {
        val queries = WhereDatabase(createTestSqlDriver()).outboxQueries
        queries.insertOutbox("first", "friend", "token", byteArrayOf(1), 0)
        queries.insertOutbox("second", "friend", "token", byteArrayOf(2), 0)

        assertFails { queries.insertOutbox("first", "friend", "token", byteArrayOf(3), 0) }

        val queued = queries.getOutboxForFriend("friend").executeAsList()
        assertEquals(listOf("first", "second"), queued.map { it.msgId })
        assertEquals(listOf(1.toByte()), queued.first().payloadBlob.toList())
    }
}
