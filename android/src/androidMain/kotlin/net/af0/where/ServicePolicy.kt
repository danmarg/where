package net.af0.where

/**
 * Whether [LocationService] should be running, and when it must next wake, as one pure function.
 *
 * Design note. The service's lifecycle used to be spread over a poll loop, three collectors in
 * onCreate, an alarm armed from several places and a sync-wait helper, each holding a piece of the
 * answer (C1 idle stop race, C2 background grace, C3 invite window all patched one interleaving
 * at a time). Now [pollLoop][LocationService] is the only owner of the state it feeds in here (the
 * collectors just queue events for it), asks [decide] at the top of every iteration, and arms the
 * one alarm from the answer. There is no clock here: `nowMs` and every timestamp are
 * elapsed-realtime millis (counts through deep sleep), the same base [PollPolicy] consumes, which
 * decides the cadence ([State.rapid] is its output).
 *
 * Rules, in order ([decide]):
 *  1. No friends and no pending invites: [Decision.Stop] ([StopReason.NO_RELATIONSHIPS]).
 *  2. Idle = not visible, not rapid, and not [State.needsBackgroundService] (sharing with a
 *     usable location permission). Idle past the [BACKGROUND_STOP_GRACE_MS] grace that began when
 *     the app last left the screen: [Decision.AwaitSyncs] while reconnect syncs run (so
 *     onDestroy doesn't cut off their acks and outbox flush), else [Decision.Stop]. The grace
 *     exists because the QR scanner and permission dialogs leave the screen briefly.
 *  3. Otherwise [Decision.Run]: sleep [Decision.Run.pollIntervalMs] (the cadence, capped at the
 *     grace's end while idle), with the doze alarm [Decision.Run.alarmInMs] behind it.
 *
 * [State.syncWaitGaveUp]: a sync wait that hit [NETWORK_SYNC_WAIT_MS] skips the stop for this
 * iteration (stuck or flapping network) and polls; without the flag a grace already over would
 * ask to wait again at once and spin.
 */
internal object ServicePolicy {
    /** How long after the app leaves the screen a service with nothing to send stops. */
    const val BACKGROUND_STOP_GRACE_MS = 30_000L

    /** Longest the idle stop waits for reconnect syncs before giving up on stopping this time. */
    const val NETWORK_SYNC_WAIT_MS = 60_000L

    /**
     * Interval for the dead-service doze alarm. setExactAndAllowWhileIdle is rate-limited
     * (~1 per 9 min) in Doze; a shorter ask is silently deferred to the next maintenance window,
     * which can be hours. Not used while rapid or idle, which keep the tight interval.
     */
    const val DOZE_ALARM_INTERVAL_MS = 10 * 60 * 1000L

    data class State(
        /** Friends or pending invites exist. */
        val hasRelationships: Boolean,
        val appForeground: Boolean,
        val sharing: Boolean,
        /** Location permission is held (or the OS doesn't require it for the service). */
        val canRunService: Boolean,
        /** [PollPolicy.isRapid] for the same instant. */
        val rapid: Boolean,
        /** When the app last left the screen (incl. a visit too short to see); null: never, so over. */
        val graceStartedAtMs: Long? = null,
        /** Reconnect syncs in flight. */
        val runningSyncs: Int = 0,
        val syncWaitGaveUp: Boolean = false,
    ) {
        val needsBackgroundService: Boolean get() = sharing && canRunService
    }

    enum class StopReason(val text: String) {
        NO_RELATIONSHIPS("no friends or pending invites"),
        NOT_SHARING("not sharing"),
        NO_PERMISSION("no location permission"),
    }

    enum class RunReason { RAPID, FOREGROUND, SHARING, GRACE, SYNC_WAIT_GAVE_UP }

    sealed interface Decision {
        data class Stop(val reason: StopReason) : Decision

        /** Wait up to [maxWaitMs] for syncs or any state change, then decide again. */
        data class AwaitSyncs(val maxWaitMs: Long) : Decision

        /**
         * @property pollIntervalMs how long to sleep before the next iteration (a poll wake ends it early)
         * @property alarmInMs delay for the one doze alarm that backs the sleep
         * @property graceAlarmInMs delay at which the idle stop is due, or null when nothing is
         *   pending (arm this on a state change that starts the grace; not while rapid, which
         *   keeps its tight alarm)
         */
        data class Run(
            val reason: RunReason,
            val pollIntervalMs: Long,
            val alarmInMs: Long,
            val graceAlarmInMs: Long?,
        ) : Decision
    }

    /**
     * The give-up flag to carry into the next decision: it stays while syncs still run and the
     * decision is still the skipped stop (or the wait that follows it). Cleared once they have
     * ended (a later sync gets a fresh wait) or an input changed enough to make [decision] a
     * run for another reason (app visible, sharing, rapid) or a stop for no friends.
     */
    fun nextGaveUp(
        state: State,
        decision: Decision,
    ): Boolean =
        state.syncWaitGaveUp && state.runningSyncs > 0 &&
            (decision as? Decision.Run)?.reason == RunReason.SYNC_WAIT_GAVE_UP

    fun decide(
        state: State,
        nowMs: Long,
    ): Decision {
        if (!state.hasRelationships) return Decision.Stop(StopReason.NO_RELATIONSHIPS)
        val needs = state.needsBackgroundService
        val idle = !state.appForeground && !state.rapid && !needs
        val graceLeft = state.graceStartedAtMs?.let { it + BACKGROUND_STOP_GRACE_MS - nowMs } ?: 0L
        if (idle && graceLeft <= 0 && !state.syncWaitGaveUp) {
            if (state.runningSyncs > 0) return Decision.AwaitSyncs(NETWORK_SYNC_WAIT_MS)
            // needsBackgroundService is also false while sharing without location permission.
            return Decision.Stop(if (state.sharing) StopReason.NO_PERMISSION else StopReason.NOT_SHARING)
        }
        val cadence = PollPolicy.intervalMs(state.rapid, state.appForeground)
        // Wake when the grace runs out so the stop happens on time; not once it has (a wait that
        // gave up), as a non-positive interval would spin.
        val interval = if (idle && graceLeft > 0) minOf(cadence, graceLeft) else cadence
        // Above the platform's ~9 min minimum unless rapid (key exchange wants responsiveness) or
        // an idle stop is pending (the coroutine timer doesn't run while the CPU sleeps).
        val alarmIn = if (state.rapid || idle) interval else maxOf(DOZE_ALARM_INTERVAL_MS, interval)
        val reason =
            when {
                state.rapid -> RunReason.RAPID
                state.appForeground -> RunReason.FOREGROUND
                needs -> RunReason.SHARING
                graceLeft > 0 -> RunReason.GRACE
                else -> RunReason.SYNC_WAIT_GAVE_UP
            }
        val graceAlarmIn = if (idle) graceLeft.takeIf { it > 0 } else null
        return Decision.Run(reason, interval, alarmIn, graceAlarmIn)
    }

    /**
     * Whether the WorkManager heartbeat should nudge the (sharing) service: not when the service
     * itself polled less than [PollPolicy.BACKGROUND_INTERVAL_MS] ago ([lastServicePollMs]; null
     * when it isn't running), since a tick would force a second poll inside its own cadence. A
     * poll time in the future (clock set back) doesn't hold the nudge off.
     */
    fun shouldNudgeFromWorker(
        nowMs: Long,
        lastServicePollMs: Long?,
    ): Boolean {
        val age = nowMs - (lastServicePollMs ?: return true)
        return age < 0 || age >= PollPolicy.BACKGROUND_INTERVAL_MS
    }
}
