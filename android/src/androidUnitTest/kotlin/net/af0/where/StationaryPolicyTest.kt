package net.af0.where

import net.af0.where.StationaryPolicy.Mode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationaryPolicyTest {
    private val policy = StationaryPolicy()
    private val idle = policy.idleMs

    /** A fix [northMeters] north of the origin at [at], taken [ageMs] before it. */
    private fun fix(
        at: Long,
        northMeters: Double = 0.0,
        accuracy: Double? = 10.0,
        gps: Boolean = true,
        ageMs: Long = 0L,
    ) = StationaryPolicy.Fix(37.0 + northMeters / 111_195.0, -122.0, accuracy, gps, at - ageMs)

    private fun stationaryAt(t: Long) {
        policy.startTracking(0L)
        assertTrue(policy.dueToEnter(t))
        policy.entered(t)
        assertEquals(Mode.STATIONARY, policy.mode)
    }

    @Test
    fun theCountdownStartsWithTrackingAndRunsOneWindow() {
        assertFalse(policy.dueToEnter(10 * idle), "not tracking")
        policy.startTracking(1_000L)
        assertFalse(policy.dueToEnter(1_000L + idle - 1))
        assertTrue(policy.dueToEnter(1_000L + idle))
        // Re-registrations don't restart it.
        policy.startTracking(5_000L)
        assertEquals(1_000L, policy.lastMovedAt)
    }

    @Test
    fun leavingTheForegroundRestartsTheCountdown() {
        policy.startTracking(0L)
        policy.backgrounded(20 * 60_000L)
        assertFalse(policy.dueToEnter(idle))
        assertTrue(policy.dueToEnter(20 * 60_000L + idle))
    }

    @Test
    fun onlyFreshAccurateGpsFixesAreMovementEvidence() {
        policy.startTracking(0L)
        val t = 60_000L
        assertFalse(policy.onFix(fix(t), t))
        assertEquals(0L, policy.lastMovedAt, "seeding the anchor isn't a move")

        // Each pair: a fix past the threshold, then its would-be confirmation.
        for ((i, bad) in listOf<(Long) -> StationaryPolicy.Fix>(
            { at -> fix(at, northMeters = 2_000.0, gps = false) },
            { at -> fix(at, northMeters = 2_000.0, accuracy = 500.0) },
            { at -> fix(at, northMeters = 2_000.0, ageMs = 2 * 60_000L) },
            { at -> fix(at, northMeters = 2_000.0, accuracy = null) },
        ).withIndex()) {
            val at = t + 1_000L * (2 * i + 1)
            policy.onFix(bad(at), at)
            policy.onFix(bad(at + 1), at + 1)
        }
        assertEquals(0L, policy.lastMovedAt, "network, coarse, stale or accuracy-less fixes aren't moves")

        val u = t + 60_000L
        policy.onFix(fix(u, northMeters = 2_000.0), u)
        assertEquals(0L, policy.lastMovedAt, "one fix past the threshold awaits confirmation")
        policy.onFix(fix(u + 1, northMeters = 2_050.0), u + 1)
        assertEquals(u + 1, policy.lastMovedAt)
    }

    @Test
    fun aMoveMustExceedTheRadiusPlusBothFixesError() {
        policy.startTracking(0L)
        policy.onFix(fix(0L, accuracy = 90.0), 0L)
        // 280 m apart: under 200 + 90 + 90.
        policy.onFix(fix(1_000L, northMeters = 280.0, accuracy = 90.0), 1_000L)
        assertEquals(0L, policy.lastMovedAt)
        policy.onFix(fix(2_000L, northMeters = 400.0, accuracy = 90.0), 2_000L)
        policy.onFix(fix(3_000L, northMeters = 410.0, accuracy = 90.0), 3_000L)
        assertEquals(3_000L, policy.lastMovedAt)
    }

    @Test
    fun aMoveWhileStationaryLeavesIt() {
        stationaryAt(idle)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t), t), "seeds the anchor")
        assertFalse(policy.onFix(fix(t + 1, northMeters = 100.0), t + 1))
        assertFalse(policy.onFix(fix(t + 2, northMeters = 1_000.0), t + 2), "unconfirmed")
        assertTrue(policy.onFix(fix(t + 3, northMeters = 1_020.0), t + 3))
        assertEquals(Mode.MOVING, policy.mode)
        assertEquals(t + 3, policy.lastMovedAt)
    }

    @Test
    fun theAnchorSurvivesEnteringStationary() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t, northMeters = 1_000.0), t))
        assertTrue(policy.onFix(fix(t + 1, northMeters = 1_020.0), t + 1), "two credible fixes after a missed departure")
    }

    @Test
    fun withNoCredibleAnchorStationaryUsesTheBestFixItHas() {
        policy.startTracking(0L)
        // Indoors: only a network fix, ±500 m, just before entering.
        policy.onFix(fix(idle - 1_000L, accuracy = 500.0, gps = false), idle - 1_000L)
        stationaryAt(idle)
        assertEquals(500.0, policy.anchor?.accuracyMeters)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t, northMeters = 300.0), t), "inside its error circle: the same place, sharper")
        assertEquals(10.0, policy.anchor?.accuracyMeters)
        assertFalse(policy.onFix(fix(t + 1, northMeters = 800.0), t + 1))
        assertTrue(policy.onFix(fix(t + 2, northMeters = 820.0), t + 2), "500 m from the sharper anchor, confirmed")
    }

    @Test
    fun aCredibleFixArrivingAfterANewerNetworkOneStillCounts() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        policy.onFix(fix(t, northMeters = 1_000.0), t)
        // Another app's network fix, taken later, is delivered before our GPS one.
        policy.onFix(fix(t + 3_000L, northMeters = 1_000.0, accuracy = 30.0, gps = false), t + 3_500L)
        assertTrue(policy.onFix(fix(t + 1_000L, northMeters = 1_020.0), t + 4_000L))
    }

    @Test
    fun aFixFromTheFutureIsIgnored() {
        policy.startTracking(0L)
        assertFalse(policy.onFix(fix(10 * idle), 1_000L))
        assertNull(policy.anchor)
        // And blinds nothing: a real fix afterwards seeds the anchor.
        policy.onFix(fix(2_000L), 2_000L)
        assertEquals(37.0, policy.anchor?.lat)
    }

    @Test
    fun oneFixDeliveredTwiceDoesNotConfirmItsOwnMove() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        // An outlier, heard by the active (or heartbeat) listener and again by the passive one.
        val outlier = fix(t, northMeters = 1_000.0)
        assertFalse(policy.onFix(outlier, t))
        assertFalse(policy.onFix(outlier, t + 5), "the same observation again")
        assertEquals(Mode.STATIONARY, policy.mode)
        assertTrue(policy.onFix(fix(t + 1_000L, northMeters = 1_020.0), t + 1_000L), "a second observation does")
    }

    @Test
    fun aDistinctFixTakenEarlierButDeliveredLateConfirms() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        // This app's GPS fix, then another app's earlier one through the passive listener.
        assertFalse(policy.onFix(fix(t, northMeters = 1_000.0), t))
        assertTrue(policy.onFix(fix(t - 5_000L, northMeters = 990.0), t + 1), "two observations, whatever the order")
    }

    @Test
    fun aLateCopyOfANearFixDoesNotCancelAPendingMove() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        val near = fix(t, northMeters = 20.0)
        assertFalse(policy.onFix(near, t))
        assertFalse(policy.onFix(fix(t + 10_000L, northMeters = 1_000.0), t + 10_000L))
        // The passive listener's copy of the near fix, delivered after the departure.
        assertFalse(policy.onFix(near, t + 10_001L))
        assertTrue(policy.onFix(fix(t + 11_000L, northMeters = 1_020.0), t + 11_000L), "still confirms the next fix")
    }

    @Test
    fun anOlderFixDeliveredLateIsNotTheFallbackAnchor() {
        policy.startTracking(0L)
        policy.onFix(fix(idle - 1_000L, northMeters = 500.0, accuracy = 30.0, gps = false), idle - 1_000L)
        policy.onFix(fix(idle - 3_000L, accuracy = 30.0, gps = false), idle - 500L)
        stationaryAt(idle)
        assertEquals(37.0 + 500.0 / 111_195.0, policy.anchor?.lat, "the newest fix, not the last delivered")
    }

    @Test
    fun stoppingTrackingEndsStationaryAndOnlySharingOffForgetsThePlace() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        policy.stopTracking(forgetPlace = false)
        assertEquals(Mode.OFF, policy.mode, "an outage")
        assertEquals(37.0, policy.anchor?.lat)
        policy.stopTracking(forgetPlace = true)
        assertNull(policy.anchor)

        // Tracking again: a fresh countdown.
        policy.startTracking(5 * idle)
        assertEquals(5 * idle, policy.lastMovedAt)
    }

    @Test
    fun leavingOnlyAppliesWhileStationary() {
        policy.startTracking(0L)
        assertFalse(policy.leave(1_000L))
        assertEquals(0L, policy.lastMovedAt)
        stationaryAt(idle)
        assertTrue(policy.leave(idle + 5))
        assertFalse(policy.leave(idle + 6), "a second trigger is a no-op")
        assertEquals(idle + 5, policy.lastMovedAt)
    }

    @Test
    fun oneLongHeartbeatFixPerPeriodWhileStationary() {
        policy.startTracking(0L)
        assertEquals(policy.fixTimeoutMs, policy.heartbeatFixTimeoutMs(idle), "moving: the listener is on")
        stationaryAt(idle)
        assertEquals(policy.fixTimeoutMs, policy.heartbeatFixTimeoutMs(idle + 5 * 60_000L))
        val due = idle + policy.longFixEveryMs
        assertEquals(policy.longFixTimeoutMs, policy.heartbeatFixTimeoutMs(due))
        policy.heartbeatFixDone(policy.longFixTimeoutMs, gotFix = false, tookMs = policy.longFixTimeoutMs, now = due)
        assertEquals(policy.fixTimeoutMs, policy.heartbeatFixTimeoutMs(due + 5 * 60_000L))
        assertEquals(policy.longFixTimeoutMs, policy.heartbeatFixTimeoutMs(due + policy.longFixEveryMs))
    }

    @Test
    fun aLongFixThatFailsAtOnceIsRetriedNextHeartbeat() {
        policy.startTracking(0L)
        stationaryAt(idle)
        val due = idle + policy.longFixEveryMs
        // No GPS for the moment: it returned straight away, having spent nothing.
        policy.heartbeatFixDone(policy.heartbeatFixTimeoutMs(due), gotFix = false, tookMs = 5L, now = due)
        val next = due + 5 * 60_000L
        assertEquals(policy.longFixTimeoutMs, policy.heartbeatFixTimeoutMs(next))
        // A quick success does use the slot.
        policy.heartbeatFixDone(policy.longFixTimeoutMs, gotFix = true, tookMs = 2_000L, now = next)
        assertEquals(policy.fixTimeoutMs, policy.heartbeatFixTimeoutMs(next + 5 * 60_000L))
    }

    @Test
    fun oneOutlierDoesNotEndStationary() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t, northMeters = 300.0, accuracy = 15.0), t), "multipath: \"15 m\", 300 m off")
        assertFalse(policy.onFix(fix(t + 1_000L), t + 1_000L), "back at the true spot cancels it")
        assertFalse(policy.onFix(fix(t + 2_000L, northMeters = 300.0, accuracy = 15.0), t + 2_000L))
        assertEquals(Mode.STATIONARY, policy.mode)
        assertEquals(37.0, policy.anchor?.lat, "the anchor didn't follow the outliers")
    }

    @Test
    fun anUnconfirmedMoveExpires() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        policy.onFix(fix(t, northMeters = 1_000.0), t)
        val late = t + policy.confirmWindowMs + 1
        assertFalse(policy.onFix(fix(late, northMeters = 1_000.0), late), "too late to confirm; starts a new candidate")
        assertTrue(policy.onFix(fix(late + 1, northMeters = 1_000.0), late + 1))
    }

    @Test
    fun theAnchorDoesNotCreepAlongWithAWalk() {
        policy.startTracking(0L)
        policy.onFix(fix(0L, accuracy = 90.0), 0L)
        // 280 m out at 10 m: within the threshold (no move) but outside the anchor's 90 m circle.
        policy.onFix(fix(1_000L, northMeters = 280.0), 1_000L)
        assertEquals(37.0, policy.anchor?.lat, "not adopted as the anchor")
    }

    @Test
    fun aStaleOrVeryCoarseFixIsNoFallbackAnchor() {
        policy.startTracking(0L)
        policy.onFix(fix(0L, accuracy = 3_000.0, gps = false), 0L)
        stationaryAt(idle)
        assertNull(policy.anchor, "a 3 km cell fix")

        val other = StationaryPolicy()
        other.startTracking(0L)
        // The forced fix's last-known fallback: an hour-old GPS fix from elsewhere.
        other.onFix(fix(idle, northMeters = 2_000.0, ageMs = 60 * 60_000L), idle)
        assertTrue(other.dueToEnter(idle))
        other.entered(idle)
        assertNull(other.anchor)
    }
}
