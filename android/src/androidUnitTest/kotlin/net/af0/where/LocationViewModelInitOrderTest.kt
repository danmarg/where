package net.af0.where

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * In the app, viewModelScope runs on Dispatchers.Main.immediate, so the init-block collectors
 * run synchronously during construction - before any property declared below them is
 * initialized. Constructing the ViewModel while the app is in the foreground starts the
 * location service from that collector; this once crashed every launch with an NPE on a
 * not-yet-initialized property.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationViewModelInitOrderTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

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
        // Like Main.immediate: launch {} runs to its first suspension inside the constructor.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun constructingInTheForegroundStartsTheServiceWithoutCrashing() {
        LocationViewModel(
            app,
            e2eeManagerParam = mockk(relaxed = true),
            userStoreParam = UserStore(MemoryStorage()),
            locationClientParam = mockk(relaxed = true),
            startPolling = false,
            locationSourceParam = TestFakeLocationSource(initialForeground = true),
            uiStateStoreParam = FakeUiStateStore(),
        )

        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }
}
