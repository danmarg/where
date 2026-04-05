package net.af0.where

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.QrPayload
import net.af0.where.model.UserLocation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TestLocationClient records all sendLocation calls for testing throttle behavior.
 */
private class TestLocationClient(store: E2eeStore) : LocationClient("http://localhost", store) {
    data class SendCall(val lat: Double, val lng: Double, val pausedFriendIds: Set<String>)

    private val calls = mutableListOf<SendCall>()

    val sendLocationCallCount: Int
        get() = calls.size

    fun getSendCalls(): List<SendCall> = calls.toList()

    fun clear() = calls.clear()

    override suspend fun sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) {
        calls.add(SendCall(lat, lng, pausedFriendIds))
        // Don't call super - we're mocking
    }
}

/**
 * FakeLocationSource for testing.
 */
private class FakeLocationSource : LocationSource {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation

    private val _users = MutableStateFlow<List<UserLocation>>(emptyList())
    override val users: StateFlow<List<UserLocation>> = _users

    override fun onLocation(lat: Double, lng: Double) {
        _lastLocation.value = lat to lng
    }

    override fun onUsersUpdate(users: List<UserLocation>) {
        _users.value = users
    }
}

private class FakeE2eeStorage : E2eeStorage {
    private val data = mutableMapOf<String, String>()

    override fun getString(key: String): String? = data[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        data[key] = value
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocationViewModelTest {
    private val app: Application = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private var viewModel: LocationViewModel? = null

    @Before
    fun setup() {
        initializeLibsodium()
        Dispatchers.setMain(testDispatcher)
        every { app.getSharedPreferences("where_prefs", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getBoolean("is_sharing", true) } returns true
        every { prefs.getString("display_name", "") } returns "Alice"

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.equals(any(), any()) } answers {
            val a = it.invocation.args[0] as CharSequence?
            val b = it.invocation.args[1] as CharSequence?
            a == b
        }

        // Mock objects that make network calls
        io.mockk.mockkObject(net.af0.where.e2ee.E2eeMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.E2eeMailboxClient.poll(any(), any()) } returns emptyList()
        io.mockk.coEvery { net.af0.where.e2ee.E2eeMailboxClient.post(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        viewModel?.stopPolling()
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testInviteLifecycle_AliceSide() =
        runTest {
            val store = E2eeStore(FakeE2eeStorage())
            val client = LocationClient("http://localhost", store)
            // Disable automatic polling loop to prevent hangs
            viewModel = LocationViewModel(app, store, client, startPolling = false)
            val vm = viewModel!!

            // 1. Create invite
            vm.createInvite()
            assertTrue(vm.inviteState.value is InviteState.Pending)
            assertNotNull(store.pendingQrPayload)

            // 2. Simulate finding an init payload via polling
            val initPayload =
                KeyExchangeInitPayload(
                    v = 1,
                    token = "token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            io.mockk.coEvery { net.af0.where.e2ee.E2eeMailboxClient.poll(any(), any()) } returns listOf(initPayload)

            // Manually trigger poll
            vm.pollPendingInvite()

            assertNotNull(vm.pendingInitPayload.value)
            assertTrue(vm.inviteState.value is InviteState.Consumed)
            assertEquals("Bob", vm.pendingInitPayload.value?.suggestedName)

            // 3. Alice cancels naming Bob
            vm.cancelPendingInit()

            assertNull(vm.pendingInitPayload.value)
            assertTrue(vm.inviteState.value is InviteState.None)
            assertNull(store.pendingQrPayload, "Store should be cleared when Alice cancels")
        }

    @Test
    fun testCancelQrScan_BobSide() =
        runTest {
            viewModel = LocationViewModel(app, startPolling = false)
            val vm = viewModel!!

            val qr = QrPayload(byteArrayOf(1, 2, 3), "Alice", "fp")

            // Bob scans - simulate by setting the private field via reflection since it's Bob's side
            val pendingQrField = LocationViewModel::class.java.getDeclaredField("_pendingQrForNaming")
            pendingQrField.isAccessible = true
            val pendingQrFlow = pendingQrField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<QrPayload?>
            pendingQrFlow.value = qr

            assertEquals(qr, vm.pendingQrForNaming.value)

            // Bob cancels
            vm.cancelQrScan()
            assertNull(vm.pendingQrForNaming.value)
        }

    @Test
    fun testSendLocationThrottle_TwoCallsWithin15Seconds() =
        runTest {
            var currentTime = 1000L
            val store = E2eeStore(FakeE2eeStorage())
            val testClient = TestLocationClient(store)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = FakeLocationSource(),
                )
            val vm = viewModel!!

            // Sharing is already enabled by default from mock (is_sharing = true)
            // Call sendLocationIfNeeded with location change (non-heartbeat)
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = false)
            assertEquals(1, testClient.sendLocationCallCount, "First call should send immediately")

            // Advance clock by 10 seconds (less than 15s threshold)
            currentTime += 10_000L

            // Call again - should be throttled
            vm.sendLocationIfNeeded(lat = 37.7750, lng = -122.4195, isHeartbeat = false)
            assertEquals(1, testClient.sendLocationCallCount, "Second call within 15s should be throttled")
        }

    @Test
    fun testSendLocationThrottle_TwoCallsAfter15Seconds() =
        runTest {
            var currentTime = 1000L
            val store = E2eeStore(FakeE2eeStorage())
            val testClient = TestLocationClient(store)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = FakeLocationSource(),
                )
            val vm = viewModel!!

            // Sharing is already enabled by default from mock (is_sharing = true)
            // Call sendLocationIfNeeded with location change (non-heartbeat)
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = false)
            assertEquals(1, testClient.sendLocationCallCount, "First call should send immediately")

            // Advance clock by 16 seconds (past 15s threshold)
            currentTime += 16_000L

            // Call again - should NOT be throttled
            vm.sendLocationIfNeeded(lat = 37.7750, lng = -122.4195, isHeartbeat = false)
            assertEquals(2, testClient.sendLocationCallCount, "Second call after 15s should send")
        }

    @Test
    fun testSendLocationThrottle_HeartbeatThrottle() =
        runTest {
            var currentTime = 1000L
            val store = E2eeStore(FakeE2eeStorage())
            val testClient = TestLocationClient(store)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = FakeLocationSource(),
                )
            val vm = viewModel!!

            // Sharing is already enabled by default from mock (is_sharing = true)
            // Send initial location
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = false)
            assertEquals(1, testClient.sendLocationCallCount)

            // Advance by 100 seconds (past location change throttle, within heartbeat throttle)
            currentTime += 100_000L

            // Send heartbeat - should be throttled (only 100s < 300s heartbeat threshold)
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = true)
            assertEquals(1, testClient.sendLocationCallCount, "Heartbeat within 300s should be throttled")

            // Advance by 210 more seconds (total 310s from first send)
            currentTime += 210_000L

            // Send another heartbeat - should NOT be throttled (now > 300s)
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = true)
            assertEquals(2, testClient.sendLocationCallCount, "Heartbeat after 300s should send")
        }

    @Test
    fun testSendLocationThrottle_ForceOverridesThrottle() =
        runTest {
            var currentTime = 1000L
            val store = E2eeStore(FakeE2eeStorage())
            val testClient = TestLocationClient(store)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = FakeLocationSource(),
                )
            val vm = viewModel!!

            // Sharing is already enabled by default from mock (is_sharing = true)
            // Send initial location
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = false)
            assertEquals(1, testClient.sendLocationCallCount)

            // Try to send again immediately (within throttle window) with force=true
            vm.sendLocationIfNeeded(lat = 37.7750, lng = -122.4195, isHeartbeat = false, force = true)
            assertEquals(2, testClient.sendLocationCallCount, "Force send should bypass throttle")
        }

    @Test
    fun testSendLocationThrottle_DisabledSharing() =
        runTest {
            var currentTime = 1000L
            val store = E2eeStore(FakeE2eeStorage())
            val testClient = TestLocationClient(store)

            // Create a mock app that returns false for is_sharing
            val disabledSharingApp = mockk<Application>(relaxed = true)
            val disabledPrefs = mockk<SharedPreferences>(relaxed = true)
            every { disabledSharingApp.getSharedPreferences("where_prefs", Context.MODE_PRIVATE) } returns disabledPrefs
            every { disabledPrefs.getBoolean("is_sharing", true) } returns false
            every { disabledPrefs.getString("display_name", "") } returns "Test"

            viewModel =
                LocationViewModel(
                    disabledSharingApp,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = FakeLocationSource(),
                )
            val vm = viewModel!!

            // Sharing is disabled, so any send should be rejected
            vm.sendLocationIfNeeded(lat = 37.7749, lng = -122.4194, isHeartbeat = false)
            assertEquals(0, testClient.sendLocationCallCount, "Sending when sharing is disabled should not send")
        }
}
