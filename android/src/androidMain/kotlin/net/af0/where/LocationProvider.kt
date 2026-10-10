package net.af0.where

import android.content.Context
import android.location.Location

enum class LocationAccuracy {
    PASSIVE,
    LOW_POWER,
    BALANCED,
    HIGH,
}

/** See [LocationProvider.setGeofenceAt]. */
enum class GeofenceRequestResult {
    SUBMITTED,
    QUEUED,
    FAILED,
}

interface LocationProvider {
    fun init(
        context: Context,
        onLocation: (lat: Double, lng: Double, bearing: Double?) -> Unit,
    )

    fun getLastLocationAsync(callback: (Location?) -> Unit)

    /**
     * [maxDelayMs] controls GMS batch delivery (FLP setMaxUpdateDelayMillis). Implementations
     * without batching support (e.g. FdroidLocationProvider via LocationManager) must ignore it.
     */
    fun requestActiveUpdates(
        accuracy: LocationAccuracy,
        intervalMs: Long,
        maxDelayMs: Long,
    ): Boolean

    fun requestPassiveUpdates(): Boolean

    fun removeActiveUpdates()

    fun removePassiveUpdates()

    suspend fun getCurrentLocation(): Location?

    suspend fun getLastLocation(): Location?

    /**
     * SUBMITTED/QUEUED mean acceptance, NOT confirmation - GMS geofencing is asynchronous, and
     * actual registration success/failure is logged via the Task listeners inside the
     * implementation. QUEUED specifically means this target was recorded to submit once a prior,
     * still-in-flight request for a *different* target resolves - so callers logging "submitted"
     * for QUEUED would be lying about what's actually been sent to GMS. The F-Droid build always
     * returns FAILED (no geofencing).
     */
    fun setGeofenceAt(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): GeofenceRequestResult

    /**
     * Removes the fence planted by [setGeofenceAt], if any. Fences are NEVER_EXPIRE and outlive
     * the process, so one left behind after sharing stops keeps waking the service. No-op in
     * the F-Droid build (no geofencing).
     */
    fun removeGeofence() {}

    fun onDestroy()
}

/**
 * True for ForegroundServiceStartNotAllowedException (API 31+): starting/promoting a foreground
 * service from the background without an exemption. Checked via SDK_INT + instanceof rather than
 * a catch clause so the class is never referenced on older devices.
 */
internal fun isBackgroundStartNotAllowed(e: Throwable): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
        e is android.app.ForegroundServiceStartNotAllowedException
