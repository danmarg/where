package net.af0.where.e2ee

class MockTimeProvider(
    private var nowMs: Long = 1711152000000L, // 2024-03-23 00:00:00 UTC
) : TimeProvider {
    override fun currentTimeSeconds(): Long = nowMs / 1000

    override fun currentTimeMillis(): Long = nowMs

    override fun formatLocalTime(seconds: Long): String {
        val s = (seconds % 86400).toInt()
        return "${(s / 3600).toString().padStart(2, '0')}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun advanceSeconds(seconds: Long) {
        nowMs += seconds * 1000
    }

    fun advanceMillis(millis: Long) {
        nowMs += millis
    }

    fun setNowMs(ms: Long) {
        nowMs = ms
    }
}
