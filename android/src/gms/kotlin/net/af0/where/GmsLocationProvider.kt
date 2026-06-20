package net.af0.where

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GmsLocationProvider"

class GmsLocationProvider : LocationProvider {
    private lateinit var context: Context
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var passiveLocationCallback: LocationCallback

    override fun init(context: Context, onLocation: (Double, Double, Double?) -> Unit) {
        this.context = context.applicationContext
        fusedClient = LocationServices.getFusedLocationProviderClient(context)
        geofencingClient = LocationServices.getGeofencingClient(context)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
            }
        }
        passiveLocationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
            }
        }
    }

    override fun getLastLocationAsync(callback: (Location?) -> Unit) {
        fusedClient.lastLocation.addOnSuccessListener { loc -> callback(loc) }
    }

    private fun LocationAccuracy.toGmsPriority() = when (this) {
        LocationAccuracy.PASSIVE -> Priority.PRIORITY_PASSIVE
        LocationAccuracy.LOW_POWER -> Priority.PRIORITY_LOW_POWER
        LocationAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
    }

    override fun requestActiveUpdates(accuracy: LocationAccuracy, intervalMs: Long, maxDelayMs: Long) {
        val request = LocationRequest.Builder(accuracy.toGmsPriority(), intervalMs)
            .setMinUpdateIntervalMillis(10_000L)
            .setMinUpdateDistanceMeters(LocationService.MOVEMENT_RADIUS_THRESHOLD_METERS)
            .setMaxUpdateDelayMillis(maxDelayMs)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting active updates: ${e.message}")
        }
    }

    override fun requestPassiveUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 1_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, passiveLocationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting passive updates: ${e.message}")
        }
    }

    override fun removeActiveUpdates() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: SecurityException) {}
    }

    override fun removePassiveUpdates() {
        try {
            fusedClient.removeLocationUpdates(passiveLocationCallback)
        } catch (_: SecurityException) {}
    }

    override suspend fun getCurrentLocation(): Location? {
        return try {
            withTimeoutOrNull(10_000L) {
                fusedClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting current location: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current location: ${e.message}")
            null
        }
    }

    override suspend fun getLastLocation(): Location? {
        return try {
            fusedClient.lastLocation.await()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last location: ${e.message}")
            null
        }
    }

    override fun setGeofenceAt(lat: Double, lng: Double) {
        val geofence = Geofence.Builder()
            .setRequestId("stationary_fence")
            .setCircularRegion(lat, lng, LocationService.MOVEMENT_RADIUS_THRESHOLD_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(geofence)
            .build()
        try {
            geofencingClient.addGeofences(request, getGeofencePendingIntent())
            Log.i(TAG, "Geofence set at $lat, $lng")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting geofence: ${e.message}")
        }
    }

    override fun removeGeofence() {
        geofencingClient.removeGeofences(getGeofencePendingIntent())
        Log.i(TAG, "Geofence removed")
    }

    override fun onDestroy() {
        removeActiveUpdates()
        removePassiveUpdates()
    }

    private fun getGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
