package net.af0.where.e2ee

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a poll cycle costs in store and transport calls (C24, E3, E4, E5), alongside what it must
 * still do.
 */
class PollCostTest {
    init {
        initializeE2eeTests()
    }

    private val clock = MockTimeProvider().also { TimeSource.setProvider(it) }

    @AfterTest
    fun restoreClock() = TimeSource.setProvider(DefaultTimeProvider)

    /** Counts statements by substring, and can fail the next query containing [failQueryOn]. */
    private class CountingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val executes = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var failQueryOn: String? = null
        var failExecuteOn: String? = null

        fun executed(part: String) = executes.count { it.contains(part) }

        fun queried(part: String) = queries.count { it.contains(part) }

        fun reset() {
            executes.clear()
            queries.clear()
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            executes.add(sql)
            val failing = failExecuteOn
            if (failing != null && sql.contains(failing)) {
                failExecuteOn = null
                throw IllegalStateException("injected failure on: $failing")
            }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            queries.add(sql)
            val pattern = failQueryOn
            if (pattern != null && sql.contains(pattern)) {
                failQueryOn = null
                throw IllegalStateException("injected query failure on: $pattern")
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    /** A mailbox that counts polls per token, serves at most [pageLimit] a GET, and can fail posts. */
    private class CountingMailbox(val pageLimit: Int = 50) : MailboxClient {
        val inner = MemoryMailboxClient()
        val polls = mutableMapOf<String, Int>()
        var failPosts = false

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            check(!failPosts) { "injected post failure" }
            inner.post(baseUrl, token, payload)
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            polls[token] = (polls[token] ?: 0) + 1
            return inner.poll(baseUrl, token).take(pageLimit)
        }

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) = inner.ackId(baseUrl, token, msgId)

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) = inner.ackIds(baseUrl, token, msgIds)
    }

    private class Setup(
        val driver: CountingDriver,
        val mailbox: CountingMailbox,
        val aliceManager: E2eeManager,
        val alice: LocationClient,
        val bobManager: E2eeManager,
        val bob: LocationClient,
        val bobId: String,
    )

    private suspend fun paired(pageLimit: Int = 50): Setup {
        val driver = CountingDriver(createTestSqlDriver())
        val mailbox = CountingMailbox(pageLimit)
        val aliceManager = testE2eeManager(driver)
        val alice = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")
        val bobManager = testE2eeManager(createTestSqlDriver())
        val bob = LocationClient("http://localhost", bobManager, mailbox)
        val (init, entry) = bobManager.processScannedQr(qr, "Bob")
        bob.postKeyExchangeInit(entry.id, qr, init)
        val pending = alice.pollPendingInvites().single()
        val bobId = requireNotNull(aliceManager.processKeyExchangeInit(pending.payload, "Bob", pending.inviteEkPub)).id
        alice.sendKeepalive(bobId)
        bob.poll(isForeground = true, pausedFriendIds = emptySet())
        // Both start from a drained, caught-up state.
        alice.poll(isForeground = true, pausedFriendIds = emptySet())
        return Setup(driver, mailbox, aliceManager, alice, bobManager, bob, bobId)
    }

    private suspend fun Setup.aliceRecvToken() = aliceManager.getFriend(bobId)!!.session.recvToken.toHex()

    private suspend fun Setup.bobSendsLocations(n: Int) {
        val token = aliceRecvToken()
        for (i in 1..n) {
            val location = MessagePlaintext.Location(10.0 + i, 20.0, 0.0, clock.currentTimeSeconds() + i)
            val (msg, _) = bobManager.encryptAndAdvance(bobId, location)
            mailbox.post("http://localhost", token, msg)
        }
    }

    private fun CountingDriver.outboxReads() = queried("FROM Outbox WHERE friendId")

    private fun CountingDriver.friendWrites() = executed("INTO Friends")

    // ---- C24 ----

    @Test
    fun `a Location clears an owed-stop row even when the first load of the table failed`() =
        runTest {
            val p = paired()
            // A stop owed and persisted, never queued.
            p.alice.sendLocation(1.0, 1.0)
            p.driver.failExecuteOn = "INSERT INTO Outbox"
            p.alice.sendStoppedSharingToFriend(p.bobId)
            assertTrue(p.alice.isStopOwed(p.bobId))

            // After a restart whose first read of OwedStops fails, a Location is sent.
            val restarted = LocationClient("http://localhost", p.aliceManager, p.mailbox)
            p.driver.failQueryOn = "OwedStops"
            restarted.sendLocation(2.0, 2.0)

            // The row is gone, so it can't be sent as a stop after the next restart.
            assertFalse(LocationClient("http://localhost", p.aliceManager, p.mailbox).isStopOwed(p.bobId))
        }

    @Test
    fun `owed-stop removal skips the delete once the table is loaded and nothing is owed`() =
        runTest {
            val p = paired()
            p.alice.sendLocation(1.0, 1.0)
            p.alice.isStopOwed(p.bobId) // loads the table
            p.driver.reset()
            p.alice.sendLocation(2.0, 2.0, stationary = true)
            assertEquals(0, p.driver.executed("DELETE FROM OwedStops"))
        }

    // ---- E3 ----

    @Test
    fun `a poll reads each friend's outbox once`() =
        runTest {
            val p = paired()
            p.driver.reset()
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, p.driver.outboxReads())
        }

    @Test
    fun `a stuck outbox is delivered by the next poll, and a fresh send still goes out at once`() =
        runTest {
            val p = paired()
            p.mailbox.failPosts = true
            runCatching { p.alice.sendLocation(1.0, 1.0) }
            assertEquals(1, p.aliceManager.getOutbox(p.bobId).size)
            p.mailbox.failPosts = false

            p.driver.reset()
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(0, p.aliceManager.getOutbox(p.bobId).size)

            // A send queues and posts in the same call, without waiting for a poll.
            val before = p.mailbox.inner.allPosted.size
            p.alice.sendLocation(2.0, 2.0)
            assertEquals(before + 1, p.mailbox.inner.allPosted.size)
            assertEquals(0, p.aliceManager.getOutbox(p.bobId).size)
        }

    // ---- E4 ----

    @Test
    fun `a short batch is not followed by a confirming GET`() =
        runTest {
            val p = paired()
            p.bobSendsLocations(3)
            val token = p.aliceRecvToken()
            val pollsBefore = p.mailbox.polls[token] ?: 0

            val updates = p.alice.poll(isForeground = true, pausedFriendIds = emptySet())

            assertEquals(1, (p.mailbox.polls[token] ?: 0) - pollsBefore)
            assertEquals(13.0, updates.single().lat)
            assertTrue(p.mailbox.inner.mailboxes[token].orEmpty().isEmpty()) // acked
            val friend = p.aliceManager.getFriend(p.bobId)!!
            assertTrue(friend.isCaughtUp)
            assertEquals(13.0, friend.lastLat)
        }

    @Test
    fun `a full page is followed by another GET until a short one`() =
        runTest {
            val p = paired(pageLimit = 50)
            p.bobSendsLocations(60)
            val token = p.aliceRecvToken()
            val pollsBefore = p.mailbox.polls[token] ?: 0

            val updates = p.alice.poll(isForeground = true, pausedFriendIds = emptySet())

            // 50, then 10 (short, so the tail): two GETs, not three.
            assertEquals(2, (p.mailbox.polls[token] ?: 0) - pollsBefore)
            assertEquals(70.0, updates.single().lat)
            assertTrue(p.mailbox.inner.mailboxes[token].orEmpty().isEmpty())
            assertTrue(p.aliceManager.getFriend(p.bobId)!!.isCaughtUp)
        }

    @Test
    fun `an exactly full page still gets the confirming GET`() =
        runTest {
            val p = paired(pageLimit = 50)
            p.bobSendsLocations(50)
            val token = p.aliceRecvToken()
            val pollsBefore = p.mailbox.polls[token] ?: 0

            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())

            assertEquals(2, (p.mailbox.polls[token] ?: 0) - pollsBefore)
            assertTrue(p.aliceManager.getFriend(p.bobId)!!.isCaughtUp)
        }

    @Test
    fun `a batch whose ack failed is not called caught up`() =
        runTest {
            val p = paired()
            p.bobSendsLocations(2)
            val failing =
                object : MailboxClient by p.mailbox {
                    override suspend fun ackIds(
                        baseUrl: String,
                        token: String,
                        msgIds: List<String>,
                    ) = throw IllegalStateException("ack down")
                }
            val alice = LocationClient("http://localhost", p.aliceManager, failing)
            alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertFalse(p.aliceManager.getFriend(p.bobId)!!.isCaughtUp)
            // Still unacked on the server: redelivered, so a later poll finishes the job.
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertTrue(p.aliceManager.getFriend(p.bobId)!!.isCaughtUp)
        }

    // ---- E5 ----

    @Test
    fun `an unchanged poll state is not rewritten within the granularity`() =
        runTest {
            val p = paired()
            // Caught up already. Polls seconds apart change nothing but the timestamp.
            p.driver.reset()
            clock.advanceSeconds(10)
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            clock.advanceSeconds(10)
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(0, p.driver.friendWrites())

            // Past the granularity it is refreshed, with the new time.
            clock.advanceSeconds(POLL_STATE_WRITE_GRANULARITY_SECONDS)
            p.alice.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, p.driver.friendWrites())
            assertEquals(clock.currentTimeSeconds(), p.aliceManager.getFriend(p.bobId)!!.lastPollTs)
        }

    @Test
    fun `a change of caught-up state is written at once`() =
        runTest {
            val p = paired()
            p.aliceManager.updatePollState(p.bobId, clock.currentTimeSeconds(), false)
            p.driver.reset()
            p.aliceManager.updatePollState(p.bobId, clock.currentTimeSeconds(), true)
            assertEquals(1, p.driver.friendWrites())
            assertTrue(p.aliceManager.getFriend(p.bobId)!!.isCaughtUp)
        }

    @Test
    fun `a clock stepped back is written, so repair judgement follows the clock`() =
        runTest {
            val p = paired()
            val t = clock.currentTimeSeconds()
            p.aliceManager.updatePollState(p.bobId, t - 5, true)
            assertEquals(t - 5, p.aliceManager.getFriend(p.bobId)!!.lastPollTs)
        }
}
