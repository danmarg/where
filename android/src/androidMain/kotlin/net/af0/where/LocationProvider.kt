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
    fun requestActiveUpdates(accuracy: LocationAccuracy, intervalMs: Long, maxDelayMs: Long)
    fun requestPassiveUpdates()
    fun removeActiveUpdates()
    fun removePassiveUpdates()
    suspend fun getCurrentLocation(): Location?
    suspend fun getLastLocation(): Location?
    fun setGeofenceAt(lat: Double, lng: Double)
    fun removeGeofence()
    fun onDestroy()
}
