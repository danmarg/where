package net.af0.where.e2ee

/** Returns the current Unix time in whole seconds. Platform-provided. */
fun currentTimeSeconds(): Long = TimeSource.currentTimeSeconds()

/** Returns the current Unix time in milliseconds. Platform-provided. */
fun currentTimeMillis(): Long = TimeSource.currentTimeMillis()

object TimeSource {
    @kotlin.concurrent.Volatile
    private var provider: TimeProvider = DefaultTimeProvider

    fun setProvider(p: TimeProvider) {
        provider = p
    }

    fun currentTimeSeconds(): Long = provider.currentTimeSeconds()

    fun currentTimeMillis(): Long = provider.currentTimeMillis()
}

interface TimeProvider {
    fun currentTimeSeconds(): Long

    fun currentTimeMillis(): Long
}

internal object DefaultTimeProvider : TimeProvider {
    override fun currentTimeSeconds(): Long = platformCurrentTimeSeconds()

    override fun currentTimeMillis(): Long = platformCurrentTimeMillis()
}

internal expect fun platformCurrentTimeSeconds(): Long

internal expect fun platformCurrentTimeMillis(): Long
