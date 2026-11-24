package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import dev.icerock.moko.resources.desc.Raw
import dev.icerock.moko.resources.desc.StringDesc
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InvitePublicKey
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.model.UserLocation
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

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

    private val _foregroundEntries = MutableStateFlow(0)
    override val foregroundEntries: StateFlow<Int> = _foregroundEntries.asStateFlow()

    val timeSource = TestTimeSource()
    private val _appBackgroundedAt = MutableStateFlow<TimeMark?>(null)
    override val appBackgroundedAt: StateFlow<TimeMark?> = _appBackgroundedAt.asStateFlow()

    private val _pendingInitPayload = MutableStateFlow<KeyExchangeInitPayload?>(null)
    override val pendingInitPayload: StateFlow<KeyExchangeInitPayload?> = _pendingInitPayload.asStateFlow()

    private val _pendingInitPayloadSetAt = MutableStateFlow(0L)
    override val pendingInitPayloadSetAt: StateFlow<Long> = _pendingInitPayloadSetAt.asStateFlow()

    private val _pendingInitAliceEkPub = MutableStateFlow<InvitePublicKey?>(null)
    override val pendingInitAliceEkPub: StateFlow<InvitePublicKey?> = _pendingInitAliceEkPub.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val _allPendingInvites = MutableStateFlow<List<net.af0.where.e2ee.PendingInviteView>>(emptyList())
    override val allPendingInvites: StateFlow<List<net.af0.where.e2ee.PendingInviteView>> = _allPendingInvites.asStateFlow()

    private val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger.asStateFlow()

    private val awaitingFirstUpdateIds = mutableSetOf<String>()

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
        if (foreground && !_isAppInForeground.value) _foregroundEntries.value += 1
        if (foreground) {
            _appBackgroundedAt.value = null
        } else if (_isAppInForeground.value) {
            _appBackgroundedAt.value = timeSource.markNow()
        }
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        aliceEkPub: InvitePublicKey?,
    ) {
        _pendingInitPayload.value = payload
        _pendingInitPayloadSetAt.value = if (payload != null) LocationService.clock() else 0L
        _pendingInitAliceEkPub.value = aliceEkPub
    }

    override fun onPendingInvitesUpdated(invites: List<net.af0.where.e2ee.PendingInviteView>) {
        _allPendingInvites.value = invites
    }

    override fun onFriendsUpdated(friends: List<FriendEntry>) {
        _friends.value = friends
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
        // Robolectric's elapsedRealtime doesn't advance with virtual time: the scheduler follows
        // whatever clock a test sets.
        LocationService.elapsedClock = { LocationService.clock() }

        // Ensure userStore is at a known state
        val app = context as TestWhereApplication
        app.userStore.setSharing(true)
        app.userStore.setPausedFriends(emptySet())
        app.userStore.setDisplayName("Test User")

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
        assertEquals(10_000L, PollPolicy.intervalMs(rapid = false, appForeground = true))
    }

    @Test
    fun testPollInterval_Background_Is5min() {
        assertEquals(5 * 60 * 1000L, PollPolicy.intervalMs(rapid = false, appForeground = false))
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
            service.locationSourceOverride = fakeLocationSource
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
            service.e2eeManagerOverride = mockStore
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
            service.locationSourceOverride = fakeLocationSource
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
            service.e2eeManagerOverride = mockStore
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

    @Test
    fun testPendingInitPayloadStopsForcingRapidPollAfterTimeout() =
        runTest {
            // Regression test for #336: an incoming invite the user never confirms or cancels
            // must not pin the client at the 2s rapid interval forever.
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            service.locationSourceOverride = fakeLocationSource
            controller.create()

            try {
                val payload = io.mockk.mockk<KeyExchangeInitPayload>(relaxed = true)
                fakeLocationSource.onPendingInit(payload, aliceEkPub = InvitePublicKey(ByteArray(32)))
                assertTrue(service.isRapidPolling(), "An unconfirmed pending invite should force rapid polling")

                currentTime += FriendPoller.PENDING_INIT_RAPID_TIMEOUT_MS - 1
                assertTrue(
                    service.isRapidPolling(),
                    "Rapid polling should still hold just under the timeout",
                )

                currentTime += 2
                assertFalse(
                    service.isRapidPolling(),
                    "An invite pending past the timeout must stop forcing rapid polling",
                )
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
            service.locationSourceOverride = fakeLocationSource
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            fakeLocationSource.onLocation(1.0, 2.0, null)
            controller.create()

            controller.startCommand(0, 0)
            advanceUntilIdle()

            io.mockk.coVerify { mockClient.sendLocation(any(), any(), any(), stamp = any()) }
        }

    @Test
    fun noLocationIsSentWithoutLocationPermission() =
        runTest {
            // Permission revoked with sharing still on: the ViewModel has sent a stop, so the
            // heartbeat's cached fix must not follow it.
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource
            service.e2eeManagerOverride = io.mockk.mockk(relaxed = true)
            controller.create()
            shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            try {
                service.sendScheduler.onFix(1.0, 2.0)
                service.sendScheduler.requestForced()
                advanceUntilIdle()
                io.mockk.coVerify(exactly = 0) { mockClient.sendLocation(any(), any(), any(), any(), any()) }

                // The refused attempt must not close the send gate: once permission is back, the
                // next fix goes out straight away.
                shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
                service.sendScheduler.onFix(1.0, 2.0)
                advanceUntilIdle()
                io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(any(), any(), any(), any(), any()) }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testSendLocationRetriesOnTransientFailure() =
        runTest {
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            // First call throws, second call succeeds.
            var sendCalls = 0
            io.mockk.coEvery { mockClient.sendLocation(any(), any(), any(), stamp = any()) } answers {
                sendCalls += 1
                if (sendCalls == 1) throw RuntimeException("simulated transient failure")
                Unit
            }
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource
            service.e2eeManagerOverride = io.mockk.mockk(relaxed = true)

            val app = context as TestWhereApplication
            app.userStore.setSharing(true)
            controller.create()

            try {
                service.sendScheduler.onFix(1.0, 2.0)
                advanceUntilIdle()
                assertEquals(2, sendCalls, "Send should retry once after transient failure")
            } finally {
                controller.destroy()
            }
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
            service.locationSourceOverride = fakeLocationSource
            val mockProvider = io.mockk.mockk<LocationProvider>(relaxed = true)
            service.locationProviderOverride = mockProvider

            // Initialize sharing
            val app = context as TestWhereApplication
            app.userStore.setSharing(true)

            controller.create()
            try {
                val mockLocation = mockk<Location>()
                every { mockLocation.latitude } returns 45.0
                every { mockLocation.longitude } returns 90.0
                every { mockLocation.hasBearing() } returns false
                // forceFix logs the fix's accuracy; a strict mock without it makes the call throw
                // and forceLocationUpdateAndGet return null.
                every { mockLocation.accuracy } returns 5f

                io.mockk.coEvery { mockProvider.getCurrentLocation() } returns mockLocation

                val result = service.forceLocationUpdateAndGet()

                assertEquals(mockLocation, result)
                io.mockk.coVerify(exactly = 1) { mockProvider.getCurrentLocation() }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testRapidLocationCallbacks_AreNotSentOrPolledEachTime() =
        runTest {
            // Regression for the October 2026 AWS bill: pollLoop forced a send on every wake, and
            // every location callback (up to 1/s from the passive listener) woke it, so a phone
            // near a navigating app sent ~1/s to every friend. Sends now go through
            // LocationSendScheduler (its rate rules are tested in shared); this checks the
            // service feeds it rather than sending, and rate-limits its own poll wakes.
            val base = 1_000_000_000L
            LocationService.clock = { base + testScheduler.currentTime }
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()
            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource
            // The heartbeat's forced fix finds nothing, so it re-reports the cached location and
            // only the scripted fixes below reach the scheduler.
            val mockProvider = io.mockk.mockk<LocationProvider>(relaxed = true)
            io.mockk.coEvery { mockProvider.getCurrentLocation() } returns null
            io.mockk.coEvery { mockProvider.getLastLocation() } returns null
            service.locationProviderOverride = mockProvider
            val mockE2ee = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
            service.e2eeManagerOverride = mockE2ee
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
            val friend = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
            io.mockk.every { friend.id } returns "friend1"
            io.mockk.coEvery { mockE2ee.listFriends() } returns listOf(friend)
            fakeLocationSource.onFriendsUpdated(listOf(friend))
            fakeLocationSource.onLocation(37.0, -122.0, null)

            controller.create()
            runCurrent()
            for (i in 1..60) {
                advanceTimeBy(1_000L)
                fakeLocationSource.onLocation(37.0 + i * 0.01, -122.0, null) // ~1 km each
                runCurrent()
            }

            // t = 0 and 30 s, plus 60 s for the last fix once its window opens.
            advanceTimeBy(1_000L)
            runCurrent()
            io.mockk.coVerify(exactly = 3) { mockClient.sendLocation(any(), any(), any(), any(), any()) }
            io.mockk.coVerify(exactly = 1) { mockClient.sendLocation(37.0 + 60 * 0.01, -122.0, any(), any(), any()) }
            io.mockk.coVerify(atMost = 4) { mockClient.poll(any(), any(), any()) }
            controller.destroy()
        }

    @Test
    fun testSetGeofenceAt_logsDistinctDiagnosticsForSubmittedQueuedAndFailed() {
        // Regression test: LocationService.setGeofenceAt() branches on GeofenceRequestResult
        // to decide what diagnostic event to log. QUEUED must not be logged as "submitted" -
        // this PR exists specifically to make multi-hour reliability diagnostics trustworthy,
        // so a mislabeled event here would defeat that.
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        val mockProvider = io.mockk.mockk<LocationProvider>(relaxed = true)
        service.locationProviderOverride = mockProvider
        val mockE2ee = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
        service.e2eeManagerOverride = mockE2ee
        controller.create()
        // Startup may plant a fence (and log) itself; count only the calls below.
        io.mockk.clearMocks(mockE2ee, answers = false, recordedCalls = true, verificationMarks = true)

        val setGeofenceAt =
            LocationService::class.java
                .getDeclaredMethod("setGeofenceAt", java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.SUBMITTED
        setGeofenceAt.invoke(service, 1.0, 2.0, true)
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.QUEUED
        setGeofenceAt.invoke(service, 3.0, 4.0, true)
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence queued") }
        // Still exactly 1 - QUEUED must not also log "submitted".
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.FAILED
        setGeofenceAt.invoke(service, 5.0, 6.0, true)
        // FAILED logs neither - counts from the prior two cases must stay unchanged.
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence queued") }
    }

    private fun buildServiceWithFriend(): Pair<org.robolectric.android.controller.ServiceController<LocationService>, LocationClient> {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
        service.locationClientOverride = mockClient
        service.locationSourceOverride = fakeLocationSource
        val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
        service.e2eeManagerOverride = mockStore
        val friend = io.mockk.mockk<FriendEntry>(relaxed = true)
        io.mockk.every { friend.id } returns "friend1"
        io.mockk.coEvery { mockStore.listFriends() } returns listOf(friend)
        io.mockk.coEvery { mockStore.listPendingInvites() } returns emptyList()
        io.mockk.coEvery { mockClient.poll(any(), any(), any()) } returns emptyList()
        io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()
        fakeLocationSource.onFriendsUpdated(listOf(friend))
        return controller to mockClient
    }

    /** #481: with sharing off, no foreground service (and notification) in the background. */
    @Test
    fun notSharingInBackground_stopsItselfWithoutPolling() =
        runTest {
            (context as TestWhereApplication).userStore.setSharing(false)
            val (controller, mockClient) = buildServiceWithFriend()

            controller.create()
            advanceUntilIdle()

            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            // The worker polls in the background; the service doesn't on its way out.
            io.mockk.coVerify(exactly = 0) { mockClient.poll(any(), any(), any()) }
        }

    @Test
    fun notSharing_staysWhileVisible_andStopsOnBackgrounding() =
        runTest {
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            val (controller, mockClient) = buildServiceWithFriend()

            controller.create()
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "visible: keeps polling")
            io.mockk.coVerify(atLeast = 1) { mockClient.poll(any(), any(), any()) }

            // A brief trip out (QR scanner, permission dialog) doesn't stop it, even when the
            // loop wakes during it.
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            fakeLocationSource.setAppForeground(false)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            now += LocationService.BACKGROUND_STOP_GRACE_MS - 1
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "still within the grace period")

            now += 1
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** Delay until the next scheduled AlarmManager alarm, in ms. */
    private fun nextAlarmIn(): Long {
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        return alarms.peekNextScheduledAlarm()!!.triggerAtTime - android.os.SystemClock.elapsedRealtime()
    }

    private fun assertAlarmIn(
        expectedMs: Long,
        message: String,
    ) {
        val actual = nextAlarmIn()
        assertTrue(actual in (expectedMs - 1_000)..expectedMs, "$message: alarm in $actual ms, expected ~$expectedMs")
    }

    private fun fireDozeAlarm(controller: ServiceController<LocationService>) {
        val alarm = Intent(context, LocationService::class.java).apply { action = LocationService.ACTION_POLL_ALARM }
        controller.withIntent(alarm).startCommand(0, 2)
    }

    /**
     * Backgrounding alone (no other wake) starts the grace and arms an alarm for its end, without
     * polling on the way out, so the service stops ~30 s later rather than at the 10-min doze
     * alarm the last foreground poll set.
     */
    @Test
    fun notSharing_backgrounding_stopsWhenTheGraceEnds_notAtTheDozeAlarm() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            val (controller, mockClient) = buildServiceWithFriend()
            controller.create()
            advanceUntilIdle()
            assertTrue(nextAlarmIn() >= LocationService.DOZE_ALARM_INTERVAL_MS, "foreground: 10-min safety-net alarm")
            val polls = io.mockk.mockk<() -> Unit>(relaxed = true)
            io.mockk.coEvery { mockClient.poll(any(), any(), any()) } answers {
                polls()
                emptyList()
            }

            // onPause only: nothing else wakes the loop with the screen off.
            fakeLocationSource.setAppForeground(false)
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "within the grace period")
            assertAlarmIn(LocationService.BACKGROUND_STOP_GRACE_MS, "armed for the end of the grace")

            now += LocationService.BACKGROUND_STOP_GRACE_MS
            fireDozeAlarm(controller)
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            io.mockk.verify(exactly = 0) { polls() }
        }

    /**
     * Every ACTION_POLL_ALARM is a new start command: the idle stop must name the latest one
     * (stopSelfResult), or Android ignores it when a newer command is in flight.
     */
    @Test
    fun idleStop_stopsWithTheLatestStartId() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            val (controller, _) = buildServiceWithFriend()
            controller.create()
            advanceUntilIdle()
            fakeLocationSource.setAppForeground(false)
            advanceUntilIdle()

            now += LocationService.BACKGROUND_STOP_GRACE_MS
            val alarm = Intent(context, LocationService::class.java).apply { action = LocationService.ACTION_POLL_ALARM }
            controller.withIntent(alarm).startCommand(0, 7)
            advanceUntilIdle()

            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertEquals(7, shadowOf(controller.get()).stopSelfResultId)
        }

    /** A visit too short for the loop to see (QR scanner, say) re-arms a full grace. */
    @Test
    fun notSharing_shortVisitInBackground_rearmsAFullGrace() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            val (controller, _) = buildServiceWithFriend()
            controller.create()
            advanceUntilIdle()
            fakeLocationSource.setAppForeground(false)
            advanceUntilIdle()

            now += 20_000
            fakeLocationSource.setAppForeground(true)
            fakeLocationSource.setAppForeground(false)
            advanceUntilIdle()
            assertAlarmIn(LocationService.BACKGROUND_STOP_GRACE_MS, "a fresh grace from the visit")

            now += LocationService.BACKGROUND_STOP_GRACE_MS
            fireDozeAlarm(controller)
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** Backgrounding while pairing keeps the rapid-poll alarm (one PendingIntent for both). */
    @Test
    fun notSharing_backgroundingWhileRapidPolling_keepsTheRapidAlarm() =
        runTest {
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            fakeLocationSource.triggerRapidPoll()
            val (controller, _) = buildServiceWithFriend()
            controller.create()
            runCurrent()
            assertAlarmIn(FriendPoller.RAPID_POLL_INTERVAL_MS, "rapid polling")

            fakeLocationSource.setAppForeground(false)
            runCurrent()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            assertAlarmIn(FriendPoller.RAPID_POLL_INTERVAL_MS, "still the rapid interval")
        }

    /** Sharing turned off while in the background arms the stop for what's left of the grace. */
    @Test
    fun sharingTurnedOffInBackground_stopsWhenTheGraceEnds_notAtTheNextPoll() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            val app = context as TestWhereApplication
            fakeLocationSource.setAppForeground(true)
            val (controller, mockClient) = buildServiceWithFriend()
            controller.create()
            advanceUntilIdle()
            fakeLocationSource.setAppForeground(false)
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "sharing: stays in the background")
            val polls = io.mockk.mockk<() -> Unit>(relaxed = true)
            io.mockk.coEvery { mockClient.poll(any(), any(), any()) } answers {
                polls()
                emptyList()
            }

            now += 10_000
            app.userStore.setSharing(false)
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "rest of the grace")
            assertAlarmIn(LocationService.BACKGROUND_STOP_GRACE_MS - 10_000, "armed for the rest of the grace")

            now += LocationService.BACKGROUND_STOP_GRACE_MS - 10_000
            fireDozeAlarm(controller)
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            io.mockk.verify(exactly = 0) { polls() }
        }

    /**
     * Starts a service with sharing off in the background, its idle stop waiting on a sync. The
     * tests use runCurrent, not advanceUntilIdle, while a sync is pending: advancing virtual time
     * would run the wait into its timeout.
     */
    private fun TestScope.serviceWaitingOnSync(): Triple<ServiceController<LocationService>, LocationClient, CompletableJob> {
        (context as TestWhereApplication).userStore.setSharing(false)
        val (controller, mockClient) = buildServiceWithFriend()
        controller.create()
        // A sync in flight, as a reconnect's would be.
        val sync = kotlinx.coroutines.Job().also { controller.get().registerSync(it) }
        runCurrent()
        assertFalse(shadowOf(controller.get()).isStoppedBySelf, "waiting for the sync")
        return Triple(controller, mockClient, sync)
    }

    @Test
    fun idleStop_stopsAfterTheSync_whenNothingChanged() =
        runTest {
            val (controller, _, sync) = serviceWaitingOnSync()

            sync.complete()
            runCurrent()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** Overlapping reconnects: the idle stop waits for every sync, whichever finishes first. */
    @Test
    fun idleStop_waitsForOverlappingSyncs_finishingOutOfOrder() =
        runTest {
            val (controller, _, first) = serviceWaitingOnSync()
            val second = kotlinx.coroutines.Job().also { controller.get().registerSync(it) }
            val third = kotlinx.coroutines.Job().also { controller.get().registerSync(it) }

            second.complete()
            runCurrent()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "first and third still flushing")
            first.complete()
            runCurrent()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "third still flushing")

            third.complete()
            runCurrent()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** The last friend removed while the idle stop waits for a sync: the wait ends and the service stops. */
    @Test
    fun idleStop_lastFriendRemovedDuringTheSync_stops() =
        runTest {
            val (controller, _, _) = serviceWaitingOnSync()

            fakeLocationSource.onFriendsUpdated(emptyList())
            runCurrent()

            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** A sync that never finishes doesn't hold the loop forever, nor stop the service. */
    @Test
    fun idleStop_stuckSync_givesUpWaitingAndPolls() =
        runTest {
            val (controller, mockClient, _) = serviceWaitingOnSync()
            io.mockk.coVerify(exactly = 0) { mockClient.poll(any(), any(), any()) }

            advanceTimeBy(LocationService.NETWORK_SYNC_WAIT_MS + 1)
            runCurrent()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "not stopped while a sync runs")
            io.mockk.coVerify(exactly = 1) { mockClient.poll(any(), any(), any()) }

            // The grace has run out, but the loop must not spin on a zero interval.
            advanceTimeBy(1_000)
            runCurrent()
            io.mockk.coVerify(exactly = 1) { mockClient.poll(any(), any(), any()) }
        }

    /**
     * C22b: the give-up lasts while the sync hangs. The loop returns to its normal cadence rather
     * than re-waiting 60 s each iteration, and the idle stop happens once the sync ends.
     */
    @Test
    fun idleStop_stuckSync_giveUpPersists_thenStopsWhenTheSyncEnds() =
        runTest {
            val (controller, mockClient, sync) = serviceWaitingOnSync()
            advanceTimeBy(LocationService.NETWORK_SYNC_WAIT_MS + 1)
            runCurrent()
            io.mockk.coVerify(exactly = 1) { mockClient.poll(any(), any(), any()) }

            // Well under the 5 min cadence, several 60 s waits' worth: no further iteration.
            advanceTimeBy(4 * 60_000L)
            runCurrent()
            io.mockk.coVerify(exactly = 1) { mockClient.poll(any(), any(), any()) }
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)

            sync.complete()
            runCurrent()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf, "the stop skipped for the hung sync is due")
        }

    /** C22c: a change made between onCreate and the collector's first run is not swallowed. */
    @Test
    fun sharingTurnedOffRightAfterCreate_stillStopsAfterTheGrace() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            (context as TestWhereApplication).userStore.setSharing(true)
            fakeLocationSource.setAppForeground(false)
            val (controller, _) = buildServiceWithFriend()
            controller.create()
            // Before any collector has run.
            (context as TestWhereApplication).userStore.setSharing(false)
            advanceUntilIdle()

            now += LocationService.BACKGROUND_STOP_GRACE_MS + 1
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** The app coming back while the idle stop waits for a (stuck) sync ends the wait at once. */
    @Test
    fun idleStop_appOpenedDuringTheSync_keepsRunning() =
        runTest {
            val (controller, mockClient, _) = serviceWaitingOnSync()

            // onResume: onStartCommand finds the loop alive and leaves it to carry on.
            fakeLocationSource.setAppForeground(true)
            controller.startCommand(0, 2)
            runCurrent()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "visible again: keeps polling")
            io.mockk.coVerify(atLeast = 1) { mockClient.poll(any(), any(), any()) }
        }

    /** A visit that has already ended by the time the sync finishes still gets a fresh grace period. */
    @Test
    fun idleStop_appOpenedAndClosedDuringTheSync_restartsTheGrace() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            val (controller, _, sync) = serviceWaitingOnSync()

            // The QR scanner, say: in and out again before the sync is done.
            fakeLocationSource.setAppForeground(true)
            fakeLocationSource.setAppForeground(false)
            sync.complete()
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "fresh grace period")

            now += LocationService.BACKGROUND_STOP_GRACE_MS
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** A visit that starts and ends while the loop is busy polling also restarts the grace. */
    @Test
    fun appOpenedAndClosedDuringAPoll_restartsTheGrace() =
        runTest {
            var now = 1_000_000L
            LocationService.elapsedClock = { now }
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.setAppForeground(true)
            val (controller, mockClient) = buildServiceWithFriend()
            controller.create()
            advanceUntilIdle()

            // Backgrounded: the next iteration starts the grace, then blocks in its poll.
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            io.mockk.coEvery { mockClient.poll(any(), any(), any()) } coAnswers {
                gate.await()
                emptyList()
            }
            fakeLocationSource.setAppForeground(false)
            fakeLocationSource.wakePoll()
            runCurrent()

            // Past the grace, with a visit (in and out) while the poll is still running.
            now += LocationService.BACKGROUND_STOP_GRACE_MS
            fakeLocationSource.setAppForeground(true)
            fakeLocationSource.setAppForeground(false)
            gate.complete(Unit)
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "fresh grace after the visit")

            now += LocationService.BACKGROUND_STOP_GRACE_MS
            fakeLocationSource.wakePoll()
            advanceUntilIdle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        }

    /** Sharing turned on while the idle stop waits for a (stuck) sync: keeps running and sends. */
    @Test
    fun idleStop_sharingTurnedOnDuringTheSync_keepsRunningAndPublishes() =
        runTest {
            val (controller, mockClient, _) = serviceWaitingOnSync()

            (context as TestWhereApplication).userStore.setSharing(true)
            fakeLocationSource.onLocation(1.0, 2.0, null)
            controller.withIntent(
                Intent(context, LocationService::class.java).apply { action = LocationService.ACTION_FORCE_PUBLISH },
            ).startCommand(0, 2)
            runCurrent()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "sharing: keeps running")
            io.mockk.coVerify(atLeast = 1) { mockClient.sendLocation(1.0, 2.0, any(), any(), any()) }
        }

    @Test
    fun sharingInBackground_keepsRunning() =
        runTest {
            val (controller, _) = buildServiceWithFriend()

            controller.create()
            advanceUntilIdle()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        }

    @Test
    fun notSharingInBackground_staysWhilePairing() =
        runTest {
            (context as TestWhereApplication).userStore.setSharing(false)
            fakeLocationSource.triggerRapidPoll()
            val (controller, _) = buildServiceWithFriend()

            controller.create()
            runCurrent()

            assertFalse(shadowOf(controller.get()).isStoppedBySelf, "rapid polling for a handshake keeps it up")
        }

    /**
     * Regression for the zombie-service race: pollLoop() calls stopSelf() and returns
     * permanently when there are no friends/invites. stopSelf() is asynchronous — a
     * startForegroundService() call can race it and land before Android actually tears the
     * Service down, in which case onCreate()/onDestroy() never re-run and nothing would
     * otherwise ever relaunch pollLoop. onStartCommand() must detect this and relaunch it.
     */
    @Test
    fun onStartCommand_relaunchesPollLoop_afterItStoppedItselfOnNoFriendsPath() =
        runTest {
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource
            val mockStore = io.mockk.mockk<net.af0.where.e2ee.E2eeManager>(relaxed = true)
            service.e2eeManagerOverride = mockStore
            io.mockk.coEvery { mockStore.listFriends() } returns emptyList()
            io.mockk.coEvery { mockStore.listPendingInvites() } returns emptyList()
            io.mockk.coEvery { mockClient.pollPendingInvites() } returns emptyList()

            val pollLoopJobField =
                LocationService::class.java.getDeclaredField("pollLoopJob").apply { isAccessible = true }

            fun currentJob(): kotlinx.coroutines.Job? = pollLoopJobField.get(service) as kotlinx.coroutines.Job?

            controller.create()
            advanceUntilIdle()

            // No friends/invites: pollLoop() must have called stopSelf() and returned,
            // completing its own coroutine Job.
            assertTrue(shadowOf(service).isStoppedBySelf, "expected stopSelf() on the no-friends path")
            val jobAfterStop = currentJob()
            assertTrue(jobAfterStop != null && jobAfterStop.isCompleted, "pollLoop's Job should have completed")

            // Now a friend gets added — mirroring the real scenario, where
            // LocationViewModel.manageForegroundService reacts to the new friend by calling
            // startForegroundService() again. The friend is added BEFORE that start command so
            // the relaunched loop has real work to do instead of immediately re-hitting the
            // same no-friends stopSelf() path.
            val friend = io.mockk.mockk<net.af0.where.e2ee.FriendEntry>(relaxed = true)
            io.mockk.every { friend.id } returns "friend1"
            fakeLocationSource.onFriendsUpdated(listOf(friend))
            io.mockk.coEvery { mockClient.poll(any()) } returns emptyList()

            // Simulate the race: a startForegroundService() call lands before Android has
            // actually torn the Service down (stopSelf() is async). onDestroy() is deliberately
            // NOT called here — before the fix, nothing would ever relaunch pollLoop in this case.
            controller.startCommand(0, 1)
            advanceUntilIdle()

            val jobAfterRestart = currentJob()
            assertTrue(
                jobAfterRestart != null && jobAfterRestart.isActive && jobAfterRestart !== jobAfterStop,
                "onStartCommand should have relaunched pollLoop after detecting it had stopped",
            )

            // Prove the relaunched loop is actually functional: waking the poll should drive
            // a real poll cycle rather than sitting dead forever.
            fakeLocationSource.wakePoll()
            advanceUntilIdle()

            io.mockk.coVerify(atLeast = 1) { mockClient.poll(any()) }

            controller.destroy()
        }

    /**
     * Regression: pollWakeLock is acquired unconditionally by ACTION_POLL_ALARM/
     * ACTION_HEARTBEAT_TICK/ACTION_GEOFENCE_EVENT in onStartCommand, but was only ever
     * released inside pollLoop() itself. If the Service is torn down while a wake is in
     * flight (or after pollLoop has already stopped), nothing released it, holding a
     * full-power partial wakelock for its full 120s timeout on every subsequent wake.
     */
    @Test
    fun onDestroy_releasesPollWakeLock_ifStillHeld() {
        val controller = Robolectric.buildService(LocationService::class.java)
        val service = controller.get()
        service.locationSourceOverride = fakeLocationSource
        service.e2eeManagerOverride = io.mockk.mockk(relaxed = true)
        service.locationClientOverride = io.mockk.mockk(relaxed = true)

        controller.create()

        val pollWakeLockField =
            LocationService::class.java.getDeclaredField("pollWakeLock").apply { isAccessible = true }

        fun isWakeLockHeld(): Boolean = (pollWakeLockField.get(service) as android.os.PowerManager.WakeLock).isHeld

        // Simulate a wake arriving (e.g. the doze alarm) that acquires the lock but never
        // gets a chance to release it before the Service is destroyed.
        val intent = Intent(service, LocationService::class.java).apply { action = LocationService.ACTION_POLL_ALARM }
        controller.withIntent(intent).startCommand(0, 1)
        assertTrue(isWakeLockHeld(), "expected onStartCommand to acquire pollWakeLock for ACTION_POLL_ALARM")

        controller.destroy()

        assertFalse(isWakeLockHeld(), "onDestroy must release pollWakeLock if still held")
    }
}
