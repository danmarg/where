package net.af0.where

import net.af0.where.ServicePolicy.Decision
import net.af0.where.ServicePolicy.RunReason
import net.af0.where.ServicePolicy.State
import net.af0.where.ServicePolicy.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Table for [ServicePolicy.decide]. Each row mirrors a LocationServiceTest case (named in the row);
 * those Robolectric tests are what proves the loop and the policy agree.
 */
class ServicePolicyTest {
    private val now = 1_000_000L
    private val grace = ServicePolicy.BACKGROUND_STOP_GRACE_MS
    private val doze = ServicePolicy.DOZE_ALARM_INTERVAL_MS

    private fun st(
        relationships: Boolean = true,
        fg: Boolean = false,
        sharing: Boolean = false,
        canRun: Boolean = true,
        rapid: Boolean = false,
        graceAt: Long? = null,
        syncs: Int = 0,
        gaveUp: Boolean = false,
    ) = State(relationships, fg, sharing, canRun, rapid, graceAt, syncs, gaveUp)

    private fun run(
        reason: RunReason,
        poll: Long,
        alarm: Long,
        graceAlarm: Long? = null,
    ) = Decision.Run(reason, poll, alarm, graceAlarm)

    private data class Row(val name: String, val state: State, val nowMs: Long, val expected: Decision)

    @Test
    fun `decision table`() {
        val rows =
            listOf(
                Row(
                    "no friends stops (onStartCommand_relaunches... no-friends path)",
                    st(relationships = false, fg = true),
                    now,
                    Decision.Stop(StopReason.NO_RELATIONSHIPS),
                ),
                Row(
                    "no friends beats everything",
                    st(relationships = false, sharing = true, rapid = true),
                    now,
                    Decision.Stop(StopReason.NO_RELATIONSHIPS),
                ),
                Row(
                    "notSharingInBackground_stopsItselfWithoutPolling (never backgrounded: grace over)",
                    st(),
                    now,
                    Decision.Stop(StopReason.NOT_SHARING),
                ),
                Row("notSharing_staysWhileVisible", st(fg = true), now, run(RunReason.FOREGROUND, 10_000, doze)),
                Row("visible, sharing", st(fg = true, sharing = true), now, run(RunReason.FOREGROUND, 10_000, doze)),
                Row("rapid foreground", st(fg = true, rapid = true), now, run(RunReason.RAPID, 2_000, 2_000)),
                Row(
                    "notSharing_staysWhileVisible: just backgrounded, within grace",
                    st(graceAt = now),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row("grace - 1 ms left", st(graceAt = now - grace + 1), now, run(RunReason.GRACE, 1, 1, 1)),
                Row("grace over exactly: stops", st(graceAt = now - grace), now, Decision.Stop(StopReason.NOT_SHARING)),
                Row(
                    "backgrounding alone arms the grace end (notSharing_backgrounding_stopsWhenTheGraceEnds)",
                    st(graceAt = now - 5_000),
                    now,
                    run(RunReason.GRACE, grace - 5_000, grace - 5_000, grace - 5_000),
                ),
                Row(
                    "sharingTurnedOffInBackground: rest of the grace",
                    st(graceAt = now - 10_000),
                    now,
                    run(RunReason.GRACE, grace - 10_000, grace - 10_000, grace - 10_000),
                ),
                Row(
                    "notSharing_shortVisitInBackground_rearmsAFullGrace",
                    st(graceAt = now),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row(
                    "appOpenedAndClosedDuringAPoll: grace restarted by the visit",
                    st(graceAt = now),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row(
                    "sharingInBackground_keepsRunning",
                    st(sharing = true, graceAt = now - 10 * grace),
                    now,
                    run(RunReason.SHARING, 300_000, doze),
                ),
                Row("sharing in background, never backgrounded", st(sharing = true), now, run(RunReason.SHARING, 300_000, doze)),
                Row(
                    "sharing without permission stops (no location permission)",
                    st(sharing = true, canRun = false),
                    now,
                    Decision.Stop(StopReason.NO_PERMISSION),
                ),
                Row(
                    "sharing without permission, within grace",
                    st(sharing = true, canRun = false, graceAt = now),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row("notSharingInBackground_staysWhilePairing", st(rapid = true), now, run(RunReason.RAPID, 2_000, 2_000)),
                Row(
                    "notSharing_backgroundingWhileRapidPolling_keepsTheRapidAlarm",
                    st(rapid = true, graceAt = now),
                    now,
                    run(RunReason.RAPID, 2_000, 2_000),
                ),
                Row(
                    "rapid with grace over does not stop",
                    st(rapid = true, graceAt = now - 2 * grace),
                    now,
                    run(RunReason.RAPID, 2_000, 2_000),
                ),
                Row(
                    "idleStop_stopsAfterTheSync (no syncs left)",
                    st(graceAt = now - grace, syncs = 0),
                    now,
                    Decision.Stop(StopReason.NOT_SHARING),
                ),
                Row("idleStop_waitsForOverlappingSyncs", st(graceAt = now - grace, syncs = 3), now, Decision.AwaitSyncs(60_000)),
                Row(
                    "syncs running within the grace: no wait yet",
                    st(graceAt = now, syncs = 2),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row(
                    "idleStop_stuckSync_givesUpWaitingAndPolls",
                    st(graceAt = now - grace, syncs = 1, gaveUp = true),
                    now,
                    run(RunReason.SYNC_WAIT_GAVE_UP, 300_000, 300_000),
                ),
                Row(
                    "gave up, grace long over: no spin interval",
                    st(gaveUp = true, syncs = 1),
                    now,
                    run(RunReason.SYNC_WAIT_GAVE_UP, 300_000, 300_000),
                ),
                Row(
                    "idleStop_appOpenedDuringTheSync_keepsRunning",
                    st(fg = true, syncs = 1, graceAt = now - 2 * grace),
                    now,
                    run(RunReason.FOREGROUND, 10_000, doze),
                ),
                Row(
                    "idleStop_sharingTurnedOnDuringTheSync_keepsRunning",
                    st(sharing = true, syncs = 1, graceAt = now - 2 * grace),
                    now,
                    run(RunReason.SHARING, 300_000, doze),
                ),
                Row(
                    "idleStop_appOpenedAndClosedDuringTheSync_restartsTheGrace",
                    st(syncs = 1, graceAt = now),
                    now,
                    run(RunReason.GRACE, grace, grace, grace),
                ),
                Row(
                    "invite window expired: rapid false again, idle stop",
                    st(rapid = false, graceAt = now - 6 * 60_000),
                    now,
                    Decision.Stop(StopReason.NOT_SHARING),
                ),
            )
        for (r in rows) assertEquals(r.expected, ServicePolicy.decide(r.state, r.nowMs), r.name)
    }

    @Test
    fun `give-up persists only while syncs run and the decision is still the skipped stop`() {
        fun next(state: State) = ServicePolicy.nextGaveUp(state, ServicePolicy.decide(state, now))
        val idle = st(graceAt = now - grace, syncs = 1, gaveUp = true)
        assertEquals(true, next(idle), "kept across iterations while the sync hangs")
        assertEquals(false, next(idle.copy(runningSyncs = 0)), "syncs ended: spent")
        assertEquals(false, next(idle.copy(appForeground = true)), "app visible: input changed")
        assertEquals(false, next(idle.copy(sharing = true)), "sharing on: input changed")
        assertEquals(false, next(idle.copy(rapid = true)), "rapid: input changed")
        assertEquals(false, next(idle.copy(hasRelationships = false)), "nobody left")
        assertEquals(false, next(idle.copy(syncWaitGaveUp = false)), "never gave up")
    }

    @Test
    fun `worker nudges the service unless it polled within the background cadence`() {
        val cadence = PollPolicy.BACKGROUND_INTERVAL_MS
        assertEquals(true, ServicePolicy.shouldNudgeFromWorker(now, null))
        assertEquals(false, ServicePolicy.shouldNudgeFromWorker(now, now - 1_000))
        assertEquals(false, ServicePolicy.shouldNudgeFromWorker(now, now - cadence + 1))
        assertEquals(true, ServicePolicy.shouldNudgeFromWorker(now, now - cadence))
        assertEquals(true, ServicePolicy.shouldNudgeFromWorker(now, now + 1_000))
    }

    @Test
    fun `graceStart resolves the foreground against the recorded start`() {
        assertEquals(null, ServicePolicy.graceStart(true, null, now), "visible: no grace")
        assertEquals(null, ServicePolicy.graceStart(true, now - 5, now), "visible clears a stale start")
        assertEquals(now, ServicePolicy.graceStart(false, null, now), "backgrounded, event not seen yet: starts now")
        assertEquals(now - 5, ServicePolicy.graceStart(false, now - 5, now), "recorded start kept")
        val started = ServicePolicy.graceStart(false, null, now)
        assertEquals(
            Decision.Stop(StopReason.NOT_SHARING),
            ServicePolicy.decide(st(graceAt = started), now + grace),
        )
        assertEquals(RunReason.GRACE, (ServicePolicy.decide(st(graceAt = started), now + grace - 1) as Decision.Run).reason)
    }
}
