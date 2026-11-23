package net.af0.where

import kotlin.test.Test
import kotlin.test.assertEquals

class PollPolicyTest {
    private val now = 10_000_000L
    private val bound = PollPolicy.PENDING_INIT_BOUND_MS
    private val window = PollPolicy.BACKGROUND_INVITE_WINDOW_MS
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
}
