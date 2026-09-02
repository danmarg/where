package net.af0.where

/**
 * Pure policy for the fallback "wake me if I leave" geofence that both platforms arm via
 * their own native geofencing APIs (CLLocationManager region monitoring on iOS,
 * GeofencingClient on Android). Unlike our own polling loop, a geofence is watched by the
 * OS/hardware independent of whether our process is still running, so it's the one wake
 * source that survives us getting suspended or frozen entirely. Its radius and
 * re-centering cadence live here as one shared, tested policy rather than two
 * hand-maintained platform copies that can silently drift apart - see the "here since"
 * background-triggering investigation, which was caused by exactly that kind of drift.
 */
object GeofencePolicy {
    /**
     * Radius while moving. We have live GPS truth anyway while awake, so this only
     * matters if we get suspended mid-motion. Sized larger than [STATIONARY_RADIUS_METERS]
     * so we don't need to re-arm on every single fix while driving - each re-arm
     * re-registers the region with the OS, which risks resetting its boundary-crossing
     * confirmation window (observed on iOS: a region needs the user to remain past the
     * boundary for ~20s before an exit is reported).
     */
    const val MOVING_RADIUS_METERS: Double = 400.0

    /**
     * Radius while confirmed stationary. Tight, since position isn't drifting while
     * genuinely still, and any real departure should be caught quickly.
     */
    const val STATIONARY_RADIUS_METERS: Double = 200.0

    /**
     * Re-arm once drift covers this fraction of the currently-active radius: tight enough
     * that the armed region stays meaningfully close to the truth, loose enough to avoid
     * re-registering on every single fix.
     */
    private const val RECENTER_FRACTION: Double = 0.5

    fun radiusMeters(isMoving: Boolean): Double = if (isMoving) MOVING_RADIUS_METERS else STATIONARY_RADIUS_METERS

    /**
     * True if a fix [distanceFromCenterMeters] away from the currently-armed region's
     * center has drifted far enough to be worth re-registering the geofence at the new
     * position. Distance-based, not time-based: a sudden suspension is then always caught
     * within a bounded distance of drift, regardless of how much wall-clock time it took
     * to get there (which varies wildly with speed, and would otherwise let a fast-moving,
     * suspended device wake up arbitrarily far from where it actually is).
     */
    fun shouldRecenter(
        distanceFromCenterMeters: Double,
        isMoving: Boolean,
    ): Boolean = distanceFromCenterMeters >= radiusMeters(isMoving) * RECENTER_FRACTION
}
