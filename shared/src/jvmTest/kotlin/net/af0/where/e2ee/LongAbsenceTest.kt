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
        ): List<MailboxPayload> = live(token).take(50).map { it.payload }

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

    /**
     * One client's network: while [offline], every request fails as with no connection. Not
     * ChaosMailboxClient: that doesn't fail acks, and an offline device can't ack either.
     */
    private class Link(private val inner: MailboxClient) : MailboxClient {
        var offline = false

        private fun check() {
            if (offline) throw MailboxTimeoutException(RuntimeException("offline"))
        }

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            check()
            inner.post(baseUrl, token, payload)
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            check()
            return inner.poll(baseUrl, token)
        }

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) {
            check()
            inner.ackId(baseUrl, token, msgId)
        }

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) {
            check()
            inner.ackIds(baseUrl, token, msgIds)
        }
    }

    private class Peers(
        val alice: LocationClient,
        val aliceStore: E2eeManager,
        val aliceLink: Link,
        val bob: LocationClient,
        val bobStore: E2eeManager,
        val bobLink: Link,
        val id: String,
    ) {
        /** Each side's entry for the other. */
        suspend fun aliceView() = aliceStore.getFriend(id)!!

        suspend fun bobView() = bobStore.getFriend(id)!!

        /** A few rounds each way, so both sides have ratcheted; Alice reads last. */
        suspend fun warmUp(
            clock: MockTimeProvider,
            rounds: Int = 3,
        ) = repeat(rounds) {
            alice.share(1.0)
            clock.advanceSeconds(30)
            bob.poll()
            bob.share(2.0)
            clock.advanceSeconds(30)
            alice.poll()
        }
    }

    private suspend fun pair(mailbox: MailboxClient): Peers {
        val aliceStore = testE2eeManager(createTestSqlDriver())
        val aliceLink = Link(mailbox)
        val alice = LocationClient("http://localhost", aliceStore, aliceLink)
        val bobStore = testE2eeManager(createTestSqlDriver())
        val bobLink = Link(mailbox)
        val bob = LocationClient("http://localhost", bobStore, bobLink)
        val qr = aliceStore.createInvite("Alice")
        val (init, bobEntry) = bobStore.processScannedQr(qr, "Bob")
        bob.postKeyExchangeInit(bobEntry.id, qr, init)
        val pending = alice.pollPendingInvites()
        requireNotNull(aliceStore.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))
        return Peers(alice, aliceStore, aliceLink, bob, bobStore, bobLink, bobEntry.id)
    }

    // A send that fails (no network) is retried from the outbox later, as in the apps.
    private companion object {
        val retentionDays = MAILBOX_RETENTION_SECONDS / 86400

        suspend fun LocationClient.share(lat: Double) {
            runCatching { sendLocation(lat, 0.0, stamp = sharingStamp()) }
        }
    }

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
     * app is stopped for her first [aliceAwayDays] (which may end before or after Bob's return)
     * and has no network for her first [aliceOfflineDays]; otherwise it runs in the background
     * (every 5 min, hourly once the other side is paused), and so does Bob's once he's back. When
     * both are back they use the app for a few minutes.
     */
    private suspend fun absence(
        retentionSeconds: Long,
        awayDays: Long,
        aliceSharing: Boolean,
        aliceAwayDays: Long = 0,
        aliceOfflineDays: Long = 0,
        bobFirstPollFails: Boolean = false,
    ): Outcome {
        val p = pair(TtlMailboxClient(retentionSeconds))
        p.warmUp(clock)

        // Bob leaves (and maybe Alice for a while).
        val left = currentTimeSeconds()
        val awayUntil = left + awayDays * 86400
        val aliceBackAt = left + aliceAwayDays * 86400
        val aliceOnlineAt = left + aliceOfflineDays * 86400
        while (currentTimeSeconds() < awayUntil) {
            if (currentTimeSeconds() >= aliceBackAt) {
                p.aliceLink.offline = currentTimeSeconds() < aliceOnlineAt
                p.alice.poll(isForeground = false, sharingEnabled = aliceSharing)
                if (aliceSharing) p.alice.share(1.0)
            }
            clock.advanceSeconds(if (p.aliceView().isStale) 3600 else 300)
        }
        val paused = p.aliceView().isStale
        assertTrue(paused == (awayDays * 86400 > FriendEntry.INACTIVITY_PAUSE_SECONDS), "Alice pauses Bob only after 7 days")

        // Bob comes back and opens the app.
        val back = currentTimeSeconds()
        val aliceWarned = p.aliceView().mayNeedRepair
        val bobWarnedBeforePolling = p.bobView().mayNeedRepair
        var bobWarnedAfterFailedPoll = false
        if (bobFirstPollFails) {
            p.bobLink.offline = true
            p.bob.poll()
            p.bobLink.offline = false
            bobWarnedAfterFailedPoll = p.bobView().mayNeedRepair
        }
        // Bob is back before Alice: his app runs alone until hers does.
        while (currentTimeSeconds() < aliceBackAt) {
            p.bob.poll(isForeground = false)
            p.bob.share(2.0)
            clock.advanceSeconds(if (p.bobView().isStale) 3600 else 300)
        }
        p.aliceLink.offline = false

        // A few minutes of both apps in the foreground: enough polls to drain a ~2,000-frame
        // backlog (500 per poll) and carry the ratchet round trips that follow.
        val bothBack = currentTimeSeconds()
        var bobGotFreshLocation = false
        repeat(12) {
            val updates = p.bob.poll()
            if (updates.any { it.timestamp >= bothBack }) bobGotFreshLocation = true
            p.bob.share(2.0)
            clock.advanceSeconds(10)
            p.alice.poll(sharingEnabled = aliceSharing)
            if (aliceSharing) p.alice.share(1.0)
            clock.advanceSeconds(10)
        }

        val alice = p.aliceView()
        val bob = p.bobView()
        val heard = alice.lastRecvTs >= bothBack && bob.lastRecvTs >= bothBack && !alice.isStale && !bob.isStale
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
            val days = retentionDays - 1
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = days, aliceSharing = true)
            assertTrue(o.resumed)
            assertTrue(o.aliceWarned, "the warning comes before the break")
            assertFalse(o.bobWarnedBeforePolling, "not before the returning side has polled")
            assertFalse(o.warnedAtEnd, "and clears once they hear from each other")
        }

    @Test
    fun aFailedFirstPollOnReturnShowsNoWarning() =
        runTest {
            val days = retentionDays - 1
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
    fun survivesBothSidesBeingAway_returningInEitherOrder() =
        runTest {
            // Bob's app is stopped for 20 days, Alice's for 60: Bob is back first and finds nothing.
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = 20, aliceSharing = true, aliceAwayDays = 60)
            assertTrue(o.resumed)
            assertFalse(o.warnedAtEnd)
        }

    @Test
    fun aTransitionFrameDeliveredLateLastsARetentionFromItsPost() =
        runTest {
            // Alice has no network for the first 20 days, so her transition frame to Bob waits in
            // her outbox and reaches the server on day 20. Bob is back on day 100, past a retention
            // from his leaving but within one from that post: it still works. Alice's warning
            // (judged from his silence) showed, and clears.
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = 100, aliceSharing = true, aliceOfflineDays = 20)
            assertTrue(o.aliceWarned, "the warning is a hint: it showed")
            assertTrue(o.resumed)
            assertFalse(o.warnedAtEnd)
        }

    @Test
    fun aPausedFriendGetsOneLastKeepaliveAndThenNothing() =
        runTest {
            val mailbox = TtlMailboxClient(MAILBOX_RETENTION_SECONDS)
            val p = pair(mailbox)
            p.warmUp(clock, rounds = 1)
            // Bob's app stops; Alice's keeps polling with sharing off, for 30 days. (Offset so her
            // regular keepalives don't land exactly on the pause, which would make one of them
            // the last frame and the parting keepalive unnecessary.)
            val pausedAt = currentTimeSeconds() + FriendEntry.INACTIVITY_PAUSE_SECONDS
            clock.advanceSeconds(7)
            val until = currentTimeSeconds() + 30 * 86400
            while (currentTimeSeconds() < until) {
                p.alice.poll(isForeground = false, sharingEnabled = false)
                clock.advanceSeconds(if (p.aliceView().isStale) 3600 else 300)
            }
            assertTrue(p.aliceView().isStale)
            assertEquals(1, mailbox.postTimes.count { it > pausedAt }, "exactly one frame after the pause")
        }

    @Test
    fun aStopWhileTheFriendIsPausedStillReachesThem() =
        runTest {
            // Bob's app stops; on day 9 (Bob already paused) Alice stops sharing. Bob comes back on
            // day 10 and must see her as stopped, not her last pin from before the pause as current.
            val p = pair(TtlMailboxClient(MAILBOX_RETENTION_SECONDS))
            p.warmUp(clock)
            val until = currentTimeSeconds() + 9 * 86400
            while (currentTimeSeconds() < until) {
                p.alice.poll(isForeground = false)
                p.alice.share(1.0)
                clock.advanceSeconds(if (p.aliceView().isStale) 3600 else 300)
            }
            assertTrue(p.aliceView().isStale)
            p.alice.sendStoppedSharing()
            clock.advanceSeconds(86400)
            repeat(8) { p.bob.poll() } // ~2,000 waiting frames at 500 per poll; the stop is last
            assertTrue(p.bobView().stoppedAtTs != null, "Bob sees that Alice stopped")
        }

    @Test
    fun noPartingKeepalivePastRetention() =
        runTest {
            // Alice's app is off for longer than the retention: the friendship is past saving, so
            // her return doesn't spend a write on it.
            val mailbox = TtlMailboxClient(MAILBOX_RETENTION_SECONDS)
            val p = pair(mailbox)
            p.warmUp(clock, rounds = 1)
            clock.advanceSeconds(MAILBOX_RETENTION_SECONDS + 86400)
            val back = currentTimeSeconds()
            p.alice.poll(isForeground = false, sharingEnabled = false)
            assertEquals(0, mailbox.postTimes.count { it >= back })
        }

    @Test
    fun breaksWellPastRetention() =
        runTest {
            val days = retentionDays + 10
            val o = absence(MAILBOX_RETENTION_SECONDS, awayDays = days, aliceSharing = true)
            assertFalse(o.resumed)
            assertTrue(o.aliceWarned)
            assertTrue(o.warnedAtEnd, "still shown after Bob's return finds nothing")
        }
}
