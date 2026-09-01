package net.af0.where.e2ee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression/behavior tests for throttling sends to a friend whose client hasn't sent us
 * anything in a while - see sendLocation()'s activeFriends filter - and for the automated
 * keepalive (pollFriend), which fires whenever we haven't sent a friend anything (real or
 * keepalive) in UNRESPONSIVE_SEND_INTERVAL_SECONDS, regardless of paused/sharing state. The two
 * share the same lastSentTs clock, so in normal operation sendLocation's own cadence for an
 * actively-shared friend keeps that clock fresh and the keepalive never engages - it only acts as
 * a backstop for a paused/unshared friend (its sole source of traffic) or for an actively-shared
 * friend whose real-send trigger has stalled. Uses a virtual clock (TestScope.currentTime) so
 * "5 minutes of silence" doesn't require a real 5-minute test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnresponsiveFriendThrottleTest {
    init {
        initializeE2eeTests()
    }

    private fun TestScope.setVirtualTime(baseTimeMs: Long) {
        TimeSource.setProvider(
            object : TimeProvider {
                override fun currentTimeMillis() = baseTimeMs + testScheduler.currentTime

                override fun currentTimeSeconds() = (baseTimeMs + testScheduler.currentTime) / 1000

                override fun formatLocalTime(seconds: Long): String = platformFormatLocalTime(seconds)
            },
        )
    }

    private suspend fun pairedClients(mailbox: MailboxClient): Pair<LocationClient, LocationClient> {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = testE2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = testE2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)

        return aliceClient to bobClient
    }

    private suspend fun pairNewFriend(
        aliceManager: E2eeManager,
        aliceClient: LocationClient,
        mailbox: MailboxClient,
        name: String,
    ): LocationClient {
        val friendDriver = createTestSqlDriver()
        val friendManager = testE2eeManager(friendDriver)
        val friendClient = LocationClient("http://localhost", friendManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val (initPayload, friendEntry) = friendManager.processScannedQr(qr, name)
        friendClient.postKeyExchangeInit(friendEntry.id, qr, initPayload)

        val pending = aliceClient.pollPendingInvites()
        aliceManager.processKeyExchangeInit(pending.last().payload, name, pending.last().inviteEkPub)

        return friendClient
    }

    @Test
    fun `a failed send to an unresponsive friend retries at the next normal cycle, not the full throttle interval`() =
        runTest {
            val realMailbox = MemoryMailboxClient()
            val mailbox = ChaosMailboxClient(realMailbox)
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(400_000L) // t=400s: unresponsive, throttled send is due.
            mailbox.failNextPost = true
            // Alice only has one friend here, so this send fails for ALL active friends -
            // sendLocation() rethrows in that case (see fe74ce0). Generation + the failed attempt
            // still happened, leaving the message stuck in the outbox, which is what we're testing.
            assertFailsWith<NetworkException> {
                aliceClient.sendLocation(1.0, 1.0, emptySet())
            }
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "the failed attempt must not have been delivered",
            )

            // Network "recovers". Only a few seconds later - nowhere near a full 5-minute throttle
            // window - matching the normal (untouched) WAL-outbox retry cadence for any other friend.
            // This call both flushes the stuck message AND generates+sends a fresh one once the
            // outbox clears (same WAL-safety fall-through as #344's retry test) - both must land.
            advanceTimeBy(5_000L)
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(2, delivered.size, "the originally stuck message must retry promptly, not wait out the throttle window")
            assertEquals(1.0, delivered[0].lat, "the originally stuck message must be delivered first, in order")
            assertEquals(2.0, delivered[1].lat)
        }

    @Test
    fun `throttling one unresponsive friend does not affect sends to a responsive friend`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)

            val aliceDriver = createTestSqlDriver()
            val aliceManager = testE2eeManager(aliceDriver)
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            val bobClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Bob")
            val charlieClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Charlie")
            // This test is about ALICE's send-side throttle specifically - Bob and Charlie's own
            // automated-keepalive backstop (which would otherwise fire on their poll() calls below,
            // since neither ever calls sendLocation themselves) is disabled so their polling doesn't
            // send Alice anything that would reset her view of their responsiveness.
            bobClient.enableAutomatedKeepalives = false
            charlieClient.enableAutomatedKeepalives = false

            // Both friends heard from at t=0. Alice is actively sharing with both (default
            // sharingEnabled=true), so sendLocation is the sole source of Alice's outbound traffic
            // throughout this test - her own automated-keepalive backstop won't engage either,
            // since her lastSentTs to both stays well under the throttle interval throughout.
            bobClient.sendLocation(0.0, 0.0, emptySet())
            charlieClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(350_000L)
            aliceClient.sendLocation(1.0, 1.0, emptySet()) // both unresponsive-but-due - fans out to both.
            bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            charlieClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(50_000L) // t=400s: only 50s since Bob's last send - well under the throttle interval.
            charlieClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            aliceClient.sendLocation(2.0, 2.0, emptySet()) // fans out to both in one call.
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "Bob is unresponsive and not yet due - must be throttled",
            )
            val toCharlie = charlieClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, toCharlie.size, "Charlie is responsive - one unresponsive friend must not affect sends to another")
        }

    @Test
    fun `sends continue normally while the friend has been heard from recently`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            // Establishes Alice's lastRecvTs for Bob at t=0.
            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(250_000L) // 250s: under the 300s threshold.
            aliceClient.sendLocation(1.0, 1.0, emptySet())

            val received = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, received.size, "still within the responsiveness window, must send normally")
        }

    @Test
    fun `send to an unresponsive friend is throttled, then allowed once the interval elapses`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(250_000L) // t=250s: still responsive, this send also sets lastSentTs=250.
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            assertEquals(1, bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).size)

            advanceTimeBy(60_000L) // t=310s: now unresponsive (310s of silence), but only 60s since our last send.
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            assertTrue(
                bobClient.poll(isForeground = true, pausedFriendIds = emptySet()).isEmpty(),
                "unresponsive and not yet due for a throttled retry - must not send",
            )

            advanceTimeBy(250_000L) // t=560s: 310s since our last send - now due.
            aliceClient.sendLocation(3.0, 3.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "throttle interval elapsed - must send the throttled update")
            assertEquals(3.0, delivered[0].lat)
        }

    @Test
    fun `while paused, repeated background polling sends periodic keepalives, not one per poll - and resuming sharing still sends`() =
        runTest {
            // Regression test for the automated keepalive (pollFriend): while paused/not-sharing,
            // it must self-pace to UNRESPONSIVE_SEND_INTERVAL_SECONDS via the shared lastSentTs
            // clock rather than firing on every background poll (constant traffic, defeating the
            // whole point). And once sharing resumes, sendLocation must still get a real update
            // through promptly - being paused earlier must not wedge future real sends.
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet()) // still sharing here - no keepalive.
            val postsAfterFirstPoll = mailbox.allPosted.size

            // Alice pauses sharing (or turns it off entirely) and just does normal background
            // maintenance polling (every 30s) for two full throttle windows.
            repeat(20) {
                advanceTimeBy(30_000L)
                aliceClient.poll(isForeground = true, pausedFriendIds = emptySet(), sharingEnabled = false)
            }

            assertEquals(
                2,
                mailbox.allPosted.size - postsAfterFirstPoll,
                "must send exactly one keepalive per throttle interval elapsed while paused, not one per 30s poll",
            )

            // Alice resumes sharing and has a real location to send.
            advanceTimeBy(300_000L)
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "a real location must still get through once due, not be blocked by prior periodic keepalives")
            assertEquals(1.0, delivered[0].lat)
        }

    @Test
    fun `an actively-shared friend still gets a backstop keepalive if the heartbeat trigger stalls for multiple cycles`() =
        runTest {
            // Regression test for e6edf01, which gated the automated keepalive on isPaused only -
            // removing the safety net for an actively-shared friend whose sendLocation() heartbeat
            // trigger stalls (e.g. a stuck background wake source leaving a stationary device
            // silent for hours - see the "here since" investigation). The keepalive must fire as a
            // backstop once genuinely due (AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS - several multiples
            // of the normal throttle interval, not the interval itself - see the "starves a merely-
            // unresponsive friend" regression test below for why it can't be the same interval).
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            aliceClient.sendLocation(1.0, 1.0, emptySet()) // establishes a fresh lastSentTs baseline.
            val postsAfterFirstSend = mailbox.allPosted.size

            // Normal 30s background polling cadence with no further real sends - simulating a
            // stalled heartbeat trigger. Must stay quiet until genuinely due.
            repeat(29) {
                advanceTimeBy(30_000L)
                aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            }
            assertEquals(
                0,
                mailbox.allPosted.size - postsAfterFirstSend,
                "must not fire before AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS elapses",
            )

            advanceTimeBy(30_000L) // total 900s elapsed since the last real send - now due.
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(
                1,
                mailbox.allPosted.size - postsAfterFirstSend,
                "must fire as a backstop once genuinely due, even while actively sharing",
            )
        }

    @Test
    fun `an unresponsive-but-actively-shared friend still gets real Locations, not just Keepalives forever`() =
        runTest {
            // Regression test for a starvation bug in the initial version of the backstop fix
            // above: pollFriend (driven by doPoll) and sendLocation's own unresponsive-friend
            // retry both read/reset the same lastSentTs clock. On real devices doPoll() always
            // runs before the heartbeat's sendLocation() call within the same wake cycle
            // (LocationService.kt/LocationSyncService.swift), so if the backstop used
            // sendLocation's own UNRESPONSIVE_SEND_INTERVAL_SECONDS threshold, it would win that
            // race on every cycle - resetting lastSentTs moments before sendLocation's throttle
            // check runs, so the throttled retry never looks "due" and Bob gets an unbroken string
            // of empty Keepalives instead of the real Location sendLocation was supposed to
            // deliver once due. Simulates that exact ordering (poll-then-sendLocation, repeatedly)
            // and asserts a real Location eventually gets through.
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)
            // Bob's own automated-keepalive backstop is irrelevant to what this test is checking
            // (Alice's send-side behavior toward an unresponsive friend) - disabled so Bob's poll
            // calls below (needed to inspect what Alice delivered) don't themselves send Alice
            // anything and inadvertently make Bob look responsive again.
            bobClient.enableAutomatedKeepalives = false

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
            aliceClient.sendLocation(1.0, 1.0, emptySet()) // lastRecvTs/lastSentTs baseline at t=0.

            // Bob goes quiet (never sends again) while Alice keeps running her normal
            // ~5-minute wake cycle: poll (which runs pollFriend) immediately followed by a
            // heartbeat sendLocation call, matching the real service loop's ordering.
            var delivered: List<net.af0.where.model.UserLocation> = emptyList()
            repeat(6) {
                advanceTimeBy(300_000L)
                aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
                aliceClient.sendLocation(2.0, 2.0, emptySet())
                delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            }

            assertTrue(
                delivered.any { it.lat == 2.0 },
                "a real Location must eventually reach an unresponsive-but-actively-shared friend, " +
                    "not just empty Keepalives",
            )
        }

    @Test
    fun `responsiveness recovers immediately once the friend sends anything, no extra cooldown`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)
            val (aliceClient, bobClient) = pairedClients(mailbox)

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(400_000L) // t=400s: unresponsive.
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            bobClient.poll(isForeground = true, pausedFriendIds = emptySet()) // drain the throttled send

            // Bob sends a keepalive (or anything) - Alice's lastRecvTs updates immediately on poll.
            bobClient.sendLocation(9.0, 9.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            advanceTimeBy(1_000L) // barely any time later - well under the throttle interval.
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            val delivered = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())
            assertEquals(1, delivered.size, "must resume full cadence right away once the friend is heard from again")
        }

    @Test
    fun `sendLocation logs a diagnostic event when every active friend is throttled, not a silent no-op`() =
        runTest {
            // Regression test for #347: sendLocation() returns normally (no exception) when
            // activeFriends is empty, so LocationService.sendLocationIfNeeded previously logged
            // "OK" and cleared its status for a send that reached nobody. A friend being paused
            // or there being no friends yet is expected and must stay silent; a friend being
            // throttled as unresponsive is operationally interesting and must be visible.
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)

            val aliceDriver = createTestSqlDriver()
            val aliceManager = testE2eeManager(aliceDriver)
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            val bobClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Bob")

            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            // Nothing logged yet - Bob is still responsive.
            assertTrue(aliceManager.diagnosticLog.value.none { it.contains("throttled as unresponsive") })

            advanceTimeBy(250_000L) // t=250s: still responsive, sets lastSentTs=250.
            aliceClient.sendLocation(1.0, 1.0, emptySet())
            assertTrue(aliceManager.diagnosticLog.value.none { it.contains("throttled as unresponsive") })

            advanceTimeBy(60_000L) // t=310s: unresponsive, and not yet due for a throttled retry.
            aliceClient.sendLocation(2.0, 2.0, emptySet())
            assertTrue(
                aliceManager.diagnosticLog.value.any { it.contains("throttled as unresponsive") },
                "must surface that this send reached nobody, not report a silent success",
            )
        }

    @Test
    fun `sendLocation stays silent when there are simply no active friends`() =
        runTest {
            val mailbox = MemoryMailboxClient()
            setVirtualTime(1_700_000_000_000L)

            val aliceDriver = createTestSqlDriver()
            val aliceManager = testE2eeManager(aliceDriver)
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            val bobClient = pairNewFriend(aliceManager, aliceClient, mailbox, "Bob")
            bobClient.sendLocation(0.0, 0.0, emptySet())
            aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())

            // Bob is explicitly paused, not unresponsive - this is a normal, expected no-op.
            aliceClient.sendLocation(1.0, 1.0, pausedFriendIds = aliceManager.listFriends().map { it.id }.toSet())
            assertTrue(aliceManager.diagnosticLog.value.none { it.contains("throttled as unresponsive") })
        }
}
