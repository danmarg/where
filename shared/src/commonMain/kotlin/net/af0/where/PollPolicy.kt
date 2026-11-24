package net.af0.where

/**
 * How often a client polls its mailboxes, as one pure, platform-free decision (shared so iOS can
 * adopt it; today only Android does). It answers "is something waiting on a fast answer, and so
 * what is the poll interval?" and nothing else: whether a *service* runs, when it stops and what
 * alarm wakes it are Android's business (`ServicePolicy` in `android/`), which consumes this
 * object's output.
 *
 * Everything is measured on one time base: [isRapid] takes `nowMs` and every timestamp in [State]
 * in the same unit and clock (the Android service uses elapsed-realtime millis, which keeps
 * counting in deep sleep). No clock reads, no coroutines.
 *
 * Intervals (CLAUDE.md "Real-time transport"): [RAPID_INTERVAL_MS] while something waits,
 * [FOREGROUND_INTERVAL_MS] with the app visible, [BACKGROUND_INTERVAL_MS] otherwise.
 */
object PollPolicy {
    /** Poll interval while [isRapid]: right after pairing / invite activity. */
    const val RAPID_INTERVAL_MS = 2_000L

    /** Poll interval with the app in the foreground. */
    const val FOREGROUND_INTERVAL_MS = 10_000L

    /** Poll interval in the background while sharing (heartbeat plus friend poll). */
    const val BACKGROUND_INTERVAL_MS = 5 * 60 * 1000L

    /** A rapid-poll request (pairing started, a friend just added) holds rapid mode this long. */
    const val RAPID_TRIGGER_WINDOW_MS = 60_000L

    /**
     * An incoming invite that is never confirmed or cancelled keeps rapid mode this long (#336):
     * long enough to notice a notification and answer, short enough not to run rapid forever.
     */
    const val PENDING_INIT_BOUND_MS = 5 * 60 * 1000L

    /**
     * An open invite sheet or naming screen keeps rapid mode this long after the app is
     * backgrounded (sharing the link leaves the app just as Bob scans); a sheet left open must not
     * hold 2 s polls forever. Foregrounding re-arms it (C3).
     */
    const val BACKGROUND_INVITE_WINDOW_MS = 5 * 60 * 1000L

    /** What the decision reads. Timestamps are on the caller's single clock; null means "never". */
    data class State(
        val appForeground: Boolean,
        /** When the app left the foreground; null while in the foreground or if it never did. */
        val backgroundedAtMs: Long? = null,
        val inviteSheetShowing: Boolean = false,
        val pendingQrForNaming: Boolean = false,
        /** When an incoming invite payload awaiting confirmation arrived; null if none. */
        val pendingInitSetAtMs: Long? = null,
        /** When rapid polling was last requested; null if never or since reset. */
        val lastRapidTriggerMs: Long? = null,
    )

    /**
     * True while something needs a fast poll: the share sheet is open or Bob is on the naming
     * screen (visible, or within [BACKGROUND_INVITE_WINDOW_MS] of backgrounding), a fresh incoming
     * invite awaits Alice's confirmation ([PENDING_INIT_BOUND_MS]), or rapid polling was requested
     * within [RAPID_TRIGGER_WINDOW_MS].
     */
    fun isRapid(
        state: State,
        nowMs: Long,
    ): Boolean {
        val recentlyTriggered = state.lastRapidTriggerMs?.let { nowMs - it < RAPID_TRIGGER_WINDOW_MS } ?: false
        val visibleOrRecent =
            state.appForeground ||
                (state.backgroundedAtMs != null && nowMs - state.backgroundedAtMs < BACKGROUND_INVITE_WINDOW_MS)
        val sheetOrNaming = visibleOrRecent && (state.inviteSheetShowing || state.pendingQrForNaming)
        val freshPendingInit = state.pendingInitSetAtMs?.let { nowMs - it < PENDING_INIT_BOUND_MS } ?: false
        return sheetOrNaming || freshPendingInit || recentlyTriggered
    }

    /** The poll interval for a client in the given mode. */
    fun intervalMs(
        rapid: Boolean,
        appForeground: Boolean,
    ): Long =
        when {
            rapid -> RAPID_INTERVAL_MS
            appForeground -> FOREGROUND_INTERVAL_MS
            else -> BACKGROUND_INTERVAL_MS
        }

    /**
     * Whether a location fix should wake a poll (so "whenever we share, we also receive"): only
     * when the last poll, from any wake, is at least this mode's [intervalMs] old. A fix after a
     * gap (or the first one, [lastPollAtMs] null) wakes at once; fixes arriving up to once a
     * second (the passive listener) don't each cost a poll. A last poll in the future (a clock
     * that went back) doesn't hold wakes off. Forced and visibility wakes don't go through here.
     */
    fun shouldWakeOnFix(
        nowMs: Long,
        lastPollAtMs: Long?,
        rapid: Boolean,
        appForeground: Boolean,
    ): Boolean {
        val age = nowMs - (lastPollAtMs ?: return true)
        return age < 0 || age >= intervalMs(rapid, appForeground)
    }

    /**
     * Whether a poll cycle also polls the pending-invite discovery mailboxes (one GET each): only
     * while the app is visible or [isRapid] (invite sheet, naming screen, a fresh incoming invite,
     * a recent trigger), the same rule as iOS's `updateUi || isInviteSheetShowing`. Background
     * maintenance polls skip it; opening the app polls them.
     */
    fun shouldPollInvites(
        appForeground: Boolean,
        rapid: Boolean,
    ): Boolean = appForeground || rapid
}
