package net.af0.where

import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Regression tests for https://github.com/danmarg/where/issues/368: the GPS-disabled fallback
 * unconditionally selected NETWORK_PROVIDER, which crashes with IllegalArgumentException on a
 * device/build with no network location provider installed at all (a real config on some
 * de-Googled/AOSP builds this app targets).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class FdroidLocationProviderFallbackTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    private fun providerWithNoGpsOrNetwork(): FdroidLocationProvider {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(lm)
        // Simulate a device where neither GPS nor NETWORK_PROVIDER exists at all (not merely
        // disabled) - LocationManager throws IllegalArgumentException for an unrecognized
        // provider name, which is exactly the crash this test guards against.
        if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            shadow.removeProvider(LocationManager.GPS_PROVIDER)
        }
        if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
            shadow.removeProvider(LocationManager.NETWORK_PROVIDER)
        }

        val provider = FdroidLocationProvider()
        provider.init(context) { _, _, _ -> }
        return provider
    }

    @Test
    fun requestActiveUpdates_returnsFalseWithoutCrashing_whenNoProviderAvailable() {
        val provider = providerWithNoGpsOrNetwork()

        val registered = provider.requestActiveUpdates(LocationAccuracy.BALANCED, 30_000L, 30_000L)

        assertFalse(registered, "Should decline to register rather than crash with no provider available")
    }

    @Test
    fun getCurrentLocation_resolvesNullWithoutCrashing_whenNoProviderAvailable() =
        runTest {
            val provider = providerWithNoGpsOrNetwork()

            val loc = provider.getCurrentLocation()

            assertNull(loc, "No provider and no last-known fix should resolve to null, not throw")
        }
}
