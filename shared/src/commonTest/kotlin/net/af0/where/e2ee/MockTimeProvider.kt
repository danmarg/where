package net.af0.where.e2ee

class MockTimeProvider(
    private var nowMs: Long = 1711152000000L, // 2024-03-23 00:00:00 UTC
) : TimeProvider {
    override fun currentTimeSeconds(): Long = nowMs / 1000

    override fun currentTimeMillis(): Long = nowMs

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
