package net.af0.where

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
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
private const val GEOFENCE_ID = "stationary_fence"

class GmsLocationProvider : LocationProvider {
    private lateinit var context: Context
    private lateinit var fusedClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var passiveLocationCallback: LocationCallback

    // Captured at init() time — callers must invoke init() on the thread whose looper
    // should receive location callbacks (LocationService calls init() on the main thread).
    private lateinit var callbackLooper: Looper

    @VisibleForTesting
    internal var fusedClientOverride: com.google.android.gms.location.FusedLocationProviderClient? = null

    @VisibleForTesting
    internal var geofencingClientOverride: GeofencingClient? = null

    override fun init(
        context: Context,
        onLocation: (Double, Double, Double?) -> Unit,
    ) {
        this.context = context.applicationContext
        callbackLooper = Looper.myLooper() ?: Looper.getMainLooper()
        fusedClient = fusedClientOverride ?: LocationServices.getFusedLocationProviderClient(context)
        geofencingClient = geofencingClientOverride ?: LocationServices.getGeofencingClient(context)
        locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                }
            }
        passiveLocationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    onLocation(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
                }
            }
    }

    override fun getLastLocationAsync(callback: (Location?) -> Unit) {
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc -> callback(loc) }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException getting last location: ${e.message}")
            callback(null)
        }
    }

    private fun LocationAccuracy.toGmsPriority() =
        when (this) {
            LocationAccuracy.PASSIVE -> Priority.PRIORITY_PASSIVE
            LocationAccuracy.LOW_POWER -> Priority.PRIORITY_LOW_POWER
            LocationAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
        }

    override fun requestActiveUpdates(
        accuracy: LocationAccuracy,
        intervalMs: Long,
        maxDelayMs: Long,
    ): Boolean {
        val request =
            LocationRequest.Builder(accuracy.toGmsPriority(), intervalMs)
                .setMinUpdateIntervalMillis(10_000L)
                .setMinUpdateDistanceMeters(LocationService.MOVEMENT_RADIUS_THRESHOLD_METERS)
                .setMaxUpdateDelayMillis(maxDelayMs)
                .build()
        return try {
            fusedClient.requestLocationUpdates(request, locationCallback, callbackLooper)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting active updates: ${e.message}")
            false
        }
    }

    override fun requestPassiveUpdates(): Boolean {
        val request =
            LocationRequest.Builder(Priority.PRIORITY_PASSIVE, 1_000L)
                .setMinUpdateDistanceMeters(0f)
                .build()
        return try {
            fusedClient.requestLocationUpdates(request, passiveLocationCallback, callbackLooper)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting passive updates: ${e.message}")
            false
        }
    }

    override fun removeActiveUpdates() {
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (_: SecurityException) {
        }
    }

    override fun removePassiveUpdates() {
        try {
            fusedClient.removeLocationUpdates(passiveLocationCallback)
        } catch (_: SecurityException) {
        }
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
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException getting last location: ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last location: ${e.message}")
            null
        }
    }

    // addGeofences()/removeGeofences() resolve asynchronously with no documented ordering
    // guarantee between overlapping calls, and setGeofenceAt() can legitimately be called again
    // (activity transition, geofence-exit rearm, cold start) before a prior call has resolved.
    // Rather than rely on GMS to sort out whichever request lands last, serialize explicitly:
    // only one add *or remove* is ever in flight, and a call arriving mid-flight just replaces
    // the pending operation rather than firing a second overlapping request. The in-flight
    // call's own completion (success or failure) drains the latest pending operation, so the
    // last operation requested is always the last one GMS applies - in particular a remove
    // requested while an add is outstanding runs after that add settles, never before it.
    private sealed interface GeofenceOp {
        data class Add(val lat: Double, val lng: Double, val radiusMeters: Float) : GeofenceOp

        data object Remove : GeofenceOp
    }

    private var geofenceRequestInFlight = false
    private var pendingGeofenceOp: GeofenceOp? = null

    override fun setGeofenceAt(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): GeofenceRequestResult {
        if (geofenceRequestInFlight) {
            pendingGeofenceOp = GeofenceOp.Add(lat, lng, radiusMeters)
            return GeofenceRequestResult.QUEUED
        }
        return submitGeofence(lat, lng, radiusMeters)
    }

    private fun submitGeofence(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): GeofenceRequestResult {
        val geofence =
            Geofence.Builder()
                .setRequestId(GEOFENCE_ID)
                .setCircularRegion(lat, lng, radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        val request =
            GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_EXIT)
                .addGeofence(geofence)
                .build()
        return try {
            geofenceRequestInFlight = true
            geofencingClient.addGeofences(request, getGeofencePendingIntent())
                .addOnSuccessListener {
                    Log.i(TAG, "Geofence registered at $lat, $lng")
                    onGeofenceRequestSettled()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Geofence add failed (fence may not be active): ${e.message}")
                    onGeofenceRequestSettled()
                }
            GeofenceRequestResult.SUBMITTED
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting geofence: ${e.message}")
            geofenceRequestInFlight = false
            GeofenceRequestResult.FAILED
        }
    }

    // Idempotent: removing a fence that isn't registered is harmless, so callers that don't know
    // whether one exists (e.g. after process death) can always call this.
    override fun removeGeofence() {
        if (geofenceRequestInFlight) {
            // Replaces any queued re-plant, and runs once the in-flight add settles - so a late
            // add completion can't leave a live fence behind after sharing stops.
            pendingGeofenceOp = GeofenceOp.Remove
            return
        }
        submitRemoveGeofence()
    }

    private fun submitRemoveGeofence() {
        try {
            geofenceRequestInFlight = true
            geofencingClient.removeGeofences(listOf(GEOFENCE_ID))
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) Log.w(TAG, "Geofence remove failed: ${task.exception?.message}")
                    onGeofenceRequestSettled()
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Geofence remove threw: ${e.message}")
            geofenceRequestInFlight = false
        }
    }

    // Task listeners run on the main looper by default (no Executor was supplied), matching
    // every other call in this class — so this never races with setGeofenceAt() itself.
    private fun onGeofenceRequestSettled() {
        geofenceRequestInFlight = false
        val next = pendingGeofenceOp ?: return
        pendingGeofenceOp = null
        when (next) {
            is GeofenceOp.Add -> submitGeofence(next.lat, next.lng, next.radiusMeters)
            GeofenceOp.Remove -> submitRemoveGeofence()
        }
    }

    override fun onDestroy() {
        removeActiveUpdates()
        removePassiveUpdates()
    }

    private fun getGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
