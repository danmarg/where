package net.af0.where

/**
 * Counts the reconnect syncs in flight and owns the wake lock they share, so that "a sync is
 * running" and "the lock is held" can't disagree. [begin] counts and acquires, [end] decrements
 * and releases when it was the last, each as one critical section under one lock: a sync
 * registered while another finishes either is counted before the last-out check (the lock stays)
 * or after the release (it re-acquires), never in between.
 */
internal class SyncGate(
    private val acquire: () -> Unit,
    private val release: () -> Unit,
) {
    private val lock = Any()
    private var count = 0

    val running: Int get() = synchronized(lock) { count }

    fun begin() =
        synchronized(lock) {
            // Acquire first: if it throws, nothing was counted.
            acquire()
            count++
        }

    fun end() =
        synchronized(lock) {
            if (count > 0 && --count == 0) release()
        }
}
