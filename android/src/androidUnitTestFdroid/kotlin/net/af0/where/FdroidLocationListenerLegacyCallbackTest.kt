package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for https://github.com/danmarg/where/issues/367: the LocationListeners
 * registered by FdroidLocationProvider only implemented onLocationChanged. LocationListener's
 * default no-op implementations of onStatusChanged/onProviderEnabled/onProviderDisabled were
 * only added to the platform interface in API 30 (R); on minSdk 26-29 devices, the framework
 * invoking one of those methods on such a listener throws AbstractMethodError.
 *
 * Robolectric's shadow doesn't itself dispatch onProviderEnabled/onProviderDisabled/
 * onStatusChanged when toggling a provider (it just broadcasts PROVIDERS_CHANGED), so this
 * drives the exact real-framework path instead: grab the listener object FdroidLocationProvider
 * actually registered with LocationManager and invoke the legacy callbacks on it directly, same
 * as LocationManagerService does on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestWhereApplication::class)
class FdroidLocationListenerLegacyCallbackTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Suppress("DEPRECATION")
    private fun registerAndInvokeLegacyCallbacks() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val provider = FdroidLocationProvider()
        provider.init(context) { _, _, _ -> }

        val registered = provider.requestActiveUpdates(LocationAccuracy.BALANCED, 30_000L, 30_000L)
        check(registered) { "Test setup failed: provider did not register with GPS enabled" }

        val listener =
            shadowOf(lm).requestLocationUpdateListeners.singleOrNull()
                ?: error("Test setup failed: expected exactly one registered LocationListener")

        // These are exactly the calls LocationManagerService makes on-device; on API 26-29 the
        // platform LocationListener interface has no default body for them, so a listener
        // missing an explicit override throws AbstractMethodError here.
        listener.onProviderEnabled(LocationManager.GPS_PROVIDER)
        listener.onProviderDisabled(LocationManager.GPS_PROVIDER)
        listener.onStatusChanged(LocationManager.GPS_PROVIDER, 0, null)
    }

    @Test
    @Config(sdk = [26])
    fun legacyProviderCallbacks_doNotCrash_onApi26() = registerAndInvokeLegacyCallbacks()

    @Test
    @Config(sdk = [28])
    fun legacyProviderCallbacks_doNotCrash_onApi28() = registerAndInvokeLegacyCallbacks()

    @Test
    @Config(sdk = [29])
    fun legacyProviderCallbacks_doNotCrash_onApi29() = registerAndInvokeLegacyCallbacks()
}
