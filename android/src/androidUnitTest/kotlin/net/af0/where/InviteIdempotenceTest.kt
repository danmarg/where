package net.af0.where

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.icerock.moko.resources.desc.Resource
import dev.icerock.moko.resources.desc.StringDesc
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.db.WhereDatabase
import net.af0.where.e2ee.DiscoverySecret
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.InviteAlreadyAcceptedException
import net.af0.where.e2ee.InvitePublicKey
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.SystemRng
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handling the same invite twice must not pair twice; a different invite is unaffected. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestWhereApplication::class)
class InviteIdempotenceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var e2ee: E2eeManager
    private lateinit var uiState: FakeUiStateStore
    private lateinit var source: TestFakeLocationSource
    private lateinit var vm: LocationViewModel

    private fun invite() =
        QrPayload(
            ekPub = InvitePublicKey(Random.nextBytes(32)),
            suggestedName = "Alice",
            discoverySecret = DiscoverySecret.random(SystemRng),
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        e2ee = mockk(relaxed = true)
        val exchanged = mockk<KeyExchangeInitPayload>(relaxed = true) to mockk<FriendEntry>(relaxed = true)
        coEvery { e2ee.processScannedQr(any(), any()) } returns exchanged
        uiState = FakeUiStateStore()
        source = TestFakeLocationSource()
        vm =
            LocationViewModel(
                app,
                e2eeManagerParam = e2ee,
                locationClientParam = mockk(relaxed = true),
                startPolling = false,
                locationSourceParam = source,
                uiStateStoreParam = uiState,
            )
        vm.serviceStarter = {}
    }

    private fun inMemoryDriver() = AndroidSqliteDriver(WhereDatabase.Schema, app, null)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aDifferentInviteIsStillHandled() =
        runTest(testDispatcher) {
            val first = invite()
            vm.processQrUrl(first.toUrl())
            vm.confirmQrScan(first, "Alice")
            advanceUntilIdle()

            val second = invite()
            assertTrue(vm.processQrUrl(second.toUrl()))
            assertEquals(second, uiState.pendingQrForNaming.value)
        }

    @Test
    fun cancelledInviteCanBeOpenedAgain() {
        // Only accepting consumes an invite; dismissing the naming dialog doesn't.
        val qr = invite()
        vm.processQrUrl(qr.toUrl())
        vm.cancelQrScan()
        assertTrue(vm.processQrUrl(qr.toUrl()))
        assertEquals(qr, uiState.pendingQrForNaming.value)
    }

    @Test
    fun failedExchangeDoesNotConsumeTheInvite() =
        runTest(testDispatcher) {
            coEvery { e2ee.processScannedQr(any(), any()) } throws IllegalStateException("boom")
            val qr = invite()
            vm.processQrUrl(qr.toUrl())
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            assertTrue(vm.processQrUrl(qr.toUrl()), "no pairing was created, so the invite may be retried")
        }

    @Test
    fun inviteAlreadyPairedIsRejectedWithoutANamingDialog() =
        runTest(testDispatcher) {
            // A friend already paired from this invite: the friend list rejects the link early.
            val qr = E2eeManager(inMemoryDriver(), UnconfinedTestDispatcher()).createInvite("Alice")
            val (_, friend) = E2eeManager(inMemoryDriver(), UnconfinedTestDispatcher()).processScannedQr(qr, "Bob")
            source.onFriendsUpdated(listOf(friend))

            assertFalse(vm.processQrUrl(qr.toUrl()))
            assertNull(uiState.pendingQrForNaming.value, "no naming dialog for an already-accepted invite")
            assertEquals(StringDesc.Resource(net.af0.where.shared.MR.strings.invite_already_used), vm.inviteError.value)
        }

    @Test
    fun replayCaughtAtConfirmShowsTheError() =
        runTest(testDispatcher) {
            // E.g. the friend was removed since: only the stored consumed-invite record remembers it.
            coEvery { e2ee.processScannedQr(any(), any()) } throws InviteAlreadyAcceptedException()
            val qr = invite()
            vm.processQrUrl(qr.toUrl())
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            assertEquals(StringDesc.Resource(net.af0.where.shared.MR.strings.invite_already_used), vm.inviteError.value)
        }

    @Test
    fun inviteErrorStaysUntilDismissed() =
        runTest(testDispatcher) {
            coEvery { e2ee.processScannedQr(any(), any()) } throws InviteAlreadyAcceptedException()
            val qr = invite()
            vm.processQrUrl(qr.toUrl())
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            // A successful poll resets the connection status; the invite error must survive it.
            source.onConnectionStatus(net.af0.where.e2ee.ConnectionStatus.Ok)
            assertEquals(StringDesc.Resource(net.af0.where.shared.MR.strings.invite_already_used), vm.inviteError.value)
            vm.dismissInviteError()
            assertNull(vm.inviteError.value)
        }

    @Test
    fun realManagerConfirmedTwiceYieldsOneFriendAndOneError() =
        runTest(testDispatcher) {
            val real = E2eeManager(inMemoryDriver(), UnconfinedTestDispatcher())
            val realVm =
                LocationViewModel(
                    app,
                    e2eeManagerParam = real,
                    locationClientParam = mockk(relaxed = true),
                    startPolling = false,
                    locationSourceParam = source,
                    uiStateStoreParam = uiState,
                )
            realVm.serviceStarter = {}
            val qr = E2eeManager(inMemoryDriver(), UnconfinedTestDispatcher()).createInvite("Alice")

            realVm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()
            assertNull(realVm.inviteError.value)
            realVm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()

            assertEquals(1, real.listFriends().size)
            assertEquals(StringDesc.Resource(net.af0.where.shared.MR.strings.invite_already_used), realVm.inviteError.value)
        }
}
