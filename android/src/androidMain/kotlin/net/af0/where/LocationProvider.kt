package net.af0.where

import android.content.Context
import android.location.Location

enum class LocationAccuracy {
    PASSIVE,
    BALANCED,
    HIGH,
}

/** See [LocationProvider.setGeofenceAt]. */
enum class GeofenceRequestResult {
    SUBMITTED,
    QUEUED,

    /** This attempt failed (e.g. a SecurityException); a later one may succeed. */
    FAILED,

    /** This build or device can't geofence at all (F-Droid); retrying is pointless. */
    UNSUPPORTED,
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

    /** A fresh fix, or null if none comes within [timeoutMs]. */
    suspend fun getCurrentLocation(timeoutMs: Long = DEFAULT_FIX_TIMEOUT_MS): Location?

    suspend fun getLastLocation(): Location?

    /**
     * SUBMITTED/QUEUED mean acceptance, NOT confirmation - GMS geofencing is asynchronous, and
     * actual registration success/failure is logged via the Task listeners inside the
     * implementation. QUEUED specifically means this target was recorded to submit once a prior,
     * still-in-flight request for a *different* target resolves - so callers logging "submitted"
     * for QUEUED would be lying about what's actually been sent to GMS. FAILED is this attempt
     * only (e.g. a SecurityException); the F-Droid build always returns UNSUPPORTED (no
     * geofencing).
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

    /**
     * Receives every fix from the active and passive listeners, with its provider, accuracy and
     * time, on the main thread, for movement detection ([StationaryPolicy] filters them). Only the
     * F-Droid build reports them; the gms build has no stationary mode.
     */
    fun setFixListener(listener: ((Location) -> Unit)?) {}

    /**
     * Arms a one-shot wake for when the device starts moving: the significant-motion sensor in
     * the F-Droid build (the gms build wakes on a geofence exit instead). [onMotion] runs at most
     * once per arm, on the main thread. True, without re-arming, when one is already armed. False
     * when this build or device has no such trigger, and then any armed one is disarmed.
     */
    fun armMotionTrigger(onMotion: () -> Unit): Boolean = false

    /** Disarms the [armMotionTrigger] trigger, if one is armed. */
    fun cancelMotionTrigger() {}

    fun onDestroy()

    companion object {
        /** A forced fix's usual timeout; see [StationaryPolicy.longFixTimeoutMs] for the exception. */
        const val DEFAULT_FIX_TIMEOUT_MS = 10_000L
    }
}

/**
 * True for ForegroundServiceStartNotAllowedException (API 31+): starting/promoting a foreground
 * service from the background without an exemption. Checked via SDK_INT + instanceof rather than
 * a catch clause so the class is never referenced on older devices.
 */
internal fun isBackgroundStartNotAllowed(e: Throwable): Boolean =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
        e is android.app.ForegroundServiceStartNotAllowedException
