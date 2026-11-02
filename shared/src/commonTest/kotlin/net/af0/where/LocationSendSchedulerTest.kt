package net.af0.where

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationSendSchedulerTest {
    private class Send(val start: Long, val lat: Double, val lng: Double, val stationary: Boolean)

    /** Records sends; [latency] and [fail] shape each attempt. Flags any overlap. */
    private class FakeSender(private val clock: () -> Long) : BroadcastSender {
        var latency: () -> Long = { 0L }
        var fail: () -> Throwable? = { null }
        val attempts = mutableListOf<Send>()
        val sent = mutableListOf<Send>()
        var inFlight = 0
        var overlapped = false

        override fun sharingStamp() = 0L

        override suspend fun sendLocation(
            lat: Double,
            lng: Double,
            pausedFriendIds: Set<String>,
            stationary: Boolean,
            stamp: Long,
        ) {
            val attempt = Send(clock(), lat, lng, stationary)
            attempts += attempt
            if (inFlight > 0) overlapped = true
            inFlight++
            try {
                delay(latency())
                fail()?.let { throw it }
                sent += attempt
            } finally {
                inFlight--
            }
        }
    }

    private class FakeGate : SendGate {
        var canSend = true
        var stationary = false
        var canSendCalls = 0

        override fun canSend(): Boolean {
            canSendCalls++
            return canSend
        }

        override fun pausedFriendIds() = emptySet<String>()

        override fun stationary() = stationary
    }

    private class Harness(test: TestScope, scope: CoroutineScope) {
        val clock = { BASE + test.testScheduler.currentTime }
        val sender = FakeSender(clock)
        val gate = FakeGate()
        val scheduler = LocationSendScheduler(sender, gate, clock, scope).also { it.start() }
    }

    private fun TestScope.harness() = Harness(this, backgroundScope)

    // ~1.1 km per step, comfortably past the 200 m filter.
    private fun far(i: Int) = 37.0 + i * 0.01

    @Test
    fun firstFixIsSentRightAway() =
        runTest {
            val h = harness()
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            assertEquals(listOf(37.0), h.sender.sent.map { it.lat })
        }

    @Test
    fun burstOfFixesIsThrottledAndTheLastFixStillGoesOut() =
        runTest {
            val h = harness()
            for (i in 0 until 100) {
                h.scheduler.onFix(far(i), -122.0)
                runCurrent()
                advanceTimeBy(1_000L)
            }
            advanceTimeBy(60_000L)
            runCurrent()
            val starts = h.sender.sent.map { it.start }
            assertTrue(starts.zipWithNext().all { (a, b) -> b - a >= 30_000L }, "starts $starts")
            assertEquals(far(99), h.sender.sent.last().lat, "the last fix goes out once the window opens")
            assertEquals(5, h.sender.sent.size) // t = 0, 30, 60, 90 s, and 120 s for the 99 s fix
        }

    @Test
    fun smallMovesWaitForTheHeartbeat() =
        runTest {
            val h = harness()
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            for (i in 1..60) {
                advanceTimeBy(1_000L)
                h.scheduler.onFix(37.0 + i * 0.00001, -122.0) // ~1 m steps, 60 m in all
                runCurrent()
            }
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(1, h.sender.sent.size, "under 200 m: nothing before the heartbeat")
        }

    @Test
    fun heartbeatResendsTheLatestFixOnlyWhenAWakeArmsIt() =
        runTest {
            val h = harness()
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()

            advanceTimeBy(200_000L)
            launch { h.scheduler.wakeAndDrain() }
            runCurrent()
            assertEquals(1, h.sender.sent.size, "not due yet")

            advanceTimeBy(80_000L) // 280 s: inside the early slack
            launch { h.scheduler.wakeAndDrain() }
            runCurrent()
            assertEquals(2, h.sender.sent.size)
            assertEquals(37.0, h.sender.sent.last().lat)

            advanceTimeBy(600_000L) // no wake, no heartbeat: the platform decides when it's awake
            runCurrent()
            assertEquals(2, h.sender.sent.size)
        }

    @Test
    fun wakeAndDrainReturnsOnlyOnceTheSendIsDone() =
        runTest {
            val h = harness()
            h.sender.latency = { 5_000L }
            h.scheduler.onFix(37.0, -122.0)
            var drained = false
            launch {
                h.scheduler.wakeAndDrain()
                drained = true
            }
            runCurrent()
            assertEquals(false, drained)
            advanceTimeBy(5_001L)
            runCurrent()
            assertEquals(true, drained)
            assertEquals(1, h.sender.sent.size)
        }

    @Test
    fun stationaryChangeIsSentEvenWithoutMovement() =
        runTest {
            val h = harness()
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            advanceTimeBy(40_000L)
            h.gate.stationary = true
            launch { h.scheduler.wakeAndDrain() }
            runCurrent()
            assertEquals(listOf(false, true), h.sender.sent.map { it.stationary })
        }

    @Test
    fun forcedSendSkipsTheIntervalButNotASendInFlight() =
        runTest {
            val h = harness()
            h.sender.latency = { 5_000L }
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            h.scheduler.requestForced()
            runCurrent()
            assertEquals(1, h.sender.attempts.size, "waits for the send in flight")
            advanceTimeBy(5_001L)
            runCurrent()
            assertEquals(2, h.sender.attempts.size, "then goes at once, inside the 30 s window")
            assertEquals(false, h.sender.overlapped)
        }

    @Test
    fun forcedSendWithNoFixDoesNothing() =
        runTest {
            val h = harness()
            h.scheduler.requestForced()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(0, h.sender.attempts.size)
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            assertEquals(1, h.sender.attempts.size, "it applies to the first fix")
        }

    @Test
    fun skippedSendParksUntilTheNextEvent() =
        runTest {
            val h = harness()
            h.gate.canSend = false
            h.scheduler.onFix(37.0, -122.0)
            advanceTimeBy(600_000L)
            runCurrent()
            assertEquals(0, h.sender.attempts.size)
            assertEquals(1, h.gate.canSendCalls, "doesn't re-check the same state in a loop")

            h.gate.canSend = true
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            assertEquals(1, h.sender.sent.size)
        }

    @Test
    fun retriesSendTheLatestFix() =
        runTest {
            val h = harness()
            var failures = 1
            h.sender.fail = { if (failures-- > 0) RuntimeException("offline") else null }
            h.scheduler.onFix(far(0), -122.0)
            runCurrent()
            h.scheduler.onFix(far(1), -122.0)
            advanceTimeBy(5_001L)
            runCurrent()
            assertEquals(listOf(far(0), far(1)), h.sender.attempts.map { it.lat })
            assertEquals(far(1), h.sender.sent.single().lat)
        }

    @Test
    fun offlineCostsOneRoundOfAttemptsPerHeartbeat() =
        runTest {
            val h = harness()
            h.sender.fail = { RuntimeException("offline") }
            for (i in 0 until 3600) {
                h.scheduler.onFix(far(i), -122.0)
                if (i % 60 == 0) launch { h.scheduler.wakeAndDrain() }
                runCurrent()
                advanceTimeBy(1_000L)
            }
            val rounds = 3600 / 300 + 1
            assertTrue(h.sender.attempts.size <= rounds * 3, "${h.sender.attempts.size} attempts in an hour offline")
        }

    @Test
    fun networkRestoreEndsTheBackoff() =
        runTest {
            val h = harness()
            var offline = true
            h.sender.fail = { if (offline) RuntimeException("offline") else null }
            h.scheduler.onFix(far(0), -122.0)
            advanceTimeBy(30_000L) // all three attempts fail
            runCurrent()
            h.scheduler.onFix(far(1), -122.0)
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(3, h.sender.attempts.size, "backing off")

            offline = false
            h.scheduler.onNetworkRestored()
            runCurrent()
            assertEquals(far(1), h.sender.sent.single().lat)
        }

    @Test
    fun aSenderThatThrowsUnexpectedlyDoesNotStopTheWorker() =
        runTest {
            val h = harness()
            var broken = true
            h.sender.fail = { if (broken) IllegalStateException("bug") else null }
            h.scheduler.onFix(far(0), -122.0)
            advanceTimeBy(30_000L)
            runCurrent()
            broken = false
            h.scheduler.onNetworkRestored()
            h.scheduler.onFix(far(1), -122.0)
            advanceTimeBy(30_000L) // the interval since the last attempt still applies
            runCurrent()
            assertEquals(far(1), h.sender.sent.single().lat)
        }

    @Test
    fun resetDiscardsASendSettlingFromBefore() =
        runTest {
            val h = harness()
            h.sender.latency = { 5_000L }
            h.scheduler.onFix(37.0, -122.0)
            runCurrent()
            h.scheduler.reset()
            advanceTimeBy(5_001L)
            runCurrent()
            // The old session's send doesn't count as delivered: the same fix goes out again.
            advanceTimeBy(30_000L)
            h.scheduler.onFix(37.0, -122.0)
            advanceTimeBy(5_001L)
            runCurrent()
            assertEquals(2, h.sender.sent.size)
        }

    @Test
    fun keepaliveCountsAsTheHeartbeat() =
        runTest {
            val h = harness()
            advanceTimeBy(400_000L)
            assertTrue(h.scheduler.isHeartbeatDue())
            h.scheduler.noteKeepalive()
            assertEquals(false, h.scheduler.isHeartbeatDue(), "no forced GPS fix on every wake after a keepalive")
        }

    @Test
    fun inaccurateFixesAreIgnored() =
        runTest {
            val h = harness()
            h.scheduler.onFix(37.0, -122.0, accuracyMeters = 1_500.0)
            runCurrent()
            assertEquals(0, h.sender.attempts.size)
        }

    /**
     * An hour of virtual time per seed: bursts of moving fixes, stationary spells with small
     * jitter, stationary flips, forced requests, periodic wakes, sends taking up to 45 s, and
     * failures. Invariants: no overlap; attempts at least 30 s apart unless forced or a retry;
     * an unchanged fix isn't re-sent before the heartbeat; the heartbeat runs while stationary;
     * the last fix goes out after a quiet spell.
     */
    @Test
    fun randomizedSimulationKeepsTheInvariants() {
        for (seed in 1..20) simulate(seed)
    }

    private fun simulate(seed: Int) =
        runTest {
            val scenario = Random(seed)
            val network = Random(seed + 1000)
            val h = harness()
            var failures = true
            h.sender.latency = { if (network.nextInt(20) == 0) network.nextLong(30_000L, 45_000L) else network.nextLong(0L, 8_000L) }
            h.sender.fail = { if (failures && network.nextInt(10) == 0) RuntimeException("offline") else null }
            val forcedAt = mutableListOf<Long>()

            var lat = 37.0
            var moving = true
            val end = 3_600_000L
            var nextWake = 60_000L
            while (testScheduler.currentTime < end) {
                if (scenario.nextInt(30) == 0) moving = !moving
                lat += if (moving) 0.0005 else scenario.nextDouble(-0.00001, 0.00001)
                h.scheduler.onFix(lat, -122.0)
                if (scenario.nextInt(200) == 0) h.gate.stationary = !h.gate.stationary
                if (scenario.nextInt(400) == 0) {
                    forcedAt += h.clock()
                    h.scheduler.requestForced()
                }
                if (testScheduler.currentTime >= nextWake) {
                    nextWake += 60_000L
                    launch { h.scheduler.wakeAndDrain() }
                }
                runCurrent()
                advanceTimeBy(scenario.nextLong(1_000L, 5_000L))
            }
            failures = false
            lat += 0.01
            h.scheduler.onFix(lat, -122.0)
            advanceTimeBy(120_000L)
            runCurrent()

            val ctx = "seed $seed"
            assertEquals(false, h.sender.overlapped, "$ctx: sends overlapped")
            assertEquals(lat, h.sender.sent.last().lat, "$ctx: the last fix never went out")
            for ((a, b) in h.sender.sent.zipWithNext()) {
                val gap = b.start - a.start
                val forced = forcedAt.any { it in a.start + 1..b.start }
                if (!forced) assertTrue(gap >= 30_000L, "$ctx: sends at ${a.start} and ${b.start} are $gap ms apart")
                if (a.lat == b.lat && a.stationary == b.stationary && !forced) {
                    assertTrue(gap >= 270_000L, "$ctx: unchanged fix re-sent after $gap ms")
                }
            }
            val longestGap = h.sender.sent.zipWithNext().maxOf { (a, b) -> b.start - a.start }
            assertTrue(longestGap <= 300_000L + 60_000L + 3 * 45_000L + 25_000L, "$ctx: heartbeat gap $longestGap ms")
        }

    private companion object {
        const val BASE = 1_000_000_000L
    }
}
