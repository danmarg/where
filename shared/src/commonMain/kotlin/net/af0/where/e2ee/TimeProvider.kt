package net.af0.where.e2ee

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Returns the current Unix time in whole seconds. Platform-provided. */
fun currentTimeSeconds(): Long = TimeSource.currentTimeSeconds()

/** Returns the current Unix time in milliseconds. Platform-provided. */
fun currentTimeMillis(): Long = TimeSource.currentTimeMillis()

internal class WallClockTimeoutCancellationException :
    CancellationException("Wall-clock timeout exceeded")

/**
 * A suspension-aware timeout that calculates expiration based on absolute wall-clock time
 * rather than coroutine-local ticks. This ensures that if the process is suspended and resumes
 * after the deadline, it will immediately timeout instead of pausing the clock.
 */
suspend fun <T> withWallClockTimeout(timeoutMillis: Long, block: suspend () -> T): T {
    val deadline = currentTimeMillis() + timeoutMillis
    return coroutineScope {
        val watcher = launch {
            while (currentTimeMillis() < deadline) {
                val remaining = deadline - currentTimeMillis()
                if (remaining <= 0) break
                delay(minOf(100L, remaining))
            }
            this@coroutineScope.cancel(WallClockTimeoutCancellationException())
        }
        try {
            block()
        } finally {
            watcher.cancel()
        }
    }
}

/** Variant of withWallClockTimeout that returns null on timeout instead of throwing. */
suspend fun <T> withWallClockTimeoutOrNull(timeoutMillis: Long, block: suspend () -> T): T? {
    return try {
        withWallClockTimeout(timeoutMillis, block)
    } catch (e: WallClockTimeoutCancellationException) {
        null
    }
}


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
