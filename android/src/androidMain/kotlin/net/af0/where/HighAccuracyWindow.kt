package net.af0.where

/**
 * Decides when the HIGH-accuracy request a geofence exit starts should end.
 *
 * A geofence exit means the device is moving, so [LocationService] switches to HIGH / 10 s. Without
 * an end condition it stays there after the device arrives somewhere and stops, keeping GPS on for
 * hours. This ends it once no fix has moved [moveThresholdMeters] from the last anchor for
 * [idleMs]. Dropping back is safe: the geofence is replanted at the new position, so the next
 * departure switches to HIGH again.
 */
internal class HighAccuracyWindow(
    private val idleMs: Long,
    private val moveThresholdMeters: Float,
    private val distanceMeters: (Double, Double, Double, Double) -> Float = ::androidDistanceMeters,
) {
    private var anchorLat = Double.NaN
    private var anchorLng = Double.NaN
    private var lastMovedAt = 0L

    /** Starts a window at [now]; the first fix after this becomes the anchor. */
    fun start(now: Long) {
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        lastMovedAt = now
    }

    /** Records a fix. One [moveThresholdMeters] or more from the anchor counts as movement. */
    fun onFix(
        now: Long,
        lat: Double,
        lng: Double,
    ) {
        if (anchorLat.isNaN() || distanceMeters(anchorLat, anchorLng, lat, lng) >= moveThresholdMeters) {
            if (!anchorLat.isNaN()) lastMovedAt = now
            anchorLat = lat
            anchorLng = lng
        }
    }

    /** True once [idleMs] have passed since the window started or the device last moved. */
    fun isIdle(now: Long): Boolean = now - lastMovedAt >= idleMs
}

private fun androidDistanceMeters(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
): Float {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
    return results[0]
}
