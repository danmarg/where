package net.af0.where

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The F-Droid provider's side of stationary mode (#483). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class FdroidMotionTriggerTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val lm get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // Robolectric's SensorManager can't arm a trigger sensor; this one has the sensor and accepts.
    private val sensorManager =
        mockk<SensorManager>(relaxed = true).also {
            every { it.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) } returns ShadowSensor.newInstance(Sensor.TYPE_SIGNIFICANT_MOTION)
            every { it.requestTriggerSensor(any(), any()) } returns true
        }

    private val contextWithSensor =
        object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? =
                if (name == Context.SENSOR_SERVICE) sensorManager else super.getSystemService(name)
        }

    private fun provider() = FdroidLocationProvider().also { it.init(context) { _, _, _ -> } }

    private fun providerWithSensor() = FdroidLocationProvider().also { it.init(contextWithSensor) { _, _, _ -> } }

    @Test
    fun noSensor_noTrigger() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = provider()
        assertTrue(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))
        assertFalse(provider.armMotionTrigger {})
    }

    @Test
    fun gpsWithSensor_arms() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = providerWithSensor()
        assertTrue(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))
        assertTrue(provider.armMotionTrigger {})
    }

    @Test
    fun armsOnce() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = providerWithSensor()
        assertTrue(provider.armMotionTrigger {})
        assertTrue(provider.armMotionTrigger {}, "already armed")
        verify(exactly = 1) { sensorManager.requestTriggerSensor(any(), any()) }
        verify(exactly = 0) { sensorManager.cancelTriggerSensor(any(), any()) }

        // After a cancel, arming requests again.
        provider.cancelMotionTrigger()
        assertTrue(provider.armMotionTrigger {})
        verify(exactly = 2) { sensorManager.requestTriggerSensor(any(), any()) }
    }

    @Test
    fun aCancelledTriggerIgnoresALateEvent() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = providerWithSensor()
        val armed = io.mockk.slot<android.hardware.TriggerEventListener>()
        every { sensorManager.requestTriggerSensor(capture(armed), any()) } returns true
        var fired = 0
        assertTrue(provider.armMotionTrigger { fired++ })
        provider.cancelMotionTrigger()
        armed.captured.onTrigger(null)
        assertEquals(0, fired)
    }

    @Test
    fun aLongTimeoutUsesAListenerPastThePlatformCap() =
        runTest {
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val provider = provider()
            val result = async { provider.getCurrentLocation(45_000L) }
            runCurrent()
            assertEquals(1, shadowOf(lm).getLocationUpdateListeners().size, "a listener, not getCurrentLocation's 30 s cap")
            advanceTimeBy(40_000L)
            runCurrent()
            shadowOf(lm).simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 3.0
                    longitude = 3.0
                    time = 3_000L
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(3.0, result.await()?.latitude, "a fix at 40 s still counts")
            assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty(), "removed after the fix")
        }

    @Test
    fun networkOnly_noTrigger() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        shadowOf(lm).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val provider = providerWithSensor()
        assertTrue(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))
        assertFalse(provider.armMotionTrigger {}, "a NETWORK listener's fixes can't show movement; keep it on")
    }

    @Test
    @Config(sdk = [28])
    fun belowApi30_getCurrentLocationTakesAFreshFix() =
        runTest {
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val stale =
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 1.0
                    longitude = 1.0
                    time = 1_000L
                }
            shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, stale)
            val provider = provider()

            val result = async { provider.getCurrentLocation() }
            runCurrent()
            shadowOf(lm).simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 2.0
                    longitude = 2.0
                    time = 2_000L
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(2.0, result.await()?.latitude, "not the last-known fix")
        }

    @Test
    @Config(sdk = [28])
    fun belowApi30_getCurrentLocationTimesOutAndRemovesItsListener() =
        runTest {
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val provider = provider()
            val result = async { provider.getCurrentLocation() }
            runCurrent()
            assertEquals(1, shadowOf(lm).getLocationUpdateListeners().size, "a single update is pending")
            advanceTimeBy(10_001L)
            runCurrent()
            assertNull(result.await(), "no fix in 10 s")
            assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty(), "the single update was cancelled")
        }

    @Test
    fun oneGpsFixHeardByBothListenersCountsOnce() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(lm).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true)
        val provider = provider()
        val heard = mutableListOf<Location>()
        provider.setFixListener { heard += it }
        assertTrue(provider.requestActiveUpdates(LocationAccuracy.BALANCED, 60_000L, 10_000L))
        assertTrue(provider.requestPassiveUpdates())

        val policy = StationaryPolicy()
        policy.startTracking(0L)
        val now = SystemClock.elapsedRealtime()

        fun deliver(north: Double) {
            shadowOf(lm).simulateLocation(
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 37.0 + north / 111_195.0
                    longitude = -122.0
                    accuracy = 10f
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
            shadowOf(Looper.getMainLooper()).idle()
        }
        deliver(0.0)
        policy.entered(policy.lastMovedAt + policy.idleMs)
        heard.forEach { policy.onFix(it.toFix(), now) }
        heard.clear()

        // One outlier fix, which the passive listener hears too. Past both listeners' min time.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2))
        deliver(1_000.0)
        assertTrue(heard.isNotEmpty(), "the fix reached the service")
        val later = SystemClock.elapsedRealtime()
        val moved = heard.map { policy.onFix(it.toFix(), later) }
        assertTrue(moved.none { it }, "heard ${heard.size}x; one observation mustn't confirm itself")
        assertEquals(StationaryPolicy.Mode.STATIONARY, policy.mode)
    }

    private fun Location.toFix() =
        StationaryPolicy.Fix(
            latitude,
            longitude,
            accuracy.toDouble(),
            provider == LocationManager.GPS_PROVIDER,
            elapsedRealtimeNanos / 1_000_000,
        )

    @Test
    fun aStaleEventFromAnEarlierArmingLeavesTheNewOneArmed() {
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val provider = providerWithSensor()
        val armed = mutableListOf<android.hardware.TriggerEventListener>()
        every { sensorManager.requestTriggerSensor(capture(armed), any()) } returns true
        var fired = 0
        assertTrue(provider.armMotionTrigger { fired++ })
        provider.cancelMotionTrigger()
        assertTrue(provider.armMotionTrigger { fired++ })
        assertEquals(2, armed.size)

        armed[0].onTrigger(null)
        assertEquals(0, fired, "the cancelled arming's late event")
        assertTrue(provider.armMotionTrigger { fired++ })
        assertEquals(2, armed.size, "still armed: no new request")

        armed[1].onTrigger(null)
        armed[1].onTrigger(null)
        assertEquals(1, fired, "one wake per arming")
        assertTrue(provider.armMotionTrigger { fired++ })
        assertEquals(3, armed.size, "a fired trigger is disarmed, so it's requested again")
    }

    @Test
    fun aLongFixTimingOutRemovesItsListener() =
        runTest {
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val provider = provider()
            val result = async { provider.getCurrentLocation(45_000L) }
            runCurrent()
            assertEquals(1, shadowOf(lm).getLocationUpdateListeners().size)
            advanceTimeBy(45_001L)
            runCurrent()
            assertNull(result.await())
            assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty(), "removed on timeout")
        }

    @Test
    fun aLongFixCancelledMidWaitRemovesItsListener() =
        runTest {
            shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            val provider = provider()
            val result = async { provider.getCurrentLocation(45_000L) }
            runCurrent()
            assertEquals(1, shadowOf(lm).getLocationUpdateListeners().size)
            advanceTimeBy(20_000L)
            result.cancel()
            runCurrent()
            assertTrue(shadowOf(lm).getLocationUpdateListeners().isEmpty(), "removed on cancellation (service destroyed)")
        }
}
