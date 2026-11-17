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
import kotlinx.coroutines.async
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
}
