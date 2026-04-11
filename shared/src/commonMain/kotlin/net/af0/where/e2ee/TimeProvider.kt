package net.af0.where.e2ee

/** Returns the current Unix time in whole seconds. Platform-provided. */
expect fun currentTimeSeconds(): Long

/** Returns the current Unix time in milliseconds. Platform-provided. */
expect fun currentTimeMillis(): Long

/**
 * Returns true if [ts] (Unix seconds) falls within the clock-skew grace window.
 *
 * Per §9.3: recipients MUST reject any EpochRotation or RatchetAck whose `ts` falls
 * outside a `T + 5 min` window relative to local clock (where T = [epochPeriodSeconds]).
 *
 * @param ts                Unix timestamp from the incoming message.
 * @param epochPeriodSeconds Epoch period T in seconds (default: 600 = 10 min).
 */
fun isTimestampFresh(
    ts: Long,
    epochPeriodSeconds: Long = 600L,
): Boolean {
    val now = currentTimeSeconds()
    val gracePeriod = epochPeriodSeconds + 5 * 60L
    return ts in (now - gracePeriod)..(now + gracePeriod)
}
