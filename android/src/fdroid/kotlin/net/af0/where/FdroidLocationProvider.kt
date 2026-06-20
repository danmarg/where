package net.af0.where

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
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

    override fun init(context: Context, onLocation: (Double, Double, Double?) -> Unit) {
        this.onLocationCallback = onLocation
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

    override fun requestActiveUpdates(accuracy: LocationAccuracy, intervalMs: Long, maxDelayMs: Long) {
        removeActiveUpdates()
        val minDistance = when (accuracy) {
            LocationAccuracy.PASSIVE -> 0f
            LocationAccuracy.LOW_POWER -> 50f
            LocationAccuracy.BALANCED -> 20f
            LocationAccuracy.HIGH -> 0f
        }
        val listener = LocationListener { loc ->
            onLocationCallback?.invoke(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
        }
        activeListener = listener
        // Use a single provider to avoid duplicate callbacks. FUSED_PROVIDER is intentionally
        // excluded: it is GMS-provided even on API 31+ and may be absent on de-Googled devices.
        val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            LocationManager.GPS_PROVIDER
        else
            LocationManager.NETWORK_PROVIDER
        try {
            locationManager.requestLocationUpdates(provider, intervalMs, minDistance, listener)
            Log.i(TAG, "Registered active location updates via $provider")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting $provider updates: ${e.message}")
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
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 1_000L, 0f, listener)
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

    // Geofencing is GMS-specific; WorkManager + alarm provide the fallback restart mechanism.
    // TODO: F-Droid background reliability is weaker than the GMS build because there is no
    //  movement-triggered wake (geofence exit). Investigate AlarmManager inexact repeating or
    //  a fused passive listener as a compensating mechanism.
    override fun setGeofenceAt(lat: Double, lng: Double): Boolean = false

    override fun removeGeofence() {}

    override fun onDestroy() {
        removeActiveUpdates()
        removePassiveUpdates()
    }
}
