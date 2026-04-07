package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        ShadowLog.stream = System.out
        LocationRepository.reset()
    }

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
    }

    @Test
    fun testDeduplication_BugC() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        controller.create()

        // Explicitly call onStartCommand with any intent since Robolectric might not trigger it synchronously during create
        controller.startCommand(0, 0)

        assertTrue(getServiceIsRegistered(service))

        // Multiple startCommand calls must not attempt to re-register location updates.
        controller.startCommand(0, 1)
        controller.startCommand(0, 2)

        assertTrue(getServiceIsRegistered(service))
    }
}
