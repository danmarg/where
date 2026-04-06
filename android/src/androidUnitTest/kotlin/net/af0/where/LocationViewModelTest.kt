package net.af0.where

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import app.cash.turbine.turbineScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.FriendEntry
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TestLocationClient records all sendLocation calls for testing throttle behavior.
 */
private open class TestLocationClient(store: E2eeStore) : LocationClient("http://localhost", store) {
    data class SendCall(val lat: Double, val lng: Double, val pausedFriendIds: Set<String>)

    private val calls = mutableListOf<SendCall>()

    val sendLocationCallCount: Int
        get() = calls.size

    fun getSendCalls(): List<SendCall> = calls.toList()

    fun clear() = calls.clear()

    override suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String>,
    ) {
        calls.add(SendCall(lat, lng, pausedFriendIds))
        // Don't call super - we're mocking
    }
}

/**
 * FailingLocationClient throws on postOpkBundle to test exception handling in finally blocks.
 */
private class FailingLocationClient(store: E2eeStore) : TestLocationClient(store) {
    override suspend fun postOpkBundle(friendId: String) {
        throw Exception("Simulated postOpkBundle failure")
    }
}

/**
 * FakeLocationSource for testing.
 */
private class FakeLocationSource : LocationSource {
    private val _lastLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    override val lastLocation: StateFlow<Pair<Double, Double>?> = _lastLocation

    private val _friendLocations = MutableStateFlow<Map<String, UserLocation>>(emptyMap())
    override val friendLocations: StateFlow<Map<String, UserLocation>> = _friendLocations

    private val _friendLastPing = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _isAppInForeground = MutableStateFlow(false)
    override val isAppInForeground: StateFlow<Boolean> = _isAppInForeground

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload

    private val _isSharingLocation = MutableStateFlow(true)
    override val isSharingLocation: StateFlow<Boolean> = _isSharingLocation

    private val _pausedFriendIds = MutableStateFlow<Set<String>>(emptySet())
    override val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds

    override fun onLocation(
        lat: Double,
        lng: Double,
    ) {
        _lastLocation.value = lat to lng
    }

    override fun onFriendUpdate(update: UserLocation, timestamp: Long) {
        _friendLocations.value += (update.userId to update)
        _friendLastPing.value += (update.userId to timestamp)
    }

    override fun onFriendRemoved(id: String) {
        _friendLocations.value -= id
        _friendLastPing.value -= id
    }

    override fun onConnectionStatus(status: ConnectionStatus) {
        _connectionStatus.value = status
    }

    override fun onConnectionError(e: Throwable) {
        val msg =
            when {
                e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "not resolved"
                e.message?.contains("timeout", ignoreCase = true) == true -> "timeout"
                e.message?.contains("ConnectException", ignoreCase = true) == true -> "no connection"
                e.message?.contains("Failed to post to mailbox: 500", ignoreCase = true) == true -> "server error 500"
                else -> e.message?.take(32) ?: "unknown error"
            }
        _connectionStatus.value = ConnectionStatus.Error(msg)
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(payload: KeyExchangeInitPayload?) {
        _pendingInitPayload.value = payload
    }

    override fun setSharingLocation(sharing: Boolean) {
        _isSharingLocation.value = sharing
    }

    override fun setPausedFriends(friendIds: Set<String>) {
        _pausedFriendIds.value = friendIds
    }

    override fun setInitialFriendLocations(locations: Map<String, UserLocation>, pings: Map<String, Long>) {
        _friendLocations.value += locations
        _friendLastPing.value += pings
    }

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends

    override val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    private val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger

    override fun onFriendsUpdated(friends: List<FriendEntry>) {
        _friends.value = friends
    }

    override fun triggerRapidPoll() {
        _lastRapidPollTrigger.value = System.currentTimeMillis()
        pollWakeSignal.trySend(Unit)
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
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testInviteLifecycle_AliceSide() =
        runTest {
            val fakeLocationSource = FakeLocationSource()
            val store = E2eeStore(FakeE2eeStorage())
            val client = LocationClient("http://localhost", store)
            // Disable automatic polling loop to prevent hangs
            viewModel = LocationViewModel(app, store, client, startPolling = false, locationSource = fakeLocationSource)
            val vm = viewModel!!

            // 1. Create invite
            vm.createInvite()
            advanceUntilIdle()
            assertTrue(vm.inviteState.value is InviteState.Pending)
            val qr = store.pendingQrPayload()
            assertNotNull(qr)

            // 2. Simulate finding an init payload via polling
            val initPayload =
                KeyExchangeInitPayload(
                    v = 1,
                    token = "token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // Bob's response
            (vm.pendingInitPayload as MutableStateFlow).value = initPayload

            // Since we're not running the service, we simulate the effect of the service polling
            val inviteStateField = LocationViewModel::class.java.getDeclaredField("_inviteState")
            inviteStateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (inviteStateField.get(vm) as MutableStateFlow<InviteState>).value = InviteState.Consumed(qr)

            assertTrue(vm.inviteState.value is InviteState.Consumed)
            assertEquals("Bob", vm.pendingInitPayload.value?.suggestedName)

            // 3. Alice cancels naming Bob
            vm.cancelPendingInit()
            advanceUntilIdle()

            assertNull(vm.pendingInitPayload.value)
            assertTrue(vm.inviteState.value is InviteState.None)
            assertNull(store.pendingQrPayload(), "Store should be cleared when Alice cancels")
        }

    @Test
    fun testCancelQrScan_BobSide() =
        runTest {
            viewModel = LocationViewModel(app, startPolling = false)
            val vm = viewModel!!

            val qr = QrPayload(byteArrayOf(1, 2, 3), "Alice", "fp")

            // Bob scans
            (vm.pendingQrForNaming as MutableStateFlow).value = qr

            assertEquals(qr, vm.pendingQrForNaming.value)

            // Bob cancels
            vm.cancelQrScan()
            assertNull(vm.pendingQrForNaming.value)
        }

    // ============ Pairing State Machine Integration Tests ============

    @Test
    fun testPairingFlow_ConfirmPendingInit() =
        runTest {
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val testClient = TestLocationClient(mockk(relaxed = true))
            val fakeLocationSource = FakeLocationSource()
            fakeLocationSource.onLocation(37.7749, -122.4194)

            // Mock the store to return a friend when processKeyExchangeInit is called
            val newFriend =
                FriendEntry(
                    name = "Bob",
                    session = mockk(relaxed = true),
                    isInitiator = false,
                    lastLat = null,
                    lastLng = null,
                    lastTs = null,
                )
            io.mockk.coEvery { store.processKeyExchangeInit(any(), any()) } returns newFriend
            io.mockk.coEvery { store.listFriends() } returns listOf(newFriend)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = fakeLocationSource,
                )
            val vm = viewModel!!

            // 1. Verify initial state: not exchanging, no pending init
            assertFalse(vm.isExchanging.value, "Should not be exchanging initially")
            assertNull(vm.pendingInitPayload.value, "Should have no pending init payload initially")

            // 2. Create a pending init payload (simulating Alice receiving Bob's response during polling)
            val initPayload =
                KeyExchangeInitPayload(
                    v = 1,
                    token = "test_token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )
            (vm.pendingInitPayload as MutableStateFlow).value = initPayload

            // Verify pending init is set
            assertNotNull(vm.pendingInitPayload.value, "Pending init should be set")

            // 3. Call confirmPendingInit - this should trigger state transitions
            vm.confirmPendingInit(name = "Bob")

            // 4. Immediately after confirmPendingInit call (synchronous part):
            // - pendingInitPayload should be cleared (synchronously)
            // - isExchanging should be true (set before async coroutine)
            assertNull(vm.pendingInitPayload.value, "Pending init should be cleared after confirmPendingInit")
            assertTrue(vm.isExchanging.value, "isExchanging should be true after confirmPendingInit")

            // 5. Advance the test scheduler to let the viewModelScope coroutine run
            advanceUntilIdle()

            // 6. After the coroutine completes (including the finally block):
            // - isExchanging should be false again (set in finally block)
            // - friends should be updated
            assertFalse(vm.isExchanging.value, "isExchanging should be false after coroutine completes")
            assertTrue(
                vm.friends.value.size > 0,
                "friends list should be updated after successful exchange",
            )
        }

    @Test
    fun testPairingFlow_IsExchangingStateTransitions() =
        runTest {
            // Simplified test: focus on isExchanging state machine (the critical invariant)
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val testClient = TestLocationClient(mockk(relaxed = true))
            val fakeLocationSource = FakeLocationSource()
            fakeLocationSource.onLocation(37.7749, -122.4194)

            // Mock store to return a friend
            val newFriend =
                FriendEntry(
                    name = "Alice",
                    session = mockk(relaxed = true),
                    isInitiator = true,
                    lastLat = null,
                    lastLng = null,
                    lastTs = null,
                )
            io.mockk.coEvery { store.processScannedQr(any(), any()) } returns Pair(mockk(relaxed = true), newFriend)
            io.mockk.coEvery { store.listFriends() } returns emptyList()

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = fakeLocationSource,
                )
            val vm = viewModel!!

            // 1. Initial state: not exchanging
            assertFalse(vm.isExchanging.value, "Should not be exchanging initially")

            // 2. Simulate Bob scanning Alice's QR code
            val qr =
                QrPayload(
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    fingerprint = "alice_fp",
                )

            // 3. Call confirmQrScan (synchronous sets isExchanging = true before async work)
            vm.confirmQrScan(qr = qr, friendName = "Alice")

            // 4. isExchanging should be true immediately
            assertTrue(vm.isExchanging.value, "isExchanging should be true immediately after confirmQrScan")

            // 5. Let async coroutine complete
            advanceUntilIdle()

            // 6. CRITICAL INVARIANT: isExchanging MUST be false after coroutine completes (finally block)
            assertFalse(vm.isExchanging.value, "CRITICAL: isExchanging must be false after coroutine completes")
        }

    @Test
    fun testPairingFlow_ExchangingResetAfterSuccess() =
        runTest {
            // Test that successfully completing the exchange resets isExchanging to false
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val testClient = TestLocationClient(mockk(relaxed = true))
            val fakeLocationSource = FakeLocationSource()
            fakeLocationSource.onLocation(37.7749, -122.4194)

            // Mock store to successfully process the exchange
            val newFriend =
                FriendEntry(
                    name = "Bob",
                    session = mockk(relaxed = true),
                    isInitiator = false,
                    lastLat = null,
                    lastLng = null,
                    lastTs = null,
                )
            io.mockk.coEvery { store.processKeyExchangeInit(any(), any()) } returns newFriend
            io.mockk.coEvery { store.listFriends() } returns listOf(newFriend)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = fakeLocationSource,
                )
            val vm = viewModel!!

            // Setup pending init
            val initPayload =
                KeyExchangeInitPayload(
                    v = 1,
                    token = "test",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )
            (vm.pendingInitPayload as MutableStateFlow).value = initPayload

            // Verify initial state
            assertFalse(vm.isExchanging.value, "Should start with isExchanging=false")

            // Call confirmPendingInit
            vm.confirmPendingInit(name = "Bob")

            // Immediately: isExchanging should be true
            assertTrue(vm.isExchanging.value, "isExchanging should be true during exchange")

            // Let coroutine complete successfully
            advanceUntilIdle()

            // CRITICAL: After successful completion, finally block must reset isExchanging to false
            assertFalse(
                vm.isExchanging.value,
                "isExchanging MUST be false after exchange completes (finally block)",
            )

            // Verify exchange actually happened
            assertTrue(vm.friends.value.size > 0, "Friends should be updated after exchange")
        }

    // ============ removeFriend Atomicity Tests ============

    @Test
    fun testRemoveFriend_RemovesFromAllCollections() =
        runTest {
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val testClient = TestLocationClient(mockk(relaxed = true))
            val source = FakeLocationSource()

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = testClient,
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = source,
                )
            val vm = viewModel!!
            advanceUntilIdle()

            // 1. Seed the ViewModel with a friend
            val friendId = "friend_alice_123"
            val friendEntry =
                FriendEntry(
                    name = "Alice",
                    session = mockk(relaxed = true),
                    isInitiator = true,
                    lastLat = 37.7749,
                    lastLng = -122.4194,
                    lastTs = 1000L,
                )

            // Inject friend into the friends list
            (vm.friends as MutableStateFlow).value = listOf(friendEntry)

            // Inject into friendLocations
            val location = UserLocation(friendId, 37.7749, -122.4194, 1000L)
            source.onFriendUpdate(location, 1000L)

            // Inject into pausedFriendIds
            source.setPausedFriends(setOf(friendId, "other_friend"))

            // Mock store to return empty list after deletion
            io.mockk.coEvery { store.listFriends() } returns emptyList()

            // 2. Verify initial state: friend exists in all three collections
            assertEquals(1, vm.friends.value.size, "Should have one friend initially")
            assertEquals(1, vm.friendLocations.value.size, "Should have one location initially")
            assertTrue(friendId in vm.pausedFriendIds.value, "Friend should be paused initially")

            // 3. Call removeFriend
            vm.removeFriend(friendId)
            advanceUntilIdle()

            // 4. CRITICAL: Assert atomicity - all three collections are cleaned up in one snapshot
            assertEquals(
                0,
                vm.friends.value.size,
                "Friend must be removed from friends list",
            )
            assertEquals(
                0,
                vm.friendLocations.value.size,
                "Friend location must be removed from friendLocations",
            )
            assertFalse(
                friendId in vm.pausedFriendIds.value,
                "Friend must be removed from pausedFriendIds",
            )

            // 5. Verify other friends still exist
            assertEquals(1, vm.pausedFriendIds.value.size, "Other paused friends should remain")
            assertTrue("other_friend" in vm.pausedFriendIds.value)

            // 6. Verify store was updated
            io.mockk.coVerify { store.deleteFriend(friendId) }
        }

    @Test
    fun testRemoveFriend_NotPausedFriend() =
        runTest {
            // Test removeFriend when the friend is not in pausedFriendIds
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val source = FakeLocationSource()

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = TestLocationClient(mockk(relaxed = true)),
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = source,
                )
            val vm = viewModel!!
            advanceUntilIdle()

            val friendId = "friend_bob_456"
            val friendEntry =
                FriendEntry(
                    name = "Bob",
                    session = mockk(relaxed = true),
                    isInitiator = false,
                    lastLat = null,
                    lastLng = null,
                    lastTs = null,
                )

            // Inject friend (not paused)
            (vm.friends as MutableStateFlow).value = listOf(friendEntry)

            val location = UserLocation(friendId, 40.7128, -74.0060, 2000L)
            source.onFriendUpdate(location, 2000L)

            // pausedFriendIds is empty
            source.setPausedFriends(emptySet())

            io.mockk.coEvery { store.listFriends() } returns emptyList()

            // Call removeFriend
            vm.removeFriend(friendId)
            advanceUntilIdle()

            // Verify cleanup (paused branch not taken, but cleanup still happens)
            assertEquals(0, vm.friends.value.size)
            assertEquals(0, vm.friendLocations.value.size)
            assertEquals(0, vm.pausedFriendIds.value.size)
        }

    @Test
    fun testRemoveFriend_MultipleFriendsPresent() =
        runTest {
            // Test removeFriend when multiple friends exist - verify only target is removed
            var currentTime = 1_000_000_000L
            val store = mockk<E2eeStore>(relaxed = true)
            val source = FakeLocationSource()

            // Mock listFriends() to return empty list during init()
            io.mockk.coEvery { store.listFriends() } returns emptyList()

            viewModel =
                LocationViewModel(
                    app,
                    e2eeStore = store,
                    locationClient = TestLocationClient(mockk(relaxed = true)),
                    startPolling = false,
                    clock = { currentTime },
                    locationSource = source,
                )
            val vm = viewModel!!
            advanceUntilIdle() // Let init() block complete

            val alice =
                FriendEntry(
                    name = "Alice",
                    session =
                        mockk(relaxed = true) {
                            every { aliceFp } returns byteArrayOf(0, 1)
                        },
                )
            val bob =
                FriendEntry(
                    name = "Bob",
                    session =
                        mockk(relaxed = true) {
                            every { aliceFp } returns byteArrayOf(2, 3)
                        },
                )
            val charlie =
                FriendEntry(
                    name = "Charlie",
                    session =
                        mockk(relaxed = true) {
                            every { aliceFp } returns byteArrayOf(4, 5)
                        },
                )

            // Mock the ID explicitly to match what FriendEntry.id returns based on the session
            // The hex string for [0, 1] is "0001"
            val aliceId = alice.id
            val bobId = bob.id
            val charlieId = charlie.id

            // Inject multiple friends
            (vm.friends as MutableStateFlow).value = listOf(alice, bob, charlie)

            source.onFriendUpdate(UserLocation(alice.id, 1.0, 2.0, 1000L), 1000L)
            source.onFriendUpdate(UserLocation(bob.id, 3.0, 4.0, 2000L), 2000L)
            source.onFriendUpdate(UserLocation(charlie.id, 5.0, 6.0, 3000L), 3000L)

            println("Initial friendLocations: ${vm.friendLocations.value}") // Added logging

            source.setPausedFriends(setOf(aliceId, bobId))

            // Update mock to return two friends after removing bob
            io.mockk.coEvery { store.listFriends() } returns listOf(alice, charlie)

            // Remove bob
            vm.removeFriend(bob.id)
            advanceUntilIdle()

            println("After removing friend, friendLocations: ${vm.friendLocations.value}") // Added logging

            // Verify bob is removed but others remain
            assertEquals(2, vm.friends.value.size, "Should have 2 friends after removing bob")
            assertEquals(2, vm.friendLocations.value.size, "Should have 2 locations after removing bob")
            assertEquals(1, vm.pausedFriendIds.value.size, "Should have 1 paused friend after removing bob")

            assertFalse(bob.id in vm.friends.value.map { it.id }, "Bob should not be in friends")
            assertFalse(bob.id in vm.friendLocations.value.keys, "Bob should not be in locations")
            assertFalse(bob.id in vm.pausedFriendIds.value, "Bob should not be paused")

            assertTrue(aliceId in vm.pausedFriendIds.value, "Alice should still be paused")
            assertTrue(charlieId in vm.friendLocations.value.keys, "Charlie should still be in locations")
        }

}
