package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A friendship survives one side's app not running for a long time (Android force-stop, iOS
 * swipe-away, a phone left off), as long as the server still holds what the absent side needs.
 * The only thing that breaks it is the server's message TTL: an unread transition frame
 * (spec §5.4.1) expiring, or every frame expiring so neither side hears from the other again
 * (docs/long-absence-recovery.md). The client's 7-day inactivity pause caps what piles up, and
 * doesn't stop recovery while the frames are still there.
 */
class LongAbsenceTest {
    init {
        initializeE2eeTests()
    }

    private val clock = MockTimeProvider().also { TimeSource.setProvider(it) }

    @AfterTest
    fun restoreClock() {
        TimeSource.setProvider(DefaultTimeProvider)
    }

    /** An in-memory mailbox that drops messages older than [retentionSeconds], like the server's TTL. */
    private class TtlMailboxClient(private val retentionSeconds: Long) : MailboxClient {
        private class Entry(val payload: MailboxPayload, val postedAt: Long)

        private val mailboxes = mutableMapOf<String, MutableList<Entry>>()

        /** The next this many polls fail, as with no network. */
        var failNextPolls = 0

        /** When each post was made (seconds), duplicates included. */
        val postTimes = mutableListOf<Long>()

        private fun live(token: String): MutableList<Entry> {
            val queue = mailboxes.getOrPut(token) { mutableListOf() }
            val now = currentTimeSeconds()
            queue.removeAll { now - it.postedAt >= retentionSeconds }
            return queue
        }

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            postTimes.add(currentTimeSeconds())
            val queue = live(token)
            if (queue.none { it.payload.msgId == payload.msgId }) queue.add(Entry(payload, currentTimeSeconds()))
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            if (failNextPolls > 0) {
                failNextPolls--
                throw MailboxTimeoutException(RuntimeException("offline"))
            }
            return live(token).take(50).map { it.payload }
        }

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) {
            live(token).removeAll { it.payload.msgId == msgId }
        }

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) {
            live(token).removeAll { it.payload.msgId in msgIds }
        }
    }

    private class Peers(
        val alice: LocationClient,
        val aliceStore: E2eeManager,
        val bob: LocationClient,
        val bobStore: E2eeManager,
        val id: String,
    )

    private suspend fun pair(mailbox: MailboxClient): Peers {
        val aliceStore = testE2eeManager(createTestSqlDriver())
        val alice = LocationClient("http://localhost", aliceStore, mailbox)
        val bobStore = testE2eeManager(createTestSqlDriver())
        val bob = LocationClient("http://localhost", bobStore, mailbox)
        val qr = aliceStore.createInvite("Alice")
        val (init, bobEntry) = bobStore.processScannedQr(qr, "Bob")
        bob.postKeyExchangeInit(bobEntry.id, qr, init)
        val pending = alice.pollPendingInvites()
        requireNotNull(aliceStore.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))
        return Peers(alice, aliceStore, bob, bobStore, bobEntry.id)
    }

    private suspend fun LocationClient.share(lat: Double) = sendLocation(lat, 0.0, stamp = sharingStamp())

    private class Outcome(
        /**
         * Sharing works again after Bob came back: each side has heard from the other since,
         * neither shows the other as inactive, and, if Alice shares, a location of hers dated after
         * his return reached him.
         */
        val resumed: Boolean,
        /** Alice showed "may need to re-pair" for Bob just before he came back. */
        val aliceWarned: Boolean,
        /** Bob showed it for Alice on coming back, before his first poll. */
        val bobWarnedBeforePolling: Boolean,
        /** Bob showed it right after a first poll that failed (only with bobFirstPollFails). */
        val bobWarnedAfterFailedPoll: Boolean,
        /** Either side shows it at the end. */
        val warnedAtEnd: Boolean,
    )

    /**
     * Pairs, exchanges a few rounds (so both sides have ratcheted, and Bob's last frame leaves a
     * transition frame of Alice's in flight to him), then Bob's app stops for [awayDays]. Alice's
     * keeps running (every 5 min, then hourly once she has paused him), except for her first
     * [aliceAwayDays], when hers is stopped too. Then Bob comes back and both use the app for a
     * few minutes.
     */
    private suspend fun absence(
        retentionSeconds: Long,
        awayDays: Long,
        aliceSharing: Boolean,
        aliceAwayDays: Long = 0,
        bobFirstPollFails: Boolean = false,
    ): Outcome {
        val mailbox = TtlMailboxClient(retentionSeconds)
        val p = pair(mailbox)
        repeat(3) {
            p.alice.share(1.0)
            clock.advanceSeconds(30)
            p.bob.poll()
            p.bob.share(2.0)
            clock.advanceSeconds(30)
            p.alice.poll()
        }

        // Bob leaves (and maybe Alice for a while).
        val left = currentTimeSeconds()
        val awayUntil = left + awayDays * 86400
        val aliceBackAt = left + aliceAwayDays * 86400
        while (currentTimeSeconds() < awayUntil) {
            if (currentTimeSeconds() >= aliceBackAt) {
                p.alice.poll(isForeground = false, sharingEnabled = aliceSharing)
                if (aliceSharing) p.alice.share(1.0)
            }
            val bobPaused = p.aliceStore.getFriend(p.id)!!.isStale
            clock.advanceSeconds(if (bobPaused) 3600 else 300)
        }
        val paused = p.aliceStore.getFriend(p.id)!!.isStale
        assertTrue(paused == (awayDays * 86400 > FriendEntry.INACTIVITY_PAUSE_SECONDS), "Alice pauses Bob only after 7 days")

        // Bob comes back and opens the app.
        val back = currentTimeSeconds()
        val aliceWarned = p.aliceStore.getFriend(p.id)!!.mayNeedRepair
        val bobWarnedBeforePolling = p.bobStore.getFriend(p.id)!!.mayNeedRepair
        var bobWarnedAfterFailedPoll = false
        if (bobFirstPollFails) {
            mailbox.failNextPolls = 1
            p.bob.poll()
            bobWarnedAfterFailedPoll = p.bobStore.getFriend(p.id)!!.mayNeedRepair
        }
        // A few minutes of both apps in the foreground: enough polls to drain a ~2,000-frame
        // backlog (500 per poll) and carry the ratchet round trips that follow.
        var bobGotFreshLocation = false
        repeat(12) {
            val updates = p.bob.poll()
            if (updates.any { it.timestamp >= back }) bobGotFreshLocation = true
            p.bob.share(2.0)
            clock.advanceSeconds(10)
            p.alice.poll(sharingEnabled = aliceSharing)
            if (aliceSharing) p.alice.share(1.0)
            clock.advanceSeconds(10)
        }

        val alice = p.aliceStore.getFriend(p.id)!!
        val bob = p.bobStore.getFriend(p.id)!!
        val heard = alice.lastRecvTs >= back && bob.lastRecvTs >= back && !alice.isStale && !bob.isStale
        return Outcome(
            resumed = heard && (!aliceSharing || bobGotFreshLocation),
            aliceWarned = aliceWarned,
            bobWarnedBeforePolling = bobWarnedBeforePolling,
            bobWarnedAfterFailedPoll = bobWarnedAfterFailedPoll,
            warnedAtEnd = alice.mayNeedRepair || bob.mayNeedRepair,
        )
    }

    // What the 7-day server TTL does today, to pin down the cause.

    @Test
    fun sevenDayRetention_survivesFiveDays() = runTest { assertTrue(absence(7 * 86400L, awayDays = 5, aliceSharing = true).resumed) }

    @Test
    fun sevenDayRetention_breaksAfterTenDays() =
        runTest {
            assertFalse(absence(7 * 86400L, awayDays = 10, aliceSharing = true).resumed)
            assertFalse(absence(7 * 86400L, awayDays = 10, aliceSharing = false).resumed)
        }

    // With MAILBOX_RETENTION_SECONDS.

    @Test
    fun survivesTenDays() =
        runTest {
            for (sharing in listOf(true, false)) {
                val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = 10, aliceSharing = sharing)
                assertTrue(o.resumed, "sharing=$sharing")
                assertFalse(o.aliceWarned || o.warnedAtEnd, "no re-pair warning after 10 days")
            }
        }

    @Test
    fun survivesNearlyTheWholeRetention() =
        runTest {
            val days = MAILBOX_RETENTION_SECONDS / 86400 - 1
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = days, aliceSharing = true)
            assertTrue(o.resumed)
            assertTrue(o.aliceWarned, "the warning comes before the break")
            assertFalse(o.bobWarnedBeforePolling, "not before the returning side has polled")
            assertFalse(o.warnedAtEnd, "and clears once they hear from each other")
        }

    @Test
    fun aFailedFirstPollOnReturnShowsNoWarning() =
        runTest {
            val days = MAILBOX_RETENTION_SECONDS / 86400 - 1
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = days, aliceSharing = true, bobFirstPollFails = true)
            assertFalse(o.bobWarnedAfterFailedPoll, "a failed poll proves nothing about what's waiting")
            assertTrue(o.resumed)
        }

    @Test
    fun survivesBothSidesBeingAway() =
        runTest {
            // Alice's app is stopped for the first 30 days, Bob's for 60.
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = 60, aliceSharing = true, aliceAwayDays = 30)
            assertTrue(o.resumed)
            assertFalse(o.warnedAtEnd)
        }

    @Test
    fun aPausedFriendGetsOneLastKeepaliveAndThenNothing() =
        runTest {
            val mailbox = TtlMailboxClient(MAILBOX_RETENTION_SECONDS)
            val p = pair(mailbox)
            p.alice.share(1.0)
            clock.advanceSeconds(30)
            p.bob.poll()
            p.bob.share(2.0)
            clock.advanceSeconds(30)
            p.alice.poll()
            // Bob's app stops; Alice's keeps polling with sharing off, for 30 days. (Offset so her
            // regular keepalives don't land exactly on the pause, which would make one of them
            // the last frame and the parting keepalive unnecessary.)
            val pausedAt = currentTimeSeconds() + FriendEntry.INACTIVITY_PAUSE_SECONDS
            clock.advanceSeconds(7)
            repeat(30 * 24 * 12) {
                p.alice.poll(isForeground = false, sharingEnabled = false)
                clock.advanceSeconds(300)
            }
            assertTrue(p.aliceStore.getFriend(p.id)!!.isStale)
            assertEquals(1, mailbox.postTimes.count { it > pausedAt }, "exactly one frame after the pause")
        }

    @Test
    fun breaksWellPastRetention() =
        runTest {
            val days = MAILBOX_RETENTION_SECONDS / 86400 + 10
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = days, aliceSharing = true)
            assertFalse(o.resumed)
            assertTrue(o.aliceWarned)
            assertTrue(o.warnedAtEnd, "still shown after Bob's return finds nothing")
        }
}
