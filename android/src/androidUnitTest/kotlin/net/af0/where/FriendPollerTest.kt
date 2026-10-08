package net.af0.where

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.ConnectionStatus
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PendingInviteResult
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.RawKeyValueStorage
import net.af0.where.e2ee.UserStore
import net.af0.where.model.UserLocation
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FriendPollerTest {
    private var now = 10_000_000L
    private val client = mockk<LocationClient>(relaxed = true)
    private val manager = mockk<E2eeManager>(relaxed = true)
    private val source = ServiceFakeLocationSource()
    private val ui = FakeUiStateStore()
    private lateinit var userStore: UserStore
    private lateinit var poller: FriendPoller

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        LocationService.clock = { now }
        userStore =
            UserStore(
                object : RawKeyValueStorage {
                    private val d = mutableMapOf<String, String>()

                    override fun getString(key: String) = d[key]

                    override fun putString(
                        key: String,
                        value: String,
                    ) {
                        d[key] = value
                    }
                },
            )
        coEvery { client.poll(any(), any(), any()) } returns emptyList()
        coEvery { client.pollPendingInvites() } returns emptyList()
        coEvery { manager.listFriends() } returns emptyList()
        coEvery { manager.listPendingInvites() } returns emptyList()
        poller = FriendPoller(client, manager, userStore, source, ui, clock = { now })
    }

    @After
    fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun inviteWith(ekPub: ByteArray): PendingInviteView {
        val qr = mockk<QrPayload>(relaxed = true)
        every { qr.ekPub } returns ekPub
        return PendingInviteView(qrPayload = qr, createdAt = 0L)
    }

    private fun result(
        inviteEk: ByteArray,
        multiple: Boolean = false,
        error: String? = null,
    ) = PendingInviteResult(mockk<KeyExchangeInitPayload>(relaxed = true), byteArrayOf(9), inviteEk, multiple, error)

    @Test
    fun poll_forwardsFriendUpdatesToUiAndStore() =
        runTest {
            val update = UserLocation("f1", 1.0, 2.0, 123L)
            coEvery { client.poll(any(), any(), any()) } returns listOf(update)

            poller.poll()

            assertEquals(update, source.friendLocations.value["f1"])
            coVerify { manager.updateLastLocation("f1", 1.0, 2.0, 123L) }
            assertEquals(ConnectionStatus.Ok, source.connectionStatus.value)
        }

    @Test
    fun poll_passesForegroundAndSharingStateToClient() =
        runTest {
            source.setAppForeground(true)
            userStore.setSharing(false)

            poller.poll()

            coVerify { client.poll(isForeground = true, pausedFriendIds = any(), sharingEnabled = false) }
        }

    @Test
    fun poll_refreshesFriendAndInviteLists() =
        runTest {
            val invites = listOf(inviteWith(byteArrayOf(1)))
            coEvery { manager.listPendingInvites() } returns invites

            poller.poll()

            assertEquals(invites, source.allPendingInvites.value)
        }

    @Test
    fun poll_matchingHandshake_setsPendingInitAndDismissesSheet() =
        runTest {
            val ek = byteArrayOf(1, 2, 3)
            val r = result(ek, multiple = true)
            coEvery { client.pollPendingInvites() } returns listOf(r)
            coEvery { manager.listPendingInvites() } returns listOf(inviteWith(ek))
            ui.setInviteSheetShowing(true)

            poller.poll()

            assertEquals(r.payload, source.pendingInitPayload.value)
            assertTrue(source.pendingInitAliceEkPub.value!!.contentEquals(ek))
            assertFalse(ui.isInviteSheetShowing.value)
            assertTrue(ui.multipleScansDetected.value)
        }

    @Test
    fun poll_handshakeForClearedInvite_isIgnored() =
        runTest {
            coEvery { client.pollPendingInvites() } returns listOf(result(byteArrayOf(1, 2, 3)))
            coEvery { manager.listPendingInvites() } returns listOf(inviteWith(byteArrayOf(7, 7, 7)))

            poller.poll()

            assertNull(source.pendingInitPayload.value)
        }

    @Test
    fun poll_pairingError_surfacesErrorWithoutPendingInit() =
        runTest {
            val ek = byteArrayOf(1, 2, 3)
            coEvery { client.pollPendingInvites() } returns listOf(result(ek, error = "bad handshake"))
            coEvery { manager.listPendingInvites() } returns listOf(inviteWith(ek))
            ui.setInviteSheetShowing(true)

            poller.poll()

            assertNull(source.pendingInitPayload.value)
            assertFalse(ui.isInviteSheetShowing.value)
        }

    @Test
    fun poll_existingNamingDialog_isNotOverwritten() =
        runTest {
            val ek = byteArrayOf(1, 2, 3)
            val existing = mockk<KeyExchangeInitPayload>(relaxed = true)
            source.onPendingInit(existing, ek)
            coEvery { client.pollPendingInvites() } returns listOf(result(ek))
            coEvery { manager.listPendingInvites() } returns listOf(inviteWith(ek))

            poller.poll()

            assertEquals(existing, source.pendingInitPayload.value)
        }

    @Test
    fun poll_pollPendingInvitesThrows_doesNotCrashOrLoseFriendUpdates() =
        runTest {
            val update = UserLocation("f1", 1.0, 2.0, 123L)
            coEvery { client.poll(any(), any(), any()) } returns listOf(update)
            coEvery { client.pollPendingInvites() } throws RuntimeException("boom")

            poller.poll()

            assertEquals(update, source.friendLocations.value["f1"])
        }

    @Test
    fun poll_clientFailure_surfacesErrorAndDoesNotThrow() =
        runTest {
            coEvery { client.poll(any(), any(), any()) } throws RuntimeException("network down")

            poller.poll()

            assertTrue(source.connectionStatus.value is ConnectionStatus.Error)
        }

    @Test
    fun poll_successAfterFailure_clearsError() =
        runTest {
            coEvery { client.poll(any(), any(), any()) } throws RuntimeException("network down") andThen emptyList()

            poller.poll()
            poller.poll()

            assertEquals(ConnectionStatus.Ok, source.connectionStatus.value)
        }

    @Test
    fun poll_cancellationPropagates() =
        runTest {
            coEvery { client.poll(any(), any(), any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> { poller.poll() }
            // The cancelled cycle must release the process-wide lock for the next caller.
            coEvery { client.poll(any(), any(), any()) } returns emptyList()
            poller.poll()
        }

    @Test
    fun poll_expiredInviteCleanup_runsAtMostOncePerHour() =
        runTest {
            poller.poll()
            now += 59 * 60_000L
            poller.poll()
            coVerify(exactly = 1) { manager.cleanupExpiredInvites(48 * 3600L) }

            now += 2 * 60_000L
            poller.poll()
            coVerify(exactly = 2) { manager.cleanupExpiredInvites(48 * 3600L) }
        }

    @Test
    fun poll_cleanupFailure_isReportedNotThrown() =
        runTest {
            coEvery { manager.cleanupExpiredInvites(any()) } throws RuntimeException("db")

            poller.poll()

            assertTrue(source.connectionStatus.value is ConnectionStatus.Error)
        }

    @Test
    fun poll_concurrentCallsAreSerialized() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            var calls = 0
            coEvery { client.poll(any(), any(), any()) } coAnswers {
                val n = ++calls
                events += "start$n"
                if (n == 1) gate.await()
                events += "end$n"
                emptyList()
            }

            val first = launch { poller.poll() }
            val second = launch { poller.poll() }
            advanceUntilIdle()
            assertEquals(listOf("start1"), events, "second cycle must wait for the first")

            gate.complete(Unit)
            first.join()
            second.join()
            assertEquals(listOf("start1", "end1", "start2", "end2"), events)
        }

    @Test
    fun isRapidPolling_falseWhenIdle() {
        assertFalse(poller.isRapidPolling())
    }

    @Test
    fun isRapidPolling_trueWhenInviteSheetShowing() {
        ui.setInviteSheetShowing(true)
        assertTrue(poller.isRapidPolling())
    }

    @Test
    fun isRapidPolling_trueWhenNamingScannedQr() {
        ui.onPendingQrForNaming(mockk<QrPayload>(relaxed = true))
        assertTrue(poller.isRapidPolling())
    }

    @Test
    fun isRapidPolling_trueShortlyAfterRapidTrigger() {
        source.triggerRapidPoll()
        assertTrue(poller.isRapidPolling())

        now += 61_000L
        assertFalse(poller.isRapidPolling())
    }

    @Test
    fun isRapidPolling_freshPendingInitIsBounded() {
        source.onPendingInit(mockk<KeyExchangeInitPayload>(relaxed = true), byteArrayOf(1))
        assertTrue(poller.isRapidPolling())

        now += FriendPoller.PENDING_INIT_RAPID_TIMEOUT_MS + 1
        assertFalse(poller.isRapidPolling(), "an unconfirmed invite must not pin rapid polling forever")
    }

    @Test
    fun hasLocationPermission_reflectsGrantState() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(app.hasLocationPermission())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(app.hasLocationPermission())
    }

    @Test
    fun hasLocationPermission_fineAlsoCounts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(app.hasLocationPermission())
    }

    @Test
    fun pollersSharingAGate_serializeAndShareCleanupThrottle() =
        runTest {
            val gate = PollGate()
            val a = FriendPoller(client, manager, userStore, source, ui, clock = { now }, gate = gate)
            val b = FriendPoller(client, manager, userStore, source, ui, clock = { now }, gate = gate)

            a.poll()
            b.poll()

            coVerify(exactly = 1) { manager.cleanupExpiredInvites(any()) }
        }

    @Test
    fun pollersWithSeparateGates_doNotShareState() =
        runTest {
            val a = FriendPoller(client, manager, userStore, source, ui, clock = { now }, gate = PollGate())
            val b = FriendPoller(client, manager, userStore, source, ui, clock = { now }, gate = PollGate())

            a.poll()
            b.poll()

            coVerify(exactly = 2) { manager.cleanupExpiredInvites(any()) }
        }

    @Test
    fun poll_treatsSharingAsOffWithoutPermission() =
        runTest {
            userStore.setSharing(true)
            val receiveOnly = FriendPoller(client, manager, userStore, source, ui, clock = { now }, canShare = { false })

            receiveOnly.poll()

            coVerify { client.poll(isForeground = any(), pausedFriendIds = any(), sharingEnabled = false) }
        }

    @Test
    fun poll_sharingFollowsToggleWhenPermitted() =
        runTest {
            userStore.setSharing(true)
            val sharing = FriendPoller(client, manager, userStore, source, ui, clock = { now }, canShare = { true })

            sharing.poll()

            coVerify { client.poll(isForeground = any(), pausedFriendIds = any(), sharingEnabled = true) }
        }
}
