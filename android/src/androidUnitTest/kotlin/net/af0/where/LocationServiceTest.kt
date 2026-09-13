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

    private val _pendingInitPayloadSetAt = MutableStateFlow(0L)
    override val pendingInitPayloadSetAt: StateFlow<Long> = _pendingInitPayloadSetAt.asStateFlow()

    private val _pendingInitAliceEkPub = MutableStateFlow<ByteArray?>(null)
    override val pendingInitAliceEkPub: StateFlow<ByteArray?> = _pendingInitAliceEkPub.asStateFlow()

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
        _isAppInForeground.value = foreground
    }

    override fun onPendingInit(
        payload: KeyExchangeInitPayload?,
        aliceEkPub: ByteArray?,
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
                fakeLocationSource.onPendingInit(payload, aliceEkPub = byteArrayOf(0))
                assertTrue(service.isRapidPolling(), "An unconfirmed pending invite should force rapid polling")

                currentTime += LocationService.PENDING_INIT_RAPID_TIMEOUT_MS - 1
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

            io.mockk.coVerify { mockClient.sendLocation(any(), any(), any()) }
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
            io.mockk.coEvery { mockClient.sendLocation(any(), any(), any()) } answers {
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
                service.sendLocationIfNeeded(1.0, 2.0, isHeartbeat = false, force = true)
                advanceUntilIdle()
                assertEquals(2, sendCalls, "Send should retry once after transient failure")
                assertTrue(service.lastSentTime > 0L, "lastSentTime should remain set after eventual success")
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testDisplacementHelperDetectsMovement() {
        var currentTime = 1_000_000_000L
        LocationService.clock = { currentTime }
        val service = Robolectric.buildService(LocationService::class.java).get()

        val recordRecentFix =
            LocationService::class.java
                .getDeclaredMethod("recordRecentFix", java.lang.Double.TYPE, java.lang.Double.TYPE)
                .apply { isAccessible = true }

        // Stationary: same coordinate twice -> ~0m displacement.
        recordRecentFix.invoke(service, 37.7749, -122.4194)
        currentTime += 30_000L
        recordRecentFix.invoke(service, 37.7749, -122.4194)
        assertTrue(service.maxRecentDisplacementMeters() < 1f, "Stationary fixes should report ~0m displacement")

        // Add a fix ~200m north -> displacement should exceed STILL threshold.
        currentTime += 30_000L
        recordRecentFix.invoke(service, 37.77670, -122.4194)
        assertTrue(
            service.maxRecentDisplacementMeters() > LocationService.STILL_DISPLACEMENT_IGNORE_METERS,
            "Moving ~200m should exceed STILL_DISPLACEMENT_IGNORE_METERS",
        )
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

                io.mockk.coEvery { mockProvider.getCurrentLocation() } returns mockLocation

                val result = service.forceLocationUpdateAndGet()

                assertEquals(mockLocation, result)
                io.mockk.coVerify(exactly = 1) { mockProvider.getCurrentLocation() }
            } finally {
                controller.destroy()
            }
        }

    @Test
    fun testIsStillBackstopDue_FalseWhenNotStill() {
        var currentTime = 1_000_000_000L
        LocationService.clock = { currentTime }
        val service = Robolectric.buildService(LocationService::class.java).get()

        service.isStill = false
        service.lastStillForcedFixTime = 0L
        currentTime += LocationService.STILL_MODE_FORCE_FIX_INTERVAL_MS * 10

        assertFalse(
            service.isStillBackstopDue(currentTime),
            "Backstop must never fire while moving - only the STILL branch skips forcing a fresh fix",
        )
    }

    @Test
    fun testIsStillBackstopDue_FalseBeforeIntervalElapses() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        service.isStill = true
        service.lastStillForcedFixTime = 1_000_000_000L

        assertFalse(
            service.isStillBackstopDue(1_000_000_000L + LocationService.STILL_MODE_FORCE_FIX_INTERVAL_MS - 1_000L),
            "Must preserve STILL-mode battery savings until the full backstop interval has elapsed",
        )
    }

    @Test
    fun testIsStillBackstopDue_TrueAfterIntervalElapses() {
        val service = Robolectric.buildService(LocationService::class.java).get()
        service.isStill = true
        service.lastStillForcedFixTime = 1_000_000_000L

        assertTrue(
            service.isStillBackstopDue(1_000_000_000L + LocationService.STILL_MODE_FORCE_FIX_INTERVAL_MS + 1_000L),
            "Regression test: a dead Activity-Recognition/geofence registration must not freeze " +
                "location forever - a real GPS fix must eventually be forced even while classified STILL",
        )
    }

    @Test
    fun testActivityTransitionToStill_ResetsBackstopCountdown() =
        runTest {
            // Regression test: entering STILL must (re)start the backstop countdown from "now",
            // not leave a stale lastStillForcedFixTime from a previous STILL period (which could
            // make the backstop fire immediately on every re-entry into STILL instead of only
            // after a genuine multi-hour stall).
            org.junit.Assume.assumeTrue(
                "Activity recognition only enabled in full flavor",
                BuildConfig.ACTIVITY_RECOGNITION_ENABLED,
            )
            var currentTime = 1_000_000_000L
            LocationService.clock = { currentTime }

            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource

            var nextEvents: List<ActivityTransitionEvent>? = null
            service.activityHelperOverride =
                object : ActivityHelper {
                    override fun init(context: android.content.Context) {}

                    override fun extractTransitionEvents(intent: Intent) = nextEvents

                    override fun ensureRegistered(
                        hasPermission: Boolean,
                        isSharing: Boolean,
                    ) {}

                    override fun unregister() {}

                    override fun onDestroy() {}
                }
            controller.create()
            fakeLocationSource.onLocation(37.0, -122.0, null)

            assertEquals(0L, service.lastStillForcedFixTime, "must start unset before any STILL transition")

            nextEvents = listOf(ActivityTransitionEvent(ActivityType.STILL, TransitionType.ENTER))
            val intent =
                Intent(service, LocationService::class.java).apply {
                    action = LocationService.ACTION_ACTIVITY_TRANSITION
                }
            controller.withIntent(intent).startCommand(0, 1)
            advanceUntilIdle()

            assertEquals(
                currentTime,
                service.lastStillForcedFixTime,
                "entering STILL must (re)start the backstop countdown at the current time",
            )
        }

    @Test
    fun testActivityTransitionToStill_SendsImmediateStationaryLocation() =
        runTest {
            org.junit.Assume.assumeTrue(
                "Activity recognition only enabled in full flavor",
                BuildConfig.ACTIVITY_RECOGNITION_ENABLED,
            )
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = io.mockk.mockk<LocationClient>(relaxed = true)
            service.locationClientOverride = mockClient
            service.locationSourceOverride = fakeLocationSource

            var nextEvents: List<ActivityTransitionEvent>? = null
            service.activityHelperOverride =
                object : ActivityHelper {
                    override fun init(context: android.content.Context) {}

                    override fun extractTransitionEvents(intent: Intent) = nextEvents

                    override fun ensureRegistered(
                        hasPermission: Boolean,
                        isSharing: Boolean,
                    ) {}

                    override fun unregister() {}

                    override fun onDestroy() {}
                }
            controller.create()
            fakeLocationSource.onLocation(37.0, -122.0, null)

            // Entering STILL must immediately send a stationary-flagged Location — not after
            // any debounce — so a peer can render "here since HH:mm" before this device might
            // go dark. See the "here since" background-triggering investigation.
            nextEvents = listOf(ActivityTransitionEvent(ActivityType.STILL, TransitionType.ENTER))
            val intent =
                Intent(service, LocationService::class.java).apply {
                    action = LocationService.ACTION_ACTIVITY_TRANSITION
                }
            controller.withIntent(intent).startCommand(0, 1)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 1) {
                mockClient.sendLocation(37.0, -122.0, any(), stationary = true)
            }
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

        val setGeofenceAt =
            LocationService::class.java
                .getDeclaredMethod("setGeofenceAt", java.lang.Double.TYPE, java.lang.Double.TYPE)
                .apply { isAccessible = true }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.SUBMITTED
        setGeofenceAt.invoke(service, 1.0, 2.0)
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.QUEUED
        setGeofenceAt.invoke(service, 3.0, 4.0)
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence queued") }
        // Still exactly 1 - QUEUED must not also log "submitted".
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }

        io.mockk.every { mockProvider.setGeofenceAt(any(), any(), any()) } returns GeofenceRequestResult.FAILED
        setGeofenceAt.invoke(service, 5.0, 6.0)
        // FAILED logs neither - counts from the prior two cases must stay unchanged.
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence submitted") }
        io.mockk.verify(exactly = 1) { mockE2ee.addDiagnosticEvent("Stationary: Geofence queued") }
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
