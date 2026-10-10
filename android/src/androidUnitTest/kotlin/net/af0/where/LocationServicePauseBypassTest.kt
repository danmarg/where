package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.LocationClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A single-friend forced publish must respect that friend's pause. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServicePauseBypassTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        LocationService.clock = { System.currentTimeMillis() }
        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        (context as TestWhereApplication).userStore.setPausedFriends(emptySet())
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun forcePublish(friendId: String) =
        Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_FORCE_PUBLISH
            putExtra(LocationService.EXTRA_FRIEND_ID, friendId)
        }

    @Test
    fun forcePublishToPausedFriendIsNotSent() =
        runTest {
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            app.userStore.setPausedFriends(setOf("paused-friend"))

            val client = mockk<LocationClient>(relaxed = true)
            val source = ServiceFakeLocationSource().apply { onLocation(37.0, -122.0, null) }
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationProviderOverride = mockk(relaxed = true)
            service.locationClientOverride = client
            service.e2eeManagerOverride = mockk(relaxed = true)
            service.locationSourceOverride = source
            controller.create()

            service.onStartCommand(forcePublish("paused-friend"), 0, 1)
            service.onStartCommand(forcePublish("active-friend"), 0, 2)
            advanceUntilIdle()

            coVerify(exactly = 0) { client.sendLocationToFriend("paused-friend", any(), any(), any()) }
            coVerify(exactly = 1) { client.sendLocationToFriend("active-friend", any(), any(), any()) }
            controller.destroy()
        }
}
