package net.af0.where

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.FriendEntry
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
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
    private lateinit var vm: LocationViewModel
    private var now = 1_000_000L

    private fun invite() =
        QrPayload(
            ekPub = Random.nextBytes(32),
            suggestedName = "Alice",
            discoverySecret = Random.nextBytes(32),
        )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        e2ee = mockk(relaxed = true)
        val exchanged = mockk<KeyExchangeInitPayload>(relaxed = true) to mockk<FriendEntry>(relaxed = true)
        coEvery { e2ee.processScannedQr(any(), any()) } returns exchanged
        uiState = FakeUiStateStore()
        vm =
            LocationViewModel(
                app,
                e2eeManagerParam = e2ee,
                locationClientParam = mockk(relaxed = true),
                startPolling = false,
                locationSourceParam = TestFakeLocationSource(),
                uiStateStoreParam = uiState,
            )
        val prefs = app.getSharedPreferences("test_consumed_invites", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        vm.consumedInvites = ConsumedInvites(prefs) { now }
        vm.serviceStarter = {}
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun acceptedInviteOpenedAgainIsRejectedWithoutPairing() =
        runTest(testDispatcher) {
            val qr = invite()
            assertTrue(vm.processQrUrl(qr.toUrl()))
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()
            coVerify(exactly = 1) { e2ee.processScannedQr(any(), any()) }

            // The same link delivered again (fresh activity launch, second tap, re-scan).
            assertFalse(vm.processQrUrl(qr.toUrl()))
            assertNull(uiState.pendingQrForNaming.value, "no naming dialog for an already-accepted invite")

            // Even a confirm racing in for it is refused.
            vm.confirmQrScan(qr, "Alice")
            advanceUntilIdle()
            coVerify(exactly = 1) { e2ee.processScannedQr(any(), any()) }
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
    fun consumedMarkIsPrunedAfterRetention() {
        val prefs = app.getSharedPreferences("test_consumed_invites", Context.MODE_PRIVATE)
        val consumed = ConsumedInvites(prefs) { now }
        val qr = invite()
        consumed.add(qr)
        assertTrue(consumed.contains(qr))

        now += ConsumedInvites.RETENTION_SECONDS + 1
        assertFalse(consumed.contains(qr))
        consumed.add(invite())
        assertEquals(1, prefs.all.size, "expired entries are pruned on the next write")
    }
}
