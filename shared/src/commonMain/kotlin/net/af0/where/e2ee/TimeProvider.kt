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

    /** Formats a Unix timestamp (seconds) into a local time string (HH:mm:ss). */
    fun formatLocalTime(seconds: Long): String = provider.formatLocalTime(seconds)
}

interface TimeProvider {
    fun currentTimeSeconds(): Long

    fun currentTimeMillis(): Long

    fun formatLocalTime(seconds: Long): String
}

internal object DefaultTimeProvider : TimeProvider {
    override fun currentTimeSeconds(): Long = platformCurrentTimeSeconds()

    override fun currentTimeMillis(): Long = platformCurrentTimeMillis()

    override fun formatLocalTime(seconds: Long): String = platformFormatLocalTime(seconds)
}

internal expect fun platformCurrentTimeSeconds(): Long

internal expect fun platformCurrentTimeMillis(): Long

internal expect fun platformFormatLocalTime(seconds: Long): String
