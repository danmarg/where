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

    // ~1.1 km per step, comfortably past the 100 m filter.
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
            assertEquals(1, h.sender.sent.size, "under 100 m: nothing before the heartbeat")

            h.scheduler.onFix(37.0 + 0.0014, -122.0) // ~155 m from the delivered fix
            runCurrent()
            assertEquals(2, h.sender.sent.size, "past 100 m: sent once the interval allows")
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
    fun wakeAndDrainReportsWhatItLeftBehind() =
        runTest {
            val h = harness()
            var result: DrainResult? = null

            // As Android's pollLoop does: report the fix, then drain, without yielding between.
            fun drain(fixLat: Double? = null) {
                result = null
                launch {
                    if (fixLat != null) h.scheduler.onFix(fixLat, -122.0)
                    result = h.scheduler.wakeAndDrain()
                }
                runCurrent()
            }

            drain()
            assertEquals(DrainResult.Idle, result, "no fix yet")

            drain(far(0))
            assertEquals(DrainResult.Sent, result)

            advanceTimeBy(10_000L)
            drain(far(1))
            assertEquals(DrainResult.Pending(20_000L), result, "moved, but inside the send interval")

            advanceTimeBy(20_000L)
            runCurrent() // the worker's own timer sends it
            drain()
            assertEquals(DrainResult.Idle, result)

            h.sender.fail = { RuntimeException("offline") }
            advanceTimeBy(300_000L)
            launch { result = h.scheduler.wakeAndDrain() }
            advanceTimeBy(30_000L) // through the retries
            runCurrent()
            assertEquals(DrainResult.Failed, result)
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
    fun aRequestTimeoutIsAFailedAttemptNotTheEndOfTheWorker() =
        runTest {
            // The mailbox's wall-clock timeout surfaces as a CancellationException subclass. It
            // must not end the worker (or wakeAndDrain would wait forever on a stuck connection).
            val h = harness()
            var timingOut = true
            h.sender.fail = { if (timingOut) kotlinx.coroutines.CancellationException("wall-clock timeout") else null }
            h.scheduler.onFix(far(0), -122.0)
            advanceTimeBy(30_000L)
            runCurrent()
            assertEquals(3, h.sender.attempts.size, "retried like any failure")

            timingOut = false
            h.scheduler.onNetworkRestored()
            h.scheduler.onFix(far(1), -122.0)
            advanceTimeBy(300_000L)
            var drained = false
            launch {
                h.scheduler.wakeAndDrain()
                drained = true
            }
            runCurrent()
            assertEquals(true, drained, "the worker is alive to drain the heartbeat")
            assertEquals(listOf(far(1), far(1)), h.sender.sent.map { it.lat }) // the fix, then the heartbeat
        }

    @Test
    fun eventsStartTheWorkerIfNeeded() =
        runTest {
            val clock = { BASE + testScheduler.currentTime }
            val sender = FakeSender(clock)
            val scheduler = LocationSendScheduler(sender, FakeGate(), clock, backgroundScope) // never start()ed
            scheduler.onFix(37.0, -122.0)
            var drained = false
            launch {
                scheduler.wakeAndDrain()
                drained = true
            }
            runCurrent()
            assertEquals(true, drained)
            assertEquals(1, sender.sent.size)
        }

    @Test
    fun aClockThatJumpsBackDoesNotShutTheGate() =
        runTest {
            var offset = 0L
            val clock = { BASE + testScheduler.currentTime + offset }
            val sender = FakeSender(clock)
            val scheduler = LocationSendScheduler(sender, FakeGate(), clock, backgroundScope)
            scheduler.onFix(far(0), -122.0)
            runCurrent()
            offset = -86_400_000L // a day back
            advanceTimeBy(31_000L)
            scheduler.onFix(far(1), -122.0)
            runCurrent()
            advanceTimeBy(31_000L)
            runCurrent()
            assertEquals(2, sender.sent.size)
            assertTrue(scheduler.isHeartbeatDue().not())
            advanceTimeBy(300_000L)
            assertTrue(scheduler.isHeartbeatDue())
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
            h.scheduler.noteKeepalive(success = false)
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
                // Moving spells average ~1 min, stationary ones ~8 min, so heartbeats run.
                if (scenario.nextInt(if (moving) 20 else 160) == 0) moving = !moving
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
                val metersApart = kotlin.math.abs(a.lat - b.lat) * METERS_PER_DEGREE
                if (metersApart < 100 && a.stationary == b.stationary && !forced) {
                    assertTrue(gap >= 270_000L, "$ctx: unchanged fix re-sent after $gap ms")
                }
            }
            // Heartbeat at most 5 min after the last attempt, caught by the next 1-min wake; a failed
            // round (3 slow attempts) then backs off one more heartbeat.
            val longestGap = h.sender.sent.zipWithNext().maxOf { (a, b) -> b.start - a.start }
            assertTrue(longestGap <= 2 * (300_000L + 60_000L + 3 * 45_000L + 25_000L), "$ctx: heartbeat gap $longestGap ms")
            val heartbeats = h.sender.sent.zipWithNext().count { (a, b) -> kotlin.math.abs(a.lat - b.lat) * METERS_PER_DEGREE < 100 }
            assertTrue(heartbeats > 3, "$ctx: the heartbeat path ran ($heartbeats times)")
        }

    private companion object {
        const val BASE = 1_000_000_000L

        // Of latitude, on the scheduler's sphere (6,371 km), so boundary cases agree with it.
        const val METERS_PER_DEGREE = 6_371_000.0 * kotlin.math.PI / 180
    }
}
