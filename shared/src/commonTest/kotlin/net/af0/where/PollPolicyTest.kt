package net.af0.where

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PollPolicyTest {
    private val now = 10_000_000L
    private val bound = PollPolicy.PENDING_INIT_BOUND_MS
    private val window = PollPolicy.BACKGROUND_INVITE_WINDOW_MS
    private val awaiting = PollPolicy.AWAITING_FIRST_UPDATE_BOUND_MS
    private val trig = PollPolicy.RAPID_TRIGGER_WINDOW_MS

    private data class Case(
        val name: String,
        val state: PollPolicy.State,
        val nowMs: Long,
        val rapid: Boolean,
    )

    private fun fg(
        sheet: Boolean = false,
        naming: Boolean = false,
    ) = PollPolicy.State(appForeground = true, inviteSheetShowing = sheet, pendingQrForNaming = naming)

    private fun bg(
        at: Long?,
        sheet: Boolean = false,
        naming: Boolean = false,
        init: Long? = null,
        trigger: Long? = null,
    ) = PollPolicy.State(false, at, sheet, naming, init, trigger)

    @Test
    fun `stamps in the future count as expired`() {
        // The wall clock stepped back 3 h after each stamp was taken.
        val ahead = now + 3 * 3_600_000L
        assertFalse(PollPolicy.isRapid(bg(at = null, trigger = ahead), now))
        assertFalse(PollPolicy.isRapid(bg(at = null, init = ahead), now))
        assertFalse(PollPolicy.isRapid(bg(at = ahead, sheet = true), now))
    }

    @Test
    fun `rapid table`() {
        val cases =
            listOf(
                Case("idle foreground", fg(), now, false),
                Case("idle background never backgrounded", bg(null), now, false),
                Case("foreground invite sheet", fg(sheet = true), now, true),
                Case("foreground naming", fg(naming = true), now, true),
                Case("background sheet just after", bg(now, sheet = true), now, true),
                Case("background sheet at window-1", bg(now - window + 1, sheet = true), now, true),
                Case("background sheet at window", bg(now - window, sheet = true), now, false),
                Case("background naming at window-1", bg(now - window + 1, naming = true), now, true),
                Case("background naming at window", bg(now - window, naming = true), now, false),
                Case("sheet showing but no backgrounded mark", bg(null, sheet = true), now, false),
                Case("trigger just now", bg(null, trigger = now), now, true),
                Case("trigger at window-1", fg().copy(lastRapidTriggerMs = now - trig + 1), now, true),
                Case("trigger at window", fg().copy(lastRapidTriggerMs = now - trig), now, false),
                Case("pending init fresh", bg(null, init = now - 1), now, true),
                Case("pending init at bound-1", bg(null, init = now - bound + 1), now, true),
                Case("pending init at bound", bg(null, init = now - bound), now, false),
                Case("pending init absent", bg(null, init = null), now, false),
                Case("pending init holds past the sheet window", bg(now - window - 1, init = now - 1), now, true),
                Case("awaiting first update fresh", fg().copy(awaitingFirstUpdateAtMs = now - 1), now, true),
                Case("awaiting first update at bound-1", bg(null).copy(awaitingFirstUpdateAtMs = now - awaiting + 1), now, true),
                Case("awaiting first update at bound", bg(null).copy(awaitingFirstUpdateAtMs = now - awaiting), now, false),
                Case("lapsed sheet and trigger", bg(now - window, sheet = true, trigger = now - trig), now, false),
            )
        for (c in cases) assertEquals(c.rapid, PollPolicy.isRapid(c.state, c.nowMs), c.name)
    }

    @Test
    fun `foregrounding rearms the sheet window`() {
        val later = now + 3 * window
        assertEquals(false, PollPolicy.isRapid(bg(now, sheet = true), later))
        assertEquals(true, PollPolicy.isRapid(fg(sheet = true), later))
        assertEquals(true, PollPolicy.isRapid(bg(later, sheet = true), later + 1))
    }

    @Test
    fun `intervals`() {
        assertEquals(2_000L, PollPolicy.intervalMs(rapid = true, appForeground = true))
        assertEquals(2_000L, PollPolicy.intervalMs(rapid = true, appForeground = false))
        assertEquals(10_000L, PollPolicy.intervalMs(rapid = false, appForeground = true))
        assertEquals(300_000L, PollPolicy.intervalMs(rapid = false, appForeground = false))
    }

    @Test
    fun `target interval table`() {
        data class Row(val rapid: Boolean, val fg: Boolean, val sharing: Boolean, val peers: Boolean, val ms: Long)
        val rows =
            listOf(
                Row(true, true, false, false, 2_000L),
                Row(true, false, false, false, 2_000L),
                Row(false, true, false, false, 10_000L),
                Row(false, true, true, true, 10_000L),
                Row(false, false, true, true, 300_000L),
                Row(false, false, false, true, 1_800_000L),
                Row(false, false, true, false, 1_800_000L),
                Row(false, false, false, false, 1_800_000L),
            )
        for (r in rows) assertEquals(r.ms, PollPolicy.targetIntervalMs(r.rapid, r.fg, r.sharing, r.peers), r.toString())
    }

    @Test
    fun `fix wakes a poll only after the mode's interval since the last poll`() {
        data class Row(val name: String, val last: Long?, val rapid: Boolean, val fg: Boolean, val wake: Boolean)
        val rows =
            listOf(
                Row("first fix", null, false, false, true),
                Row("background, 30 s after a poll", now - 30_000, false, false, false),
                Row("background, just under 5 min", now - 299_999, false, false, false),
                Row("background, 5 min", now - 300_000, false, false, true),
                Row("foreground, 9 s", now - 9_000, false, true, false),
                Row("foreground, 10 s", now - 10_000, false, true, true),
                Row("rapid, 1 s", now - 1_000, true, false, false),
                Row("rapid, 2 s", now - 2_000, true, true, true),
                Row("clock went back", now + 5_000, false, false, true),
            )
        for (r in rows) assertEquals(r.wake, PollPolicy.shouldWakeOnFix(now, r.last, r.rapid, r.fg), r.name)
    }
}
