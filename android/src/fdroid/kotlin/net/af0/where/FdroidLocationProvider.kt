package net.af0.where

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "FdroidLocationProvider"
private const val GPS_ONE_SHOT_TIMEOUT_MS = 10_000L
private const val GPS_NO_FIX_TIMEOUT_MS = 5 * 60_000L

// After this long, the "last GPS one-shot timed out" hint is forgotten: conditions (indoors vs.
// outside, sharing stop/restart) may have changed and GPS deserves another chance.
private const val GPS_ONE_SHOT_FAIL_RETRY_MS = 10 * 60_000L

class FdroidLocationProvider : LocationProvider {
    private lateinit var locationManager: LocationManager
    private var activeListener: LocationListener? = null
    private var passiveListener: LocationListener? = null
    private var onLocationCallback: ((Double, Double, Double?) -> Unit)? = null

    // Monotonic ms at which the last one-shot GPS fix timed out (0 = never, or forgotten). The
    // next one-shot within GPS_ONE_SHOT_FAIL_RETRY_MS goes to NETWORK instead of spending another
    // GPS_ONE_SHOT_TIMEOUT_MS searching. Reads and writes are confined to the callback looper.
    private var lastGpsOneShotFailAt = 0L

    // Stops a continuous GPS-only request that never produces a fix; see armGpsNoFixTimeout.
    private var gpsNoFixTimeout: Runnable? = null

    // Captured at init() time — callers must invoke init() on the thread whose looper
    // should receive location callbacks (LocationService calls init() on the main thread).
    private lateinit var callbackLooper: Looper

    // The handler the no-fix timeout is posted to. One instance is kept so removeCallbacks
    // matches the post site (MessageQueue.removeMessages filters by Handler instance).
    private lateinit var callbackHandler: Handler

    // One-shot GPS fixes on API >= R take an Executor. Reused so each getCurrentLocation call
    // doesn't leak a non-daemon thread.
    private val oneShotExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    override fun init(
        context: Context,
        onLocation: (Double, Double, Double?) -> Unit,
    ) {
        this.onLocationCallback = onLocation
        callbackLooper = Looper.myLooper() ?: Looper.getMainLooper()
        callbackHandler = Handler(callbackLooper)
        locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun getLastLocationAsync(callback: (Location?) -> Unit) {
        val loc = getBestLastKnownLocation()
        callback(loc)
    }

    // Providers are chosen from the enabled list, never probed by name: some AOSP/de-Googled
    // builds have no network location provider at all, and LocationManager throws
    // IllegalArgumentException for a name it doesn't recognize (isProviderEnabled() itself does
    // on some OEM builds). FUSED_PROVIDER is excluded: GMS-injected, absent on de-Googled devices.
    //
    // Provider for the continuous request. GPS holds the receiver on for as long as the request
    // lives and, with no sky view, searches without ever getting a fix, so it is used only when
    // HIGH accuracy is asked for (this flavor can't reach HIGH: that comes from a geofence exit)
    // or when it is the only provider enabled. The 200 m distance filter doesn't help: it
    // suppresses callbacks, not the receiver's work. NETWORK works indoors at a fraction of the
    // cost, and a stationary device wants nothing finer.
    private fun selectActiveProvider(accuracy: LocationAccuracy): String? {
        val enabled = locationManager.getProviders(true)
        return when {
            accuracy == LocationAccuracy.HIGH && enabled.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabled.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            enabled.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
    }

    // Provider for a one-shot fix: GPS first for accuracy, NETWORK for a GPS_ONE_SHOT_FAIL_RETRY_MS
    // window after a GPS timeout (so an indoor miss doesn't cost another full timeout on each call).
    private fun selectOneShotProvider(): String? {
        val enabled = locationManager.getProviders(true)
        val network = enabled.contains(LocationManager.NETWORK_PROVIDER)
        val gps = enabled.contains(LocationManager.GPS_PROVIDER)
        val skipGps = lastGpsOneShotFailAt > 0 &&
            (android.os.SystemClock.elapsedRealtime() - lastGpsOneShotFailAt) < GPS_ONE_SHOT_FAIL_RETRY_MS
        return when {
            skipGps && network -> LocationManager.NETWORK_PROVIDER
            gps -> LocationManager.GPS_PROVIDER
            network -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
    }

    // A GPS-only device (no network location provider, e.g. AOSP without a network location
    // service) can't be moved to NETWORK. If that request gets no fix within
    // GPS_NO_FIX_TIMEOUT_MS it is removed rather than left searching. Nothing re-arms it here:
    // LocationService's stale-registration watchdog re-registers once no callback has arrived
    // for STALE_REGISTRATION_THRESHOLD_MS, so the receiver is off for the rest of that window.
    // The first callback cancels the timeout; a fix that arrived means GPS can see the sky.
    private fun armGpsNoFixTimeout(listener: LocationListener) {
        cancelGpsNoFixTimeout()
        val timeout =
            Runnable {
                if (activeListener === listener) {
                    Log.i(TAG, "No GPS fix within ${GPS_NO_FIX_TIMEOUT_MS / 60_000} min; releasing the receiver until re-registration")
                    removeActiveUpdates()
                }
            }
        gpsNoFixTimeout = timeout
        callbackHandler.postDelayed(timeout, GPS_NO_FIX_TIMEOUT_MS)
    }

    private fun cancelGpsNoFixTimeout() {
        gpsNoFixTimeout?.let { callbackHandler.removeCallbacks(it) }
        gpsNoFixTimeout = null
    }

    // LocationListener only gained default no-op implementations of onStatusChanged /
    // onProviderEnabled / onProviderDisabled in API 30 (R). On API 26-29 (minSdk is 26) the
    // platform interface has no defaults for those, so a listener that only implements
    // onLocationChanged (e.g. via SAM conversion) throws AbstractMethodError the first time the
    // framework invokes one of them. Implement all four explicitly so this works down to minSdk.
    private fun createLocationListener(onLocation: (Location) -> Unit): LocationListener =
        object : LocationListener {
            override fun onLocationChanged(loc: Location) = onLocation(loc)

            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: Bundle?,
            ) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }

    private fun getBestLastKnownLocation(): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
    }

    override fun requestActiveUpdates(
        accuracy: LocationAccuracy,
        intervalMs: Long,
        maxDelayMs: Long,
    ): Boolean {
        removeActiveUpdates()
        // PASSIVE accuracy is handled entirely by requestPassiveUpdates() (PASSIVE_PROVIDER,
        // 1s interval). Registering a second listener here on the same provider would double
        // callbacks during deep sleep, defeating the power intent.
        // Return whether passive actually registered so the caller's isRegistered flag is honest.
        if (accuracy == LocationAccuracy.PASSIVE) {
            val ok = passiveListener != null
            Log.i(TAG, "PASSIVE accuracy: active registration skipped; passive listener covers this (registered=$ok)")
            return ok
        }
        val provider = selectActiveProvider(accuracy)
        if (provider == null) {
            Log.w(TAG, "No enabled location provider (GPS or NETWORK) available; active registration skipped")
            return false
        }
        // Use the same 200m distance filter as GmsLocationProvider so stationary users
        // don't receive a flood of fixes (PASSIVE is handled above and never reaches here).
        val minDistance = LocationService.MOVEMENT_RADIUS_THRESHOLD_METERS
        val listener =
            createLocationListener { loc ->
                cancelGpsNoFixTimeout()
                onLocationCallback?.invoke(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
            }
        // Mirror GmsLocationProvider's 10s floor to prevent excessive wakeups under fast
        // heartbeat conditions (e.g. activity transition to MOVING uses a 10s interval).
        // maxDelayMs (batch delivery control) has no LocationManager equivalent; ignored per interface contract.
        val effectiveInterval = maxOf(intervalMs, 10_000L)
        return try {
            locationManager.requestLocationUpdates(provider, effectiveInterval, minDistance, listener, callbackLooper)
            activeListener = listener
            // Any GPS registration that never fixes should be released: HIGH asked for on purpose
            // is still wasteful indoors (e.g. a geofence-exit false-positive). LocationService's
            // stale-registration watchdog re-registers once the watchdog's threshold passes.
            if (provider == LocationManager.GPS_PROVIDER) armGpsNoFixTimeout(listener)
            Log.i(TAG, "Registered active location updates via $provider (accuracy=$accuracy, intervalMs=$effectiveInterval)")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException requesting $provider updates: ${e.message}")
            false
        } catch (e: IllegalArgumentException) {
            // Provider existed when selectActiveProvider()/selectOneShotProvider() checked but vanished before this
            // call (TOCTOU) - defensive, since LocationManager throws for an unrecognized name.
            Log.w(TAG, "$provider no longer available requesting updates: ${e.message}")
            false
        }
    }

    override fun requestPassiveUpdates(): Boolean {
        if (!locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            Log.w(TAG, "PASSIVE_PROVIDER not enabled; passive updates skipped")
            return false
        }
        val listener =
            createLocationListener { loc ->
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
        cancelGpsNoFixTimeout()
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
        val oneShotProvider = selectOneShotProvider()
        val result =
            withTimeoutOrNull(GPS_ONE_SHOT_TIMEOUT_MS) {
                getCurrentLocationFrom(oneShotProvider)
            }
        if (oneShotProvider == LocationManager.GPS_PROVIDER) {
            // Null here is either the GPS callback resolving null or the outer timeout cancelling
            // it; either way the fix wasn't delivered in time. A successful GPS one-shot clears
            // the hint; the time-based decay in selectOneShotProvider lets GPS retry later
            // regardless, so a NETWORK run doesn't need to clear it itself.
            lastGpsOneShotFailAt = if (result == null) android.os.SystemClock.elapsedRealtime() else 0L
        }
        return result
    }

    private suspend fun getCurrentLocationFrom(provider: String?): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                // R == API 30; LocationManager.getCurrentLocation() was added in API 30.
                // The else branch returns the best last-known fix, which may be stale.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (provider == null) {
                        Log.w(TAG, "No enabled location provider (GPS or NETWORK) available; falling back to last-known fix")
                        cont.resume(getBestLastKnownLocation())
                    } else {
                        val signal = CancellationSignal()
                        cont.invokeOnCancellation { signal.cancel() }
                        locationManager.getCurrentLocation(provider, signal, oneShotExecutor) { loc ->
                            cont.resume(loc)
                        }
                    }
                } else {
                    cont.resume(getBestLastKnownLocation())
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException getting current location: ${e.message}")
                cont.resume(null)
            } catch (e: IllegalArgumentException) {
                // Provider existed when selectActiveProvider()/selectOneShotProvider() checked but vanished before
                // this call (TOCTOU) - defensive, since LocationManager throws for an
                // unrecognized name.
                Log.w(TAG, "Provider no longer available getting current location: ${e.message}")
                cont.resume(null)
            }
        }

    override suspend fun getLastLocation(): Location? = getBestLastKnownLocation()

    // Geofencing is GMS-specific; WorkManager + setExactAndAllowWhileIdle alarm are the only
    // restart mechanisms in the F-Droid build.
    // Reliability gap: GMS wakes the service on movement (geofence exit) even after process
    // death; F-Droid wakes only on a 10-min alarm floor and a WorkManager periodic task.
    // On de-Googled devices with aggressive battery optimization (GrapheneOS, LineageOS),
    // users should exempt this app from battery optimization to avoid missed heartbeats.
    override fun setGeofenceAt(
        lat: Double,
        lng: Double,
        radiusMeters: Float,
    ): GeofenceRequestResult = GeofenceRequestResult.UNSUPPORTED

    override fun onDestroy() {
        removeActiveUpdates()
        removePassiveUpdates()
    }
}
