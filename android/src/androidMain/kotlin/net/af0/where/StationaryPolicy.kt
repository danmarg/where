package net.af0.where

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When the F-Droid build turns its active location request off (#483). The fdroid flavor has no
 * geofence to wake it on departure, so BALANCED would keep the GPS listener on nonstop. After
 * [StationaryPolicy.idleMs] without a credible move it goes stationary (PASSIVE plus the
 * significant-motion trigger) until the trigger fires, a fix shows a move, or the app comes to the
 * foreground.
 *
 * Pure: no Android types or clock; [LocationService] feeds it events and carries out what it
 * decides (priority, registration, the trigger). Times are elapsed-realtime millis.
 */
class StationaryPolicy(
    /** No credible move for this long, in the background, enters stationary. */
    val idleMs: Long = 15 * 60_000L,
    /** A credible move is this far (plus both fixes' accuracy) from the anchor. */
    val moveRadiusMeters: Double = 200.0,
    /** Movement evidence must be a GPS fix at least this accurate... */
    val credibleAccuracyMeters: Double = 100.0,
    /** ...and at most this old: a forced fix can fall back to an old last-known one. */
    val credibleMaxAgeMs: Long = 60_000L,
    /**
     * A move needs a second credible fix past the threshold within this long of the first: one
     * multipath outlier (a "15 m" fix 300 m off) mustn't end stationary mode. Long enough for two
     * heartbeats on a Doze alarm cadence.
     */
    val confirmWindowMs: Long = 15 * 60_000L,
    /** The coarsest fix that can stand in as an anchor when there's no credible one. */
    val fallbackAnchorMaxAccuracyMeters: Double = 500.0,
    /** The heartbeat's fix timeout... */
    val fixTimeoutMs: Long = LocationProvider.DEFAULT_FIX_TIMEOUT_MS,
    /**
     * ...except, while stationary, once per [longFixEveryMs]: with no active request a cold
     * receiver can't download ephemeris in 10 s bursts, so a departure the sensor missed would
     * never get a fix. Not every heartbeat: that would cost most of the GPS time saved.
     */
    val longFixTimeoutMs: Long = 45_000L,
    val longFixEveryMs: Long = 30 * 60_000L,
) {
    enum class Mode {
        /** Not tracking: sharing off, nobody to share with, or an outage. */
        OFF,
        MOVING,
        STATIONARY,
    }

    /** A location fix, as the policy needs it. [elapsedMs] is when it was taken. */
    data class Fix(
        val lat: Double,
        val lng: Double,
        val accuracyMeters: Double?,
        val isGps: Boolean,
        val elapsedMs: Long,
    )

    var mode = Mode.OFF
        private set

    /** When the last credible move (or session start, or leaving stationary) happened. */
    var lastMovedAt = 0L
        private set

    /** Where the last credible move ended, or a stationary entry's best guess; null if unknown. */
    var anchor: Fix? = null
        private set

    // The latest fresh fix that could stand in as an anchor on entering stationary.
    private var latestFix: Fix? = null

    // A credible fix past the threshold, awaiting a second to confirm the move.
    private var unconfirmedMove: Fix? = null
    private var lastLongFixAt = 0L

    /** Tracking (re)starts: a fresh countdown. A no-op while already tracking. */
    fun startTracking(now: Long) {
        if (mode != Mode.OFF) return
        mode = Mode.MOVING
        lastMovedAt = now
    }

    /**
     * Tracking stops: sharing off or nobody to share with ([forgetPlace]), or an outage
     * (permission or location services) after which the device may have moved unobserved.
     */
    fun stopTracking(forgetPlace: Boolean) {
        mode = Mode.OFF
        unconfirmedMove = null
        if (forgetPlace) {
            anchor = null
            latestFix = null
        }
    }

    /** The app left the foreground: time spent visible doesn't count toward going stationary. */
    fun backgrounded(now: Long) {
        if (mode == Mode.MOVING) lastMovedAt = now
    }

    /** On a background poll wake: whether to try entering stationary (the caller arms the trigger). */
    fun dueToEnter(now: Long): Boolean = mode == Mode.MOVING && now - lastMovedAt >= idleMs

    /** The trigger armed: stationary from [now]. */
    fun entered(now: Long) {
        if (mode != Mode.MOVING) return
        mode = Mode.STATIONARY
        // Without a credible anchor (e.g. indoors all along), the latest fresh fix: its accuracy
        // widens the move threshold, so a coarse one can't fake a move, only miss a short one.
        if (anchor == null) anchor = latestFix?.takeIf { now - it.elapsedMs <= credibleMaxAgeMs }
        lastLongFixAt = now
    }

    /** Leaving stationary (the trigger, the foreground, a lost trigger). Returns true if it was. */
    fun leave(now: Long): Boolean {
        if (mode != Mode.STATIONARY) return false
        mode = Mode.MOVING
        lastMovedAt = now
        return true
    }

    /**
     * A fix from any source. Returns true when it shows a move while stationary: the caller leaves
     * (the sensor missed the departure).
     */
    fun onFix(
        fix: Fix,
        now: Long,
    ): Boolean {
        // From the future (a bad HAL or a mock provider): no evidence of anything.
        if (fix.elapsedMs > now) return false
        val fresh = now - fix.elapsedMs <= credibleMaxAgeMs
        if (fresh && (fix.accuracyMeters ?: Double.MAX_VALUE) <= fallbackAnchorMaxAccuracyMeters) latestFix = fix
        if (!isCredible(fix, now)) return false
        val accuracy = fix.accuracyMeters ?: return false
        val last = anchor
        if (last == null) {
            // Seeding the anchor isn't a move: the first fix after any registration always arrives.
            anchor = fix
            return false
        }
        val lastAccuracy = last.accuracyMeters ?: 0.0
        val distance = distanceMeters(last, fix)
        if (distance < moveRadiusMeters + lastAccuracy + accuracy) {
            unconfirmedMove = null
            // Not a move. A sharper fix inside the anchor's own error circle is the same place,
            // better measured: it narrows the next threshold (and can't creep along with a walk).
            if (accuracy < lastAccuracy && distance <= lastAccuracy) anchor = fix
            return false
        }
        val pending = unconfirmedMove
        // The same fix again, or an older one arriving late, is no second observation: one fix
        // often arrives twice (the passive listener also hears this app's own GPS fixes, the
        // heartbeat's included), and counting it would let one outlier confirm its own move.
        if (pending != null && fix.elapsedMs <= pending.elapsedMs) return false
        if (pending == null || fix.elapsedMs - pending.elapsedMs > confirmWindowMs) {
            unconfirmedMove = fix
            return false
        }
        unconfirmedMove = null
        anchor = fix
        if (mode == Mode.OFF) return false
        lastMovedAt = now
        return leave(now)
    }

    /** The timeout for the heartbeat's forced fix at [now]; see [longFixEveryMs]. */
    fun heartbeatFixTimeoutMs(now: Long): Long =
        if (mode == Mode.STATIONARY && now - lastLongFixAt >= longFixEveryMs) longFixTimeoutMs else fixTimeoutMs

    /**
     * The heartbeat's fix with [timeoutMs] ended at [now] after [tookMs], with a fix or not. A long
     * one uses up its slot only if it got a fix or actually ran: one that failed at once (no GPS
     * for the moment) spent nothing, so the next heartbeat tries again.
     */
    fun heartbeatFixDone(
        timeoutMs: Long,
        gotFix: Boolean,
        tookMs: Long,
        now: Long,
    ) {
        if (timeoutMs == longFixTimeoutMs && (gotFix || tookMs >= fixTimeoutMs)) lastLongFixAt = now
    }

    private fun isCredible(
        fix: Fix,
        now: Long,
    ): Boolean =
        fix.isGps && fix.accuracyMeters != null && fix.accuracyMeters <= credibleAccuracyMeters &&
            now - fix.elapsedMs <= credibleMaxAgeMs

    private fun distanceMeters(
        a: Fix,
        b: Fix,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * r * asin(min(1.0, sqrt(h)))
    }
}
