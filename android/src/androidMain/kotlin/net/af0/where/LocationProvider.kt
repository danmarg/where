package net.af0.where

import android.content.Context
import android.location.Location

enum class LocationAccuracy {
    PASSIVE,
    LOW_POWER,
    BALANCED,
    HIGH,
}

interface LocationProvider {
    fun init(context: Context, onLocation: (lat: Double, lng: Double, bearing: Double?) -> Unit)
    fun getLastLocationAsync(callback: (Location?) -> Unit)
    /**
     * [maxDelayMs] controls GMS batch delivery (FLP setMaxUpdateDelayMillis). Implementations
     * without batching support (e.g. FdroidLocationProvider via LocationManager) must ignore it.
     */
    fun requestActiveUpdates(accuracy: LocationAccuracy, intervalMs: Long, maxDelayMs: Long): Boolean
    fun requestPassiveUpdates(): Boolean
    fun removeActiveUpdates()
    fun removePassiveUpdates()
    suspend fun getCurrentLocation(): Location?
    suspend fun getLastLocation(): Location?
    /**
     * Returns true if the geofence request was submitted to the system, NOT if it was confirmed.
     * GMS geofencing is asynchronous; actual registration success/failure is logged via the
     * Task listeners inside the implementation. The F-Droid build always returns false (no geofencing).
     */
    fun setGeofenceAt(lat: Double, lng: Double): Boolean
    fun removeGeofence()
    fun onDestroy()
}
