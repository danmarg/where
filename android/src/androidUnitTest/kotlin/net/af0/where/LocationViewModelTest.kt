package net.af0.where

import android.app.Application
import android.content.SharedPreferences
import android.text.TextUtils
import androidx.test.core.app.ApplicationProvider
import dev.icerock.moko.resources.desc.Raw
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.toHex
import net.af0.where.e2ee.discoveryToken
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InviteState
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.KtorMailboxClient
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PROTOCOL_VERSION
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.SessionState
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

    private val _pendingInitDiscoveryToken = MutableStateFlow<String?>(null)
    override val pendingInitDiscoveryToken: StateFlow<String?> = _pendingInitDiscoveryToken.asStateFlow()

    private val _multipleScansDetected = MutableStateFlow(false)
    override val multipleScansDetected: StateFlow<Boolean> = _multipleScansDetected.asStateFlow()

    private val _isSharingLocation = MutableStateFlow(false)
    override val isSharingLocation: StateFlow<Boolean> = _isSharingLocation.asStateFlow()

    private val _pausedFriendIds = MutableStateFlow<Set<String>>(emptySet())
    override val pausedFriendIds: StateFlow<Set<String>> = _pausedFriendIds.asStateFlow()

    private val _friends = MutableStateFlow<List<FriendEntry>>(emptyList())
    override val friends: StateFlow<List<FriendEntry>> = _friends.asStateFlow()

    private val _pendingInvites = MutableStateFlow<List<net.af0.where.e2ee.PendingInvite>>(emptyList())
    override val pendingInvites: StateFlow<List<net.af0.where.e2ee.PendingInvite>> = _pendingInvites.asStateFlow()

    private val _lastRapidPollTrigger = MutableStateFlow(0L)
    override val lastRapidPollTrigger: StateFlow<Long> = _lastRapidPollTrigger.asStateFlow()

    private val _pendingQrForNaming = MutableStateFlow<QrPayload?>(null)
    override val pendingQrForNaming: StateFlow<QrPayload?> = _pendingQrForNaming.asStateFlow()

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
        multipleScans: Boolean,
    ) {
        _pendingInitPayload.value = payload
        _multipleScansDetected.value = multipleScans
    }

    override fun onPendingInitWithToken(
        payload: KeyExchangeInitPayload?,
        multipleScans: Boolean,
        discoveryTokenHex: String?,
    ) {
        _pendingInitPayload.value = payload
        _multipleScansDetected.value = multipleScans
    }

    override fun setSharingLocation(sharing: Boolean) {
        _isSharingLocation.value = sharing
    }

    override fun setPausedFriends(friendIds: Set<String>) {
        _pausedFriendIds.value = friendIds
    }

    override fun onFriendsUpdated(friendsList: List<FriendEntry>) {
        _friends.value = friendsList
    }

    override fun onPendingInvitesUpdated(invites: List<net.af0.where.e2ee.PendingInvite>) {
        _pendingInvites.value = invites
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
 * FakeE2eeStorage for testing.
 */
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
            val store = io.mockk.spyk(E2eeStore(FakeE2eeStorage()))
            val client = LocationClient("http://localhost", store)
            // Disable automatic polling loop to prevent hangs
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = fakeLocationSource,
                )
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
                    v = PROTOCOL_VERSION,
                    token = "token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // Bob's response arriving at the repository
            fakeLocationSource.onPendingInitWithToken(initPayload, false, qr.discoveryToken().toHex())
            advanceUntilIdle()

            // After peer joins, inviteState transitions to None to dismiss QR sheet
            assertTrue(vm.inviteState.value is InviteState.None)
            assertEquals("Bob", vm.pendingInitPayload.value?.suggestedName)

            io.mockk.coEvery { store.processKeyExchangeInit(any(), any(), any()) } returns mockk(relaxed = true)

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
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = E2eeStore(FakeE2eeStorage()),
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    fingerprint = "fp",
                    discoverySecret = ByteArray(32),
                )

            // Bob scans
            source.onPendingQrForNaming(qr)

            assertEquals(qr, vm.pendingQrForNaming.value)

            // Bob cancels
            vm.cancelQrScan()
            assertNull(vm.pendingQrForNaming.value)
        }

    @Test
    fun testPairingFlow_ConfirmPendingInit() =
        runTest {
            val store = mockk<E2eeStore>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    token = "token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // 1. Peer joins Alice's mailbox
            source.onPendingInit(initPayload)
            advanceUntilIdle()

            assertEquals(initPayload, vm.pendingInitPayload.value)

            // 2. Alice confirms Bob's name
            vm.confirmPendingInit("Bob (Friend)")
            advanceUntilIdle()

            io.mockk.coVerify { store.processKeyExchangeInit(initPayload, "Bob (Friend)", any()) }
            assertNull(vm.pendingInitPayload.value)
        }

    @Test
    fun testPairingFlow_CancelPendingInit() =
        runTest {
            val store = mockk<E2eeStore>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            val initPayload =
                KeyExchangeInitPayload(
                    v = PROTOCOL_VERSION,
                    token = "token",
                    ekPub = byteArrayOf(1, 2, 3),
                    keyConfirmation = byteArrayOf(4, 5, 6),
                    suggestedName = "Bob",
                )

            // 1. Peer joins Alice's mailbox
            source.onPendingInit(initPayload)
            advanceUntilIdle()

            assertEquals(initPayload, vm.pendingInitPayload.value)

            // 2. Alice cancels
            vm.cancelPendingInit()
            advanceUntilIdle()

            io.mockk.coVerify { store.clearInvite() }
            assertNull(vm.pendingInitPayload.value)
        }

    @Test
    fun testPairingFlow_BobScanningAlice() =
        runTest {
            val store = mockk<E2eeStore>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            // 1. Simulate Bob scanning Alice's QR code
            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    fingerprint = "alice_fp",
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

            source.onPendingQrForNaming(qr)
            (vm.inviteState as MutableStateFlow).value = InviteState.Pending(qr)

            // 2. Bob confirms Alice's name
            vm.confirmQrScan(qr, "Alice (My Friend)")
            advanceUntilIdle()

            io.mockk.coVerify(timeout = 5000) { store.processScannedQr(any(), any()) }
            io.mockk.coVerify(timeout = 5000) { client.postKeyExchangeInit(any(), any()) }
        }

    @Test
    fun testPairingFlow_CancelQrScan() =
        runTest {
            val store = mockk<E2eeStore>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    fingerprint = "fp",
                    discoverySecret = ByteArray(32),
                )

            source.onPendingQrForNaming(qr)

            // Bob cancels naming Alice
            vm.cancelQrScan()
            advanceUntilIdle()

            assertNull(vm.pendingQrForNaming.value)
            io.mockk.coVerify(exactly = 0) { store.processScannedQr(any(), any()) }
        }

    @Test
    fun testConfirmQrScan_TriggersRapidPollAndForcedLocationUpdate() =
        runTest {
            val store = mockk<E2eeStore>(relaxed = true)
            val client = mockk<LocationClient>(relaxed = true)
            val source = TestFakeLocationSource()
            viewModel =
                LocationViewModel(
                    app,
                    e2eeStoreParam = store,
                    locationClientParam = client,
                    startPolling = false,
                    locationSource = source,
                )
            val vm = viewModel!!

            val qr =
                QrPayload(
                    protocolVersion = PROTOCOL_VERSION,
                    ekPub = byteArrayOf(1, 2, 3),
                    suggestedName = "Alice",
                    fingerprint = "fp",
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
            io.mockk.coVerify(timeout = 5000) { client.postKeyExchangeInit(any(), any()) }
        }
}
