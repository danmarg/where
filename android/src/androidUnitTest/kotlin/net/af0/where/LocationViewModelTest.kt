package net.af0.where

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.text.TextUtils
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.icerock.moko.resources.desc.Raw
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.db.WhereDatabase
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InviteState
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.KtorMailboxClient
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PROTOCOL_VERSION
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.RawKeyValueStorage
import net.af0.where.e2ee.SessionState
import net.af0.where.e2ee.UserStore
import net.af0.where.e2ee.currentTimeSeconds
import net.af0.where.e2ee.toHex
import net.af0.where.model.UserLocation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun createTestSqlDriver(name: String? = null): SqlDriver {
    return AndroidSqliteDriver(
        WhereDatabase.Schema,
        ApplicationProvider.getApplicationContext(),
        name,
    )
}

/**
 * TestFakeLocationSource for testing.
 */
class TestFakeLocationSource : LocationSource {
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

    private val pollWakeSignal = Channel<Unit>(Channel.CONFLATED)

    override fun triggerRapidPoll() {
        _lastRapidPollTrigger.value = System.currentTimeMillis()
    }

    override fun resetRapidPoll() {
        _lastRapidPollTrigger.value = 0L
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
        _pendingInitPayloadSetAt.value = if (payload != null) System.currentTimeMillis() else 0L
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

    override fun wakePoll() {
        pollWakeSignal.trySend(Unit)
    }

    override suspend fun awaitPollWake(timeoutMillis: Long) {
        // In tests, we don't want the background loop to spin automatically.
        // We only want it to wake if we explicitly signal it via wakePoll().
        pollWakeSignal.receive()
    }

    override fun markAwaitingFirstUpdate(friendId: String) {
        // No-op
    }

    override fun onFriendLocationReceived(friendId: String) {
        // No-op
    }
}

/**
 * FakeRawKeyValueStorage for testing.
 */
private class FakeRawKeyValueStorage : RawKeyValueStorage {
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
@Config(sdk = [33], manifest = Config.NONE, application = TestWhereApplication::class)
class LocationViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext<Application>()
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private var viewModel: LocationViewModel? = null

    @Before
    fun setup() {
        net.af0.where.initializeLibsodium()
        Dispatchers.setMain(testDispatcher)
        LocationService.clock = { 1_000_000L }

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0

        mockkStatic(TextUtils::class)
        every { TextUtils.equals(any(), any()) } answers {
            val a = it.invocation.args[0] as CharSequence?
            val b = it.invocation.args[1] as CharSequence?
            a == b
        }

        // Mock objects that make network calls
        io.mockk.mockkObject(KtorMailboxClient)
        io.mockk.coEvery { KtorMailboxClient.poll(any(), any()) } returns emptyList()
        io.mockk.coEvery { KtorMailboxClient.post(any(), any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testInviteLifecycle_AliceSide() =
        runTest {
            val fakeLocationSource = TestFakeLocationSource()
            val store = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher())
            val client = LocationClient("http://localhost", store)
            // Disable automatic polling loop to prevent hangs
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = fakeLocationSource,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            // 1. Create invite
            vm.createInvite()
            advanceUntilIdle()
            assertTrue(vm.inviteState.value is InviteState.Pending)
            val qr = store.listPendingInvites().first().qrPayload
            assertNotNull(qr)

            // 2. Simulate finding an init payload via polling
            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // Bob's response arriving at the repository
            fakeLocationSource.onPendingInit(initPayload, aliceEkPub = qr.ekPub)
            advanceUntilIdle()

            // After peer joins, inviteState transitions to None to dismiss QR sheet
            assertTrue(vm.inviteState.value is InviteState.None)
            assertEquals("Bob", vm.pendingInitPayload.value?.suggestedName)

            // Alice cancels naming Bob
            vm.cancelPendingInit()
            advanceUntilIdle()

            assertNull(vm.pendingInitPayload.value)
            assertTrue(vm.inviteState.value is InviteState.None)
            assertTrue(store.listPendingInvites().isEmpty(), "Store should be cleared when Alice cancels")
        }

    @Test
    fun testCancelQrScan_BobSide() =
        runTest {
            val source = TestFakeLocationSource()
            val uiStore = FakeUiStateStore()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = uiStore,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            // Bob scans
            uiStore.onPendingQrForNaming(qr)

            assertEquals(qr, vm.pendingQrForNaming.value)

            // Bob cancels
            vm.cancelQrScan()
            assertNull(vm.pendingQrForNaming.value)
        }

    @Test
    fun testPairingFlow_ConfirmPendingInit() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // 1. Peer joins Alice's mailbox
            source.onPendingInit(initPayload, aliceEkPub = byteArrayOf(0))
            advanceUntilIdle()

            assertEquals(initPayload, vm.pendingInitPayload.value)

            // 2. Alice confirms Bob's name
            vm.confirmPendingInit("Bob (Friend)")
            advanceUntilIdle()

            io.mockk.coVerify { store.processKeyExchangeInit(initPayload, "Bob (Friend)", any()) }
            assertNull(vm.pendingInitPayload.value)
        }

    @Test
    fun testCancelPendingInit_SurgicalRemoval() =
        runTest {
            val store = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher())
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            // 1. Create two invites
            vm.createInvite()
            advanceUntilIdle()
            val qr1 = store.listPendingInvites().first().qrPayload

            vm.createInvite()
            advanceUntilIdle()
            val qr2 = store.listPendingInvites().last().qrPayload

            assertEquals(2, store.listPendingInvites().size)

            // 2. Simulate Bob responding to the FIRST invite
            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )
            source.onPendingInit(initPayload, aliceEkPub = qr1.ekPub)
            advanceUntilIdle()

            assertEquals(initPayload, vm.pendingInitPayload.value)

            // 3. Alice cancels naming Bob
            vm.cancelPendingInit()
            advanceUntilIdle()

            // 4. Verify ONLY the first invite was removed
            val remaining = store.listPendingInvites()
            assertEquals(1, remaining.size, "Only one invite should remain")
            assertContentEquals(qr2.ekPub, remaining[0].qrPayload.ekPub, "The second invite should be the one remaining")
            assertNull(vm.pendingInitPayload.value)
        }

    @Test
    fun testPairingFlow_CancelPendingInit() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // 1. Peer joins Alice's mailbox
            source.onPendingInit(initPayload, aliceEkPub = byteArrayOf(0))
            advanceUntilIdle()

            assertEquals(initPayload, vm.pendingInitPayload.value)

            // 2. Alice cancels
            vm.cancelPendingInit()
            advanceUntilIdle()

            io.mockk.coVerify { store.clearInvite(any<ByteArray>()) }
            assertNull(vm.pendingInitPayload.value)
        }

    @Test
    fun testPairingFlow_BobScanningAlice() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            val uiStore = FakeUiStateStore()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = uiStore,
                )
            val vm = viewModel!!

            // 1. Simulate Bob scanning Alice's QR code
            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            val mockPayload = mockk<KeyExchangeInitPayload>(relaxed = true)
            val mockFriend = mockk<FriendEntry>(relaxed = true)
            val mockSession = mockk<SessionState>(relaxed = true)
            every { mockFriend.session } returns mockSession
            every { mockSession.sendToken } returns byteArrayOf(1, 2, 3)
            every { mockSession.recvToken } returns byteArrayOf(4, 5, 6)
            every { mockFriend.id } returns "alice_fp"
            io.mockk.coEvery { store.processScannedQr(any(), any()) } returns (mockPayload to mockFriend)

            uiStore.onPendingQrForNaming(qr)
            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)

            // 2. Bob confirms Alice's name
            vm.confirmQrScan(qr, "Alice (My Friend)")
            advanceUntilIdle()

            io.mockk.coVerify(timeout = 5000) { store.processScannedQr(any(), any()) }
            io.mockk.coVerify(timeout = 5000) { client.postKeyExchangeInit(any(), any(), any()) }
        }

    @Test
    fun testPairingFlow_CancelQrScan() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            val uiStore = FakeUiStateStore()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = uiStore,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            uiStore.onPendingQrForNaming(qr)

            // Bob cancels naming Alice
            vm.cancelQrScan()
            advanceUntilIdle()

            assertNull(vm.pendingQrForNaming.value)
            io.mockk.coVerify(exactly = 0) { store.processScannedQr(any(), any()) }
        }

    // Regression test for the pause-desync bug: when sharing is toggled off and the app
    // goes to the background, the LocationService must NOT be stopped. Stopping it killed
    // the maintenance poll loop that sends DH-ratchet keepalives, causing token drift.
    @Test
    fun testServiceNotStoppedWhenSharingPaused() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            val inMemory = FakeRawKeyValueStorage()
            val userStore = net.af0.where.e2ee.UserStore(inMemory)
            userStore.setSharing(true)

            val source = TestFakeLocationSource()
            source.setAppForeground(false) // app is in background
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                    userStoreParam = userStore,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            advanceUntilIdle()

            // Drain any startForegroundService calls from the initial "sharing=true" state.
            while (shadowOf(app).peekNextStartedService() != null) {
                shadowOf(app).nextStartedService
            }

            // Pause sharing — app remains in background.
            viewModel!!.toggleSharing()
            advanceUntilIdle()

            // No further service interactions should have occurred: no start and no stop.
            val nextStarted: Intent? = shadowOf(app).peekNextStartedService()
            assertNull(nextStarted, "No startForegroundService should be issued while paused + backgrounded")

            // Also verify the intent queue of stopped services is empty. Robolectric records
            // stopService() calls via ShadowContextImpl; none should appear here.
            val stopped: Intent? = shadowOf(app).nextStoppedService
            assertNull(stopped, "stopService must not be called when sharing is merely paused")
        }

    @Test
    fun testConfirmQrScan_TriggersRapidPollAndForcedLocationUpdate() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            // Mock store to return a friend
            val mockPayload = mockk<KeyExchangeInitPayload>(relaxed = true)
            val mockFriend = mockk<FriendEntry>(relaxed = true)
            val mockSession = mockk<SessionState>(relaxed = true)
            every { mockFriend.session } returns mockSession
            every { mockSession.sendToken } returns byteArrayOf(1, 2, 3)
            every { mockSession.recvToken } returns byteArrayOf(4, 5, 6)
            every { mockFriend.id } returns "friend1"
            io.mockk.coEvery { store.processScannedQr(any(), any()) } returns (mockPayload to mockFriend)

            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            // 1. Should trigger rapid poll
            assertTrue(source.lastRapidPollTrigger.value > 0L)

            // 2. Should attempt a pollAll immediately after pairing
            io.mockk.coVerify(timeout = 5000) { client.postKeyExchangeInit(any(), any(), any()) }
        }

    @Test
    fun testUiStateSynchronization_DismissalFlows() =
        runTest {
            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            val uiStateStore = FakeUiStateStore()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = uiStateStore,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            // Test 1: Scanning a QR code dismisses the invite sheet
            uiStateStore.setInviteSheetShowing(true)
            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)

            vm.processQrUrl(qr.toUrl())
            advanceUntilIdle()

            assertFalse(uiStateStore.isInviteSheetShowing.value, "processQrUrl should dismiss the invite sheet")
            assertEquals(InviteState.None, vm.inviteState.value, "processQrUrl should reset inviteState")

            // Test 2: Confirming a QR scan dismisses the invite sheet
            uiStateStore.setInviteSheetShowing(true)
            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)

            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            assertFalse(uiStateStore.isInviteSheetShowing.value, "confirmQrScan should dismiss the invite sheet")
            assertEquals(InviteState.None, vm.inviteState.value, "confirmQrScan should reset inviteState")

            // Test 3: Canceling a QR scan dismisses the invite sheet
            uiStateStore.setInviteSheetShowing(true)
            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)

            vm.dismissInvite()
            advanceUntilIdle()

            assertFalse(uiStateStore.isInviteSheetShowing.value, "dismissInvite should dismiss the invite sheet")
            assertEquals(InviteState.None, vm.inviteState.value, "dismissInvite should reset inviteState")
        }

    /**
     * Regression test for bug 2: confirmPendingInit() must fire ACTION_FORCE_PUBLISH so that
     * Alice proactively sends her location to Bob immediately after the handshake.
     *
     * Without the force-publish, Bob (the scanner) stays isConfirmed=false for up to 5 minutes
     * waiting for Alice's regular heartbeat — displayed as "pending" with no location updates.
     */
    @Test
    fun testConfirmPendingInit_ForcePublishesLocation() =
        runTest {
            // Drain any pending intents from prior tests.
            while (shadowOf(app).peekNextStartedService() != null) {
                shadowOf(app).nextStartedService
            }

            // Enable sharing so the force-publish branch executes.
            (app as TestWhereApplication).userStore.setSharing(true)

            val store = mockk<E2eeManager>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            val mockFriend = mockk<FriendEntry>(relaxed = true)
            every { mockFriend.id } returns "friend-alice-123"
            io.mockk.coEvery { store.processKeyExchangeInit(any(), any(), any()) } returns mockFriend
            io.mockk.coEvery { store.listFriends() } returns listOf(mockFriend)
            io.mockk.coEvery { store.listPendingInvites() } returns emptyList()

            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Alice",
                )
            source.onPendingInit(initPayload, aliceEkPub = byteArrayOf(0))
            advanceUntilIdle()

            vm.confirmPendingInit("Alice")
            advanceUntilIdle()

            // The ViewModel must fire ACTION_FORCE_PUBLISH so Alice's location reaches Bob
            // without waiting for the next 5-minute heartbeat.
            val startedIntent: Intent? = shadowOf(app).nextStartedService
            assertNotNull(startedIntent, "confirmPendingInit must fire a service intent to publish location")
            assertEquals(
                LocationService.ACTION_FORCE_PUBLISH,
                startedIntent!!.action,
                "The intent must carry ACTION_FORCE_PUBLISH",
            )
            assertEquals(
                "friend-alice-123",
                startedIntent.getStringExtra(LocationService.EXTRA_FRIEND_ID),
                "The intent must target the newly confirmed friend ID",
            )
        }

    /**
     * Pausing a friend gives the peer the same positive "stopped" signal that master-off
     * and timer-expiry produce, so they don't see indefinite silent staleness. Un-pause
     * is silent on the wire — the next outgoing Location is itself the "I'm back" signal.
     */
    @Test
    fun testTogglePauseFriend_sendsStoppedSharingOnPauseOnly() =
        runTest {
            val mockClient = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                    locationClientParam = mockClient,
                    userStoreParam = net.af0.where.e2ee.UserStore(FakeRawKeyValueStorage()),
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )
            val vm = viewModel!!

            // Pause Bob: should enqueue one StoppedSharing for him.
            vm.togglePauseFriend("bob")
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { mockClient.sendStoppedSharingToFriend("bob") }

            // Un-pause Bob: must NOT enqueue. Still exactly 1 from the pause above.
            vm.togglePauseFriend("bob")
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { mockClient.sendStoppedSharingToFriend("bob") }
        }

    /**
     * If the app was killed with a per-friend timer set to expire in the past, the watcher
     * coroutine must fire the expiry on the next launch: that friend ends up in
     * pausedFriendIds and a single StoppedSharing is enqueued for them. This is the
     * remainingSec <= 0 branch in fireFriendExpiry's collectLatest.
     */
    @Test
    fun testFireFriendExpiry_appliesElapsedTimerOnLaunch() =
        runTest {
            val storage = FakeRawKeyValueStorage()
            val userStore = net.af0.where.e2ee.UserStore(storage)
            // Pre-seed: a per-friend timer that already elapsed before this launch.
            val friendId = "friend-bob"
            userStore.setFriendExpiry(friendId, epochSeconds = 500L) // way in the past

            val mockClient = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()

            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                    locationClientParam = mockClient,
                    userStoreParam = userStore,
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = FakeUiStateStore(),
                )

            advanceUntilIdle()

            // The watcher should have run fireFriendExpiry, clearing the timer and pausing.
            assertFalse(friendId in userStore.friendExpiresAt.value, "elapsed timer must be cleared")
            assertTrue(friendId in userStore.pausedFriendIds.value, "expired friend must be paused")
            io.mockk.coVerify(exactly = 1) { mockClient.sendStoppedSharingToFriend(friendId) }
        }

    /**
     * Regression for #405: an invite shown in person is never "exported", and the scan can land
     * after the sheet is closed (e.g. to scan the other person's QR). Dismissing must keep the
     * invite and its key, or that scan is dropped and the scanner stays "Pending" forever.
     */
    @Test
    fun dismissInvite_keepsUnexportedInviteInStore() =
        runTest {
            val store = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher())
            val uiStateStore = FakeUiStateStore()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    startPolling = false,
                    locationSourceParam = TestFakeLocationSource(),
                    uiStateStoreParam = uiStateStore,
                )
            viewModel!!.createInvite()
            advanceUntilIdle()
            val pending = viewModel!!.inviteState.value as InviteState.Pending
            uiStateStore.setInviteSheetShowing(true)

            viewModel!!.dismissInvite()
            advanceUntilIdle()

            assertEquals(InviteState.None, viewModel!!.inviteState.value)
            assertFalse(uiStateStore.isInviteSheetShowing.value)
            assertTrue(
                store.listPendingInvites().any { it.qrPayload.ekPub.contentEquals(pending.qr.ekPub) },
                "dismissing the sheet must not delete an unexported invite",
            )
        }

    @Test
    fun testCreateInvite_StartsServiceWhenNoPriorFriends() =
        runTest {
            // Regression: when there are no existing friends the LocationService stops itself
            // on startup. createInvite() must start the service so it polls the discovery mailbox.
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

            viewModel =
                LocationViewModel(
                    app,
                    e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                    startPolling = false,
                    locationSourceParam = TestFakeLocationSource(),
                    uiStateStoreParam = FakeUiStateStore(),
                )

            // Drain any service starts from ViewModel init.
            while (shadowOf(app).peekNextStartedService() != null) {
                shadowOf(app).nextStartedService
            }

            viewModel!!.createInvite()
            advanceUntilIdle()

            assertTrue(viewModel!!.inviteState.value is InviteState.Pending)
            val started: Intent? = shadowOf(app).nextStartedService
            assertNotNull(started, "createInvite must start LocationService so discovery mailbox is polled")
            assertEquals(LocationService::class.java.name, started.component?.className)
        }

    /** A ViewModel whose store holds one pending invite (a relationship worth polling for) unless [withInvite] is false. */
    private suspend fun foregroundPollVm(
        source: TestFakeLocationSource,
        client: LocationClient,
        withInvite: Boolean = true,
    ): LocationViewModel {
        val store = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher())
        if (withInvite) store.createInvite("Me")
        return LocationViewModel(
            app,
            e2eeManagerParam = store,
            locationClientParam = client,
            startPolling = false,
            locationSourceParam = source,
            uiStateStoreParam = FakeUiStateStore(),
        )
    }

    private fun mockPollingClient(): LocationClient =
        mockk<LocationClient>(relaxed = true).also {
            io.mockk.coEvery { it.poll(any(), any(), any()) } returns emptyList()
            io.mockk.coEvery { it.pollPendingInvites() } returns emptyList()
        }

    @Test
    @Config(sdk = [34])
    fun foregroundWithoutLocationPermission_pollsFromViewModel() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(atLeast = 1) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun foregroundPolling_continuesEachWake() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()
            source.wakePoll()
            advanceUntilIdle()

            io.mockk.coVerify(atLeast = 2) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun foregroundWithLocationPermission_leavesPollingToService() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun backgrounding_stopsForegroundPolling() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()
            source.setAppForeground(false)
            advanceUntilIdle()
            io.mockk.clearMocks(client, answers = false)

            source.wakePoll()
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun neverForegrounded_doesNotPoll() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun foregroundPolling_stopsWhenPermissionGranted() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            io.mockk.clearMocks(client, answers = false)

            source.wakePoll() // loop re-checks permission, exits, and leaves polling to the service
            advanceUntilIdle()
            source.wakePoll()
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun foregroundWithoutRelationships_doesNotPoll() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client, withInvite = false)

            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [33])
    fun foregroundBeforeApi34_serviceRunsSoViewModelDoesNotPoll() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)

            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.poll(any(), any(), any()) }
        }

    @Test
    @Config(sdk = [34])
    fun permissionGrantedWhileForegrounded_startsServiceAndStopsViewModelPolling() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            viewModel = foregroundPollVm(source, client)
            source.setAppForeground(true)
            advanceUntilIdle()
            while (shadowOf(app).nextStartedService != null) { /* drain services started so far */ }

            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            source.wakePoll()
            advanceUntilIdle()

            val started = shadowOf(app).nextStartedService
            assertNotNull(started, "granting permission while foregrounded must hand polling to LocationService")
            assertEquals(LocationService::class.java.name, started.component?.className)
        }

    private fun newVm(source: TestFakeLocationSource): LocationViewModel =
        LocationViewModel(
            app,
            e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
            locationClientParam = mockk<LocationClient>(relaxed = true),
            startPolling = false,
            locationSourceParam = source,
            uiStateStoreParam = FakeUiStateStore(),
        )

    @Test
    fun processQrUrl_unparseableUrl_returnsFalseAndSurfacesInvalidQrError() =
        runTest {
            val source = TestFakeLocationSource()
            val vm = newVm(source)

            assertFalse(vm.processQrUrl("not a where invite"))

            val status = source.connectionStatus.value
            assertTrue(status is ConnectionStatus.Error, "bad QR must surface an error, was $status")
            assertEquals(
                StringDesc.Resource(net.af0.where.shared.MR.strings.invalid_qr_code),
                (status as ConnectionStatus.Error).message,
            )
        }

    @Test
    fun processQrUrl_expiredInvite_returnsFalseAndSurfacesExpiredError() =
        runTest {
            val source = TestFakeLocationSource()
            val vm = newVm(source)
            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = ByteArray(32) { 1 },
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                    expiresAt = currentTimeSeconds() - 3600,
                )

            assertFalse(vm.processQrUrl(qr.toUrl()))

            val status = source.connectionStatus.value
            assertTrue(status is ConnectionStatus.Error, "expired invite must surface an error, was $status")
            assertEquals(
                StringDesc.Resource(net.af0.where.shared.MR.strings.invite_expired),
                (status as ConnectionStatus.Error).message,
            )
        }

    @Test
    fun processQrUrl_unexpiredInvite_isAccepted() =
        runTest {
            val source = TestFakeLocationSource()
            val vm = newVm(source)
            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = ByteArray(32) { 1 },
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                    expiresAt = currentTimeSeconds() + 3600,
                )

            assertTrue(vm.processQrUrl(qr.toUrl()))
            assertEquals(ConnectionStatus.Ok, source.connectionStatus.value)
        }

    @Test
    fun createInvite_whileShowingOne_replacesItInsteadOfAccumulating() =
        runTest {
            val store = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher())
            val vm =
                LocationViewModel(
                    app,
                    e2eeManagerParam = store,
                    startPolling = false,
                    locationSourceParam = TestFakeLocationSource(),
                    uiStateStoreParam = FakeUiStateStore(),
                )
            vm.createInvite()
            advanceUntilIdle()
            val first = (vm.inviteState.value as InviteState.Pending).qr

            // e.g. the user edits their name in the invite sheet.
            vm.createInvite()
            advanceUntilIdle()
            val second = (vm.inviteState.value as InviteState.Pending).qr

            assertFalse(first.ekPub.contentEquals(second.ekPub))
            val stored = store.listPendingInvites().map { it.qrPayload.ekPub.toHex() }
            assertEquals(listOf(second.ekPub.toHex()), stored)
        }

    @Test
    fun processQrUrl_validUrl_doesNotSetError() =
        runTest {
            val source = TestFakeLocationSource()
            val vm = newVm(source)
            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = ByteArray(32) { 1 },
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            assertTrue(vm.processQrUrl(qr.toUrl()))
            assertEquals(ConnectionStatus.Ok, source.connectionStatus.value)
        }

    @Test
    fun confirmQrScan_processScannedQrFails_surfacesErrorAndClearsExchanging() =
        runTest {
            val source = TestFakeLocationSource()
            val vm = newVm(source)
            val badVersionQr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION + 100,
                    ekPub = ByteArray(32) { 1 },
                    suggestedName = "Alice",
                    discoverySecret = ByteArray(32),
                )

            vm.confirmQrScan(badVersionQr, "Alice")
            advanceUntilIdle()

            assertTrue(
                source.connectionStatus.value is ConnectionStatus.Error,
                "a failed scan must not be silent",
            )
            assertFalse(vm.isExchanging.value, "isExchanging must be cleared after failure")
        }

    private fun sharingVm(
        source: TestFakeLocationSource,
        client: LocationClient,
    ): Pair<LocationViewModel, UserStore> {
        val userStore = UserStore(FakeRawKeyValueStorage())
        val vm =
            LocationViewModel(
                app,
                e2eeManagerParam = E2eeManager(createTestSqlDriver(), UnconfinedTestDispatcher()),
                userStoreParam = userStore,
                locationClientParam = client,
                startPolling = false,
                locationSourceParam = source,
                uiStateStoreParam = FakeUiStateStore(),
            )
        userStore.setSharing(false) // UserStore defaults to on; tests opt in explicitly
        return vm to userStore
    }

    @Test
    fun setSharing_withoutPermission_isIgnored() =
        runTest {
            val (vm, userStore) = sharingVm(TestFakeLocationSource(), mockPollingClient())
            viewModel = vm

            vm.setSharing(true)
            advanceUntilIdle()

            assertFalse(userStore.isSharingLocation.value, "sharing must not be enabled without location permission")
        }

    @Test
    fun setSharing_withPermission_enablesSharing() =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            val (vm, userStore) = sharingVm(TestFakeLocationSource(), mockPollingClient())
            viewModel = vm

            vm.setSharing(true)
            advanceUntilIdle()

            assertTrue(userStore.isSharingLocation.value)
        }

    @Test
    fun setSharing_off_withoutPermission_stillAllowed() =
        runTest {
            val (vm, userStore) = sharingVm(TestFakeLocationSource(), mockPollingClient())
            viewModel = vm
            userStore.setSharing(true) // stale "on" left over from before permission was lost

            vm.setSharing(false)
            advanceUntilIdle()

            assertFalse(userStore.isSharingLocation.value)
        }

    @Test
    fun permissionLostWhileSharing_tellsPeersOncePerLoss() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            val (vm, userStore) = sharingVm(source, client)
            viewModel = vm
            userStore.setSharing(true) // sharing was on; permission is not held

            source.setAppForeground(true)
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { client.sendStoppedSharing(any()) }

            source.setAppForeground(false)
            advanceUntilIdle()
            source.setAppForeground(true)
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { client.sendStoppedSharing(any()) }
        }

    @Test
    fun permissionLost_butNotSharing_tellsNoOne() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            val (vm, _) = sharingVm(source, client)
            viewModel = vm

            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 0) { client.sendStoppedSharing(any()) }
        }

    @Test
    fun permissionRegained_resumesSharingAndRearmsNotification() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            val (vm, userStore) = sharingVm(source, client)
            viewModel = vm
            userStore.setSharing(true)
            source.setAppForeground(true)
            advanceUntilIdle()
            while (shadowOf(app).nextStartedService != null) { /* drain */ }

            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            source.setAppForeground(false)
            advanceUntilIdle()
            source.setAppForeground(true)
            advanceUntilIdle()

            var forced = false
            while (true) {
                val i = shadowOf(app).nextStartedService ?: break
                if (i.action == LocationService.ACTION_FORCE_PUBLISH) forced = true
            }
            assertTrue(forced, "regaining permission while sharing must republish our location")

            // A second loss is a new event and must notify again.
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            source.setAppForeground(false)
            advanceUntilIdle()
            source.setAppForeground(true)
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 2) { client.sendStoppedSharing(any()) }
        }

    @Test
    fun permissionLoss_notificationFailure_isRetriedNextTime() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            io.mockk.coEvery { client.sendStoppedSharing(any()) } throws RuntimeException("offline") andThen Unit
            val (vm, userStore) = sharingVm(source, client)
            viewModel = vm
            userStore.setSharing(true)

            source.setAppForeground(true)
            advanceUntilIdle()
            source.setAppForeground(false)
            advanceUntilIdle()
            source.setAppForeground(true)
            advanceUntilIdle()
            // third time: it succeeded on the retry, so no more sends
            source.setAppForeground(false)
            advanceUntilIdle()
            source.setAppForeground(true)
            advanceUntilIdle()

            io.mockk.coVerify(exactly = 2) { client.sendStoppedSharing(any()) }
        }

    @Test
    fun onLocationPermissionChanged_withoutForegroundFlip_resumesSharing() =
        runTest {
            val source = TestFakeLocationSource()
            val client = mockPollingClient()
            val (vm, userStore) = sharingVm(source, client)
            viewModel = vm
            userStore.setSharing(true)
            source.setAppForeground(true)
            advanceUntilIdle()
            io.mockk.coVerify(exactly = 1) { client.sendStoppedSharing(any()) }
            while (shadowOf(app).nextStartedService != null) { /* drain */ }

            // Granted through the in-app flow: the app never leaves the foreground.
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            vm.onLocationPermissionChanged()
            advanceUntilIdle()

            var forced = false
            while (true) {
                val i = shadowOf(app).nextStartedService ?: break
                if (i.action == LocationService.ACTION_FORCE_PUBLISH) forced = true
            }
            assertTrue(forced, "granting in-app must republish our location without a foreground flip")
        }
}
