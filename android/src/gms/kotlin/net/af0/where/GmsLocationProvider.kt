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

    // addGeofences() resolves asynchronously with no documented ordering guarantee between
    // overlapping calls, and setGeofenceAt() can legitimately be called again (activity
    // transition, geofence-exit rearm, cold start) before a prior call has resolved. Rather
    // than rely on GMS's same-request-ID replacement to sort out whichever registration lands
    // last, serialize explicitly: only one add is ever in flight, and a call arriving mid-flight
    // just replaces the pending target rather than firing a second overlapping request. The
    // in-flight call's own completion (success or failure) drains the latest pending target, if
    // any changed while it was outstanding.
    private var geofenceRequestInFlight = false
    private var pendingGeofenceTarget: Triple<Double, Double, Float>? = null

    override fun setGeofenceAt(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): Boolean {
        if (geofenceRequestInFlight) {
            pendingGeofenceTarget = Triple(lat, lng, radiusMeters)
            return true
        }
        return submitGeofence(lat, lng, radiusMeters)
    }

    private fun submitGeofence(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): Boolean {
        val geofence =
            Geofence.Builder()
                .setRequestId("stationary_fence")
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
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException setting geofence: ${e.message}")
            geofenceRequestInFlight = false
            false
        }
    }

    // Task listeners run on the main looper by default (no Executor was supplied), matching
    // every other call in this class — so this never races with setGeofenceAt() itself.
    private fun onGeofenceRequestSettled() {
        geofenceRequestInFlight = false
        val next = pendingGeofenceTarget ?: return
        pendingGeofenceTarget = null
        submitGeofence(next.first, next.second, next.third)
    }

    override fun removeGeofence() {
        // Drop any queued re-arm target - otherwise an in-flight add's completion could
        // resurrect the geofence right after this call by draining a now-stale pending
        // target (see onGeofenceRequestSettled()). An add already in flight when this is
        // called can't be cancelled at this layer; a fresh setGeofenceAt() after removal
        // still submits normally.
        pendingGeofenceTarget = null
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
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
