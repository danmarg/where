package net.af0.where

import kotlin.math.asin
import kotlin.math.cos
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
    /** The heartbeat's fix timeout... */
    val fixTimeoutMs: Long = 10_000L,
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

    // The latest fix with an accuracy, credible or not: a fallback anchor on entering stationary.
    private var latestFix: Fix? = null
    private var lastLongFixAt = Long.MIN_VALUE

    /** Tracking (re)starts: a fresh countdown. A no-op while already tracking. */
    fun startTracking(now: Long) {
        if (mode != Mode.OFF) return
        mode = Mode.MOVING
        lastMovedAt = now
    }

    /**
     * Tracking stops: sharing off or nobody to share with ([forgetPlace]), or an outage
     * (permission or location services) after which the device may have moved unobserved.
     * Returns true if it was stationary, so the trigger must be disarmed.
     */
    fun stopTracking(forgetPlace: Boolean): Boolean {
        val wasStationary = mode == Mode.STATIONARY
        mode = Mode.OFF
        if (forgetPlace) {
            anchor = null
            latestFix = null
        }
        return wasStationary
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
        // Without a credible anchor (e.g. indoors all along), the best fix we have: its accuracy
        // widens the move threshold, so a coarse one can't fake a move, only miss a short one.
        if (anchor == null) anchor = latestFix
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
        if (fix.accuracyMeters != null) latestFix = fix
        if (!isCredible(fix, now)) return false
        val last = anchor
        val accuracy = fix.accuracyMeters!!
        val lastAccuracy = last?.accuracyMeters ?: 0.0
        if (last != null && distanceMeters(last, fix) < moveRadiusMeters + lastAccuracy + accuracy) {
            // Not a move; but a sharper fix of the same place narrows the next threshold.
            if (accuracy < lastAccuracy) anchor = fix
            return false
        }
        anchor = fix
        // Seeding the anchor isn't a move: the first fix after any registration always arrives.
        if (last == null || mode == Mode.OFF) return false
        lastMovedAt = now
        return leave(now)
    }

    /** The timeout for the heartbeat's forced fix at [now]; see [longFixEveryMs]. */
    fun heartbeatFixTimeoutMs(now: Long): Long {
        if (mode != Mode.STATIONARY || now - lastLongFixAt < longFixEveryMs) return fixTimeoutMs
        lastLongFixAt = now
        return longFixTimeoutMs
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
        return 2 * r * asin(sqrt(h))
    }
}
