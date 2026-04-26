package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.icerock.moko.resources.desc.Raw
import dev.icerock.moko.resources.desc.StringDesc
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.QrPayload
import net.af0.where.model.UserLocation
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ServiceFakeLocationSource for testing.
 */
class ServiceFakeLocationSource : LocationSource {
    private val _lastLocation = MutableStateFlow<Triple<Double, Double, Double?>?>(null)
    override val lastLocation: StateFlow<Triple<Double, Double, Double?>?> = _lastLocation

    private val _friendLocations = MutableStateFlow<Map<String, UserLocation>>(emptyMap())
    override val friendLocations: StateFlow<Map<String, UserLocation>> = _friendLocations

    private val _friendLastPing = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val friendLastPing: StateFlow<Map<String, Long>> = _friendLastPing

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Ok)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _isAppInForeground = MutableStateFlow(false)
    override val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload.asStateFlow()

    private val _multipleScansDetected = MutableStateFlow(false)
    override val multipleScansDetected: StateFlow<Boolean> = _multipleScansDetected.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(false)
    override val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    private val _pausedFriendIds = MutableStateFlow<Set<String>>(emptySet())
    override val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val _allPendingInvites = MutableStateFlow<List<net.af0.where.e2ee.PendingInvite>>(emptyList())
    override val allPendingInvites: StateFlow<List<net.af0.where.e2ee.PendingInvite>> = _allPendingInvites.asStateFlow()

    private val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger.asStateFlow()

    private val awaitingFirstUpdateIds = mutableSetOf<String>()

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    override val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming.asStateFlow()

    private val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    override fun triggerRapidPoll() {
        _lastRapidPollTrigger.value = LocationService.clock()
        pollWakeSignal.trySend(Unit)
    }

    override fun resetRapidPoll() {
        _lastRapidPollTrigger.value = 0L
        awaitingFirstUpdateIds.clear()
    }

    override fun onLocation(
        lat: Double,
        lng: Double,
        bearing: Double?,
    ) {
        _lastLocation.value = Triple(lat, lng, bearing)
    }

    override fun onFriendUpdate(
        update: UserLocation,
        timestamp: Long,
    ) {
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
        _connectionStatus.value = ConnectionStatus.Error(StringDesc.Raw(e.message ?: "error"))
    }

    override fun setAppForeground(foreground: Boolean) {
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        multipleScans: Boolean,
    ) {
        _pendingInitPayload.value = payload
        _multipleScansDetected.value = multipleScans
    }

    override fun onPendingInvitesUpdated(invites: List<net.af0.where.e2ee.PendingInvite>) {
        _allPendingInvites.value = invites
    }

    override fun setSharingLocation(sharing: Boolean) {
        _isSharingLocation.value = sharing
    }

    override fun setPausedFriends(friendIds: Set<String>) {
        _pausedFriendIds.value = friendIds
    }

    override fun onFriendsUpdated(friends: List<FriendEntry>) {
        _friends.value = friends
    }

    override fun onPendingQrForNaming(qr: QrPayload?) {
        _pendingQrForNaming.value = qr
    }

    override fun confirmQrScan() {
        pollWakeSignal.trySend(Unit)
    }

    override fun setInitialFriendLocations(
        locations: Map<String, UserLocation>,
        pings: Map<String, Long>,
    ) {
        _friendLocations.value = locations
        _friendLastPing.value = pings
    }

    override fun markAwaitingFirstUpdate(friendId: String) {
        awaitingFirstUpdateIds.add(friendId)
    }

    override fun onFriendLocationReceived(friendId: String) {
        if (awaitingFirstUpdateIds.remove(friendId)) {
            if (awaitingFirstUpdateIds.isEmpty()) {
                resetRapidPoll()
            }
        }
    }

    override fun wakePoll() {
        pollWakeSignal.trySend(Unit)
    }

    override suspend fun awaitPollWake(timeoutMillis: Long) {
        // In tests, we don't want the background loop to spin automatically.
        // We only want it to wake if we explicitly signal it via wakePoll().
        pollWakeSignal.receive()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class LocationServiceTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        ShadowLog.stream = System.out
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        fakeLocationSource = ServiceFakeLocationSource()
        LocationService.clock = { System.currentTimeMillis() }
        LocationService.locationSource = fakeLocationSource

        // Mock KtorMailboxClient to prevent network calls during pollPendingInvite
        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    private fun getServiceIsRegistered(service: LocationService): Boolean {
        val field = LocationService::class.java.getDeclaredField("isRegistered")
        field.isAccessible = true
        return field.get(service) as Boolean
    }

    @Test
    fun testPollInterval_Foreground_Is10s() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(10_000L, service.pollInterval(rapid = false, inForeground = true))
    }

    @Test
    fun testPollInterval_Background_Is5min() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(5 * 60 * 1000L, service.pollInterval(rapid = false, inForeground = false, isSharingLocation = true))
    }

    @Test
    fun testPollInterval_BackgroundNotSharing_Is30min() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        assertEquals(30 * 60 * 1000L, service.pollInterval(rapid = false, inForeground = false, isSharingLocation = false))
    }

    @Test
    fun testPollInterval_Maintains30minDuringPause() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        // When sharing is paused (isSharingLocation = false), we must use the 30-min interval.
        assertEquals(
            30 * 60 * 1000L,
            service.pollInterval(rapid = false, inForeground = false, isSharingLocation = false),
            "Maintenance interval must be 30 minutes when sharing is paused",
        )
    }

    @Test
    fun testRapidPollResetAfterFirstLocationUpdate() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
            service.e2eeStoreOverride = mockStore
            controller.create()

            try {
                // 1. Trigger rapid poll for a new friend (mirrors LocationViewModel behaviour)
                val newFriendId = "new_friend"
                val mockFriend = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
                io.mockk.every { mockFriend.id } returns newFriendId
                io.mockk.coEvery { mockStore.listFriends() } returns listOf(mockFriend)
                io.mockk.coEvery { mockStore.listPendingInvites() } returns emptyList()
                fakeLocationSource.markAwaitingFirstUpdate(newFriendId)
                fakeLocationSource.triggerRapidPoll()
                assertTrue(service.isRapidPolling())

                // 2. Mock a location update from that new friend
                val update = net.af0.where.model.UserLocation(newFriendId, 1.0, 2.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll(any()) } returns listOf(update)

                // 3. Fire poll
                service.doPoll()
                advanceUntilIdle()

                // 4. Verify rapid poll is reset
                assertFalse(service.isRapidPolling(), "Rapid poll should be reset after first location update from a new friend")
                assertEquals(0L, fakeLocationSource.lastRapidPollTrigger.value)
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testRapidPollResetOnlyAfterUpdatesFromAllNewlyAddedFriends() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeStore>(relaxed = true)
            service.e2eeStoreOverride = mockStore
            controller.create()

            try {
                // 1. Add two new friends
                val friendId1 = "friend1"
                val friendId2 = "friend2"
                val mockFriend1 = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
                val mockFriend2 = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
                io.mockk.every { mockFriend1.id } returns friendId1
                io.mockk.every { mockFriend2.id } returns friendId2
                io.mockk.coEvery { mockStore.listFriends() } returns listOf(mockFriend1, mockFriend2)
                io.mockk.coEvery { mockStore.listPendingInvites() } returns emptyList()
                fakeLocationSource.markAwaitingFirstUpdate(friendId1)
                fakeLocationSource.markAwaitingFirstUpdate(friendId2)

                // 2. Trigger rapid poll
                fakeLocationSource.triggerRapidPoll()
                assertTrue(service.isRapidPolling())

                // 3. Mock a location update from ONLY one friend
                val update1 = net.af0.where.model.UserLocation(friendId1, 1.0, 2.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll(any()) } returns listOf(update1)

                // 4. Fire poll
                service.doPoll()
                advanceUntilIdle()

                // 5. Verify rapid poll is NOT reset yet
                assertTrue(service.isRapidPolling(), "Rapid poll should NOT be reset until all new friends have sent an update")
                assertTrue(fakeLocationSource.lastRapidPollTrigger.value > 0L)

                // 6. Mock a location update from the second friend
                val update2 = net.af0.where.model.UserLocation(friendId2, 3.0, 4.0, currentTime / 1000L)
                io.mockk.coEvery { mockClient.poll(any()) } returns listOf(update2)

                // 7. Fire poll again
                service.doPoll()
                advanceUntilIdle()

                // 8. Verify rapid poll IS reset now
                assertFalse(service.isRapidPolling(), "Rapid poll should be reset after all new friends have sent updates")
                assertEquals(0L, fakeLocationSource.lastRapidPollTrigger.value)
            } finally {
                controller.destroy()
            }
        }

    @Ignore("Known unstable failure in CI/Robolectric environment")
    @Test
    fun testActionForcePublish() =
        runTest {
            val intent =
                Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_FORCE_PUBLISH
                }
            val controller = Robolectric.buildService(LocationService::class.java, intent)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            fakeLocationSource.setSharingLocation(true)
            fakeLocationSource.onLocation(1.0, 2.0, null)
            controller.create()

            controller.startCommand(0, 0)
            advanceUntilIdle()

            io.mockk.coVerify { mockClient.sendLocation(any(), any(), any()) }
        }

    @Test
    fun testForceLocationUpdate_Throttle() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            val mockFused = io.mockk.mockk<com.google.android.gms.location.FusedLocationProviderClient>(relaxed = true)
            service.fusedClientOverride = mockFused

            // Initialize sharing
            fakeLocationSource.setSharingLocation(true)

            controller.create()
            try {
                // 1. Threshold not exceeded.
                // We simulate one poll cycle.
                service.lastSentTime = currentTime - 60_000L // 1 minute ago
                service.pollInterval(false, false, true) // Just to trigger some logic if needed

                // Let's test forceLocationUpdate directly since it's the core of the fix.
                val method = LocationService::class.java.getDeclaredMethod("forceLocationUpdate")
                method.isAccessible = true
                method.invoke(service)

                io.mockk.verify(exactly = 1) {
                    mockFused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                }
            } finally {
                controller.destroy()
            }
        }
}
