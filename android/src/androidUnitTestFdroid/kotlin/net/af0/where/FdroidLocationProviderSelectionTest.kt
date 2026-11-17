package net.af0.where

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The continuous request goes to NETWORK when it is enabled (GPS held on indoors drains the
 * battery without ever fixing), and a GPS-only device releases a request that gets no fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class FdroidLocationProviderSelectionTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val lm get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fixes = mutableListOf<Pair<Double, Double>>()

    private fun provider(
        gps: Boolean,
        network: Boolean,
    ): FdroidLocationProvider {
        val shadow = shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, gps)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, network)
        return FdroidLocationProvider().also { it.init(context) { lat, lng, _ -> fixes += lat to lng } }
    }

    private var fixCount = 0

    private fun fix(provider: String) {
        // Each fix moves at least 200 m past the last and has a monotonically later timestamp,
        // so neither the production distance filter nor Robolectric's own last-fix ordering can
        // silently drop the second one.
        fixCount++
        shadowOf(lm).simulateLocation(
            Location(provider).apply {
                latitude = 37.0 + fixCount * 0.01
                longitude = -122.0
                accuracy = 10f
                time = System.currentTimeMillis() + fixCount
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos() + fixCount * 1_000_000L
            },
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun balancedRequest_usesNetwork_whenBothProvidersEnabled() {
        val p = provider(gps = true, network = true)
        assertTrue(p.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))

        fix(LocationManager.GPS_PROVIDER)
        assertEquals(0, fixes.size, "GPS must not be listened to for a balanced request")
        fix(LocationManager.NETWORK_PROVIDER)
        assertEquals(1, fixes.size)
    }

    @Test
    fun highRequest_usesGps_whenAvailable() {
        val p = provider(gps = true, network = true)
        assertTrue(p.requestActiveUpdates(LocationAccuracy.HIGH, 10_000L, 10_000L))

        fix(LocationManager.NETWORK_PROVIDER)
        assertEquals(0, fixes.size)
        fix(LocationManager.GPS_PROVIDER)
        assertEquals(1, fixes.size)
    }

    @Test
    fun gpsOnlyDevice_releasesRequestThatGetsNoFix() {
        val p = provider(gps = true, network = false)
        assertTrue(p.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))

        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.MINUTES)

        fix(LocationManager.GPS_PROVIDER)
        assertEquals(0, fixes.size, "The no-fix timeout should have removed the GPS listener")
    }

    @Test
    fun gpsOnlyDevice_keepsRequestWhileFixesKeepArriving() {
        val p = provider(gps = true, network = false)
        assertTrue(p.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))

        // Each fix re-arms the no-fix timeout, so a device that keeps producing fixes stays
        // registered even if any one gap approaches the 5-min cap.
        fix(LocationManager.GPS_PROVIDER)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.MINUTES)
        fix(LocationManager.GPS_PROVIDER)
        shadowOf(Looper.getMainLooper()).idleFor(4, TimeUnit.MINUTES)
        fix(LocationManager.GPS_PROVIDER)

        assertEquals(3, fixes.size, "A device that keeps fixing keeps its request")
    }

    @Test
    fun gpsOnlyDevice_releasesRequestOnceFixesStop() {
        val p = provider(gps = true, network = false)
        assertTrue(p.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))

        // A device that fixed once and then went indoors is released within 5 min of the last
        // fix, not held forever (a one-shot cancel would assume stationarity).
        fix(LocationManager.GPS_PROVIDER)
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.MINUTES)
        fix(LocationManager.GPS_PROVIDER)

        assertEquals(1, fixes.size, "The re-armed no-fix timeout should have released the receiver")
    }
}
