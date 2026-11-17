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

        policy.onFix(fix(t, northMeters = 2_000.0, gps = false), t)
        policy.onFix(fix(t, northMeters = 2_000.0, accuracy = 500.0), t)
        policy.onFix(fix(t, northMeters = 2_000.0, ageMs = 2 * 60_000L), t)
        policy.onFix(fix(t, northMeters = 2_000.0, accuracy = null), t)
        assertEquals(0L, policy.lastMovedAt, "network, coarse, stale or accuracy-less fixes aren't moves")

        policy.onFix(fix(t, northMeters = 2_000.0), t)
        assertEquals(t, policy.lastMovedAt)
    }

    @Test
    fun aMoveMustExceedTheRadiusPlusBothFixesError() {
        policy.startTracking(0L)
        policy.onFix(fix(0L, accuracy = 90.0), 0L)
        // 280 m apart: under 200 + 90 + 90.
        policy.onFix(fix(1_000L, northMeters = 280.0, accuracy = 90.0), 1_000L)
        assertEquals(0L, policy.lastMovedAt)
        policy.onFix(fix(2_000L, northMeters = 400.0, accuracy = 90.0), 2_000L)
        assertEquals(2_000L, policy.lastMovedAt)
    }

    @Test
    fun aMoveWhileStationaryLeavesIt() {
        stationaryAt(idle)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t), t), "seeds the anchor")
        assertFalse(policy.onFix(fix(t, northMeters = 100.0), t))
        assertTrue(policy.onFix(fix(t, northMeters = 1_000.0), t))
        assertEquals(Mode.MOVING, policy.mode)
        assertEquals(t, policy.lastMovedAt)
    }

    @Test
    fun theAnchorSurvivesEnteringStationary() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        val t = idle + 60_000L
        assertTrue(policy.onFix(fix(t, northMeters = 1_000.0), t), "the first credible fix after a missed departure is enough")
    }

    @Test
    fun withNoCredibleAnchorStationaryUsesTheBestFixItHas() {
        policy.startTracking(0L)
        // Indoors: only a network fix, ±500 m.
        policy.onFix(fix(0L, accuracy = 500.0, gps = false), 0L)
        stationaryAt(idle)
        assertEquals(500.0, policy.anchor?.accuracyMeters)
        val t = idle + 60_000L
        assertFalse(policy.onFix(fix(t, northMeters = 600.0), t), "within its error: not a move, but a sharper anchor")
        assertEquals(10.0, policy.anchor?.accuracyMeters)
        assertTrue(policy.onFix(fix(t, northMeters = 1_000.0), t), "now 400 m from the sharper anchor")
    }

    @Test
    fun stoppingTrackingEndsStationaryAndOnlySharingOffForgetsThePlace() {
        policy.startTracking(0L)
        policy.onFix(fix(0L), 0L)
        stationaryAt(idle)
        assertTrue(policy.stopTracking(forgetPlace = false), "an outage: was stationary, disarm")
        assertEquals(Mode.OFF, policy.mode)
        assertEquals(37.0, policy.anchor?.lat)
        assertFalse(policy.stopTracking(forgetPlace = true), "already off")
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
        assertEquals(policy.fixTimeoutMs, policy.heartbeatFixTimeoutMs(due + 5 * 60_000L))
        assertEquals(policy.longFixTimeoutMs, policy.heartbeatFixTimeoutMs(due + policy.longFixEveryMs))
    }
}
