package net.af0.where

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "FdroidLocationProvider"

class FdroidLocationProvider : LocationProvider {
    private lateinit var locationManager: LocationManager
    private var activeListener: LocationListener? = null
    private var passiveListener: LocationListener? = null
    private var onLocationCallback: ((Double, Double, Double?) -> Unit)? = null
    // Captured at init() time — callers must invoke init() on the thread whose looper
    // should receive location callbacks (LocationService calls init() on the main thread).
    private lateinit var callbackLooper: Looper

    override fun init(context: Context, onLocation: (Double, Double, Double?) -> Unit) {
        this.onLocationCallback = onLocation
        callbackLooper = Looper.myLooper() ?: Looper.getMainLooper()
        locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun getLastLocationAsync(callback: (Location?) -> Unit) {
        val loc = getBestLastKnownLocation()
        callback(loc)
    }

    private fun getBestLastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) { null }
        }.maxByOrNull { it.time }
    }

    override fun requestActiveUpdates(accuracy: LocationAccuracy, intervalMs: Long, maxDelayMs: Long): Boolean {
        removeActiveUpdates()
        // PASSIVE accuracy is handled entirely by requestPassiveUpdates() (PASSIVE_PROVIDER,
        // 1s interval). Registering a second listener here on the same provider would double
        // callbacks during deep sleep, defeating the power intent.
        if (accuracy == LocationAccuracy.PASSIVE) {
            Log.i(TAG, "PASSIVE accuracy: active registration skipped; passive listener covers this")
            return true
        }
        // For real providers: GPS preferred, NETWORK as fallback.
        // FUSED_PROVIDER is intentionally excluded: it is GMS-provided even on API 31+ and
        // may be absent on de-Googled devices.
        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        // Use the same 200m distance filter as GmsLocationProvider so stationary users
        // don't receive a flood of fixes (PASSIVE is handled above and never reaches here).
        val minDistance = LocationService.MOVEMENT_RADIUS_THRESHOLD_METERS
        val listener = LocationListener { loc ->
            onLocationCallback?.invoke(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
        }
        // Mirror GmsLocationProvider's 10s floor to prevent excessive wakeups under fast
        // heartbeat conditions (e.g. activity transition to MOVING uses a 10s interval).
        val effectiveInterval = maxOf(intervalMs, 10_000L)
        return try {
            locationManager.requestLocationUpdates(provider, effectiveInterval, minDistance, listener, callbackLooper)
            activeListener = listener
            Log.i(TAG, "Registered active location updates via $provider (accuracy=$accuracy, intervalMs=$effectiveInterval)")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting $provider updates: ${e.message}")
            false
        }
    }

    override fun requestPassiveUpdates(): Boolean {
        if (!locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            Log.w(TAG, "PASSIVE_PROVIDER not enabled; passive updates skipped")
            return false
        }
        val listener = LocationListener { loc ->
            onLocationCallback?.invoke(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
        }
        return try {
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, listener, callbackLooper)
            passiveListener = listener
            Log.i(TAG, "Registered passive location updates")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting passive updates: ${e.message}")
            false
        }
    }

    override fun removeActiveUpdates() {
        activeListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeActiveUpdates failed: ${e.message}")
            }
            activeListener = null
        }
    }

    override fun removePassiveUpdates() {
        passiveListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (e: Exception) {
                Log.w(TAG, "removePassiveUpdates failed: ${e.message}")
            }
            passiveListener = null
        }
    }

    override suspend fun getCurrentLocation(): Location? {
        return withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    // R == API 30; LocationManager.getCurrentLocation() was added in API 30.
                    // The else branch returns the best last-known fix, which may be stale.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                            LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                        locationManager.getCurrentLocation(provider, signal, Executors.newSingleThreadExecutor()) { loc ->
                            cont.resume(loc)
                        }
                    } else {
                        cont.resume(getBestLastKnownLocation())
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException getting current location: ${e.message}")
                    cont.resume(null)
                }
            }
        }
    }

    override suspend fun getLastLocation(): Location? = getBestLastKnownLocation()

    // Geofencing is GMS-specific; WorkManager + setExactAndAllowWhileIdle alarm are the only
    // restart mechanisms in the F-Droid build.
    // Reliability gap: GMS wakes the service on movement (geofence exit) even after process
    // death; F-Droid wakes only on a 10-min alarm floor and a WorkManager periodic task.
    // On de-Googled devices with aggressive battery optimization (GrapheneOS, LineageOS),
    // users should exempt this app from battery optimization to avoid missed heartbeats.
    override fun setGeofenceAt(lat: Double, lng: Double): Boolean = false

    override fun removeGeofence() {}

    override fun onDestroy() {
        removeActiveUpdates()
        removePassiveUpdates()
    }
}
