package net.af0.where

import android.Manifest
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.RawKeyValueStorage
import net.af0.where.e2ee.UserStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A foreground-service start refused because the app was in the background must not be lost:
 * it is retried when the activity next resumes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationViewModelDeferredStartTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: LocationViewModel
    private val started = mutableListOf<Intent>()
    private var refuse = false

    private class MemoryStorage : RawKeyValueStorage {
        private val data = mutableMapOf<String, String>()

        override fun getString(key: String): String? = data[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            data[key] = value
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // Sharing defaults to on; start from off so setSharing(true) is a real transition.
        val userStore = UserStore(MemoryStorage()).apply { setSharing(false) }
        vm =
            LocationViewModel(
                app,
                e2eeManagerParam = mockk(relaxed = true),
                userStoreParam = userStore,
                locationClientParam = mockk(relaxed = true),
                startPolling = false,
                locationSourceParam = TestFakeLocationSource(),
                uiStateStoreParam = FakeUiStateStore(),
            )
        vm.serviceStarter = { intent ->
            if (refuse) throw ForegroundServiceStartNotAllowedException("app in background")
            started += intent
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun refusedStartWhenEnablingSharingIsRetriedOnResume() {
        refuse = true
        vm.setSharing(true)
        assertTrue(vm.isSharingLocation.value)
        assertTrue(started.isEmpty())

        refuse = false
        vm.retryDeferredServiceStarts()
        assertEquals(listOf(LocationService.ACTION_FORCE_PUBLISH), started.map { it.action })

        // Retried once, not on every later resume.
        vm.retryDeferredServiceStarts()
        assertEquals(1, started.size)
    }

    @Test
    fun startStillRefusedOnResumeStaysPending() {
        refuse = true
        vm.setSharing(true)
        vm.retryDeferredServiceStarts()
        assertTrue(started.isEmpty())

        refuse = false
        vm.retryDeferredServiceStarts()
        assertEquals(1, started.size)
    }

    @Test
    fun repeatedRefusalsOfTheSameRequestCollapseToOneRetry() {
        refuse = true
        vm.setSharing(true)
        vm.setSharing(false)
        vm.setSharing(true)

        refuse = false
        vm.retryDeferredServiceStarts()
        assertEquals(1, started.size)
    }

    @Test
    fun otherIllegalStateExceptionsStillPropagate() {
        vm.serviceStarter = { throw IllegalStateException("unexpected") }
        assertFailsWith<IllegalStateException> { vm.setSharing(true) }
    }
}
