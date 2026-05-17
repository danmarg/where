package net.af0.where

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.PendingInviteResult
import net.af0.where.e2ee.PendingInviteView
import net.af0.where.e2ee.QrPayload
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = net.af0.where.TestWhereApplication::class)
class LocationServiceReproductionTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeLocationSource: ServiceFakeLocationSource

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeLocationSource = ServiceFakeLocationSource()
        io.mockk.mockkObject(net.af0.where.e2ee.KtorMailboxClient)
        io.mockk.coEvery { net.af0.where.e2ee.KtorMailboxClient.poll(any(), any()) } returns emptyList()
    }

    @After
    fun teardown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testPollPendingInvites_DoesNotPopUpIfInviteWasClearedWhilePolling() =
        runTest {
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = mockk<LocationClient>(relaxed = true)
            val mockStore = mockk<E2eeManager>(relaxed = true)
            service.locationClientOverride = mockClient
            service.e2eeManagerOverride = mockStore
            service.locationSourceOverride = fakeLocationSource

            controller.create()

            try {
                val aliceEkPub = byteArrayOf(1, 2, 3)
                val initPayload = mockk<KeyExchangeInitPayload>(relaxed = true)
                val pollResult = PendingInviteResult(initPayload, byteArrayOf(), aliceEkPub, false)

                // pollPendingInvites returns a result, but the invite was cleared before we process it
                coEvery { mockClient.pollPendingInvites() } returns listOf(pollResult)
                coEvery { mockStore.listPendingInvites() } returns emptyList()
                coEvery { mockStore.listFriends() } returns emptyList()

                service.doPoll()
                advanceUntilIdle()

                // onPendingInit must NOT be called — the invite was already cleared
                assertNull(fakeLocationSource.pendingInitPayload.value)
            } finally {
                controller.destroy()
            }
        }

    /**
     * Regression test for bug 1: doPoll() must surface a pending init to the UI.
     *
     * Before the fix, LocationClient.poll() (called inside doPoll) silently consumed the
     * invite via its internal pollPendingInvites() + processKeyExchangeInit() call.
     * When LocationService.pollPendingInvites() then ran, it found no invite, so
     * pendingInitPayload was never set and the QR sheet was never dismissed.
     */
    @Test
    fun testPollPendingInvites_SetsPendingInitAfterDoPoll() =
        runTest {
            val controller = Robolectric.buildService(LocationService::class.java)
            val service = controller.get()

            val mockClient = mockk<LocationClient>(relaxed = true)
            val mockStore = mockk<E2eeManager>(relaxed = true)
            service.locationClientOverride = mockClient
            service.e2eeManagerOverride = mockStore
            service.locationSourceOverride = fakeLocationSource

            controller.create()

            try {
                val aliceEkPub = byteArrayOf(1, 2, 3)
                val initPayload = mockk<KeyExchangeInitPayload>(relaxed = true)
                val pollResult = PendingInviteResult(initPayload, byteArrayOf(), aliceEkPub, false)

                // The matching invite exists, so the filter in pollPendingInvites() passes.
                val mockQr = mockk<QrPayload>(relaxed = true)
                every { mockQr.ekPub } returns aliceEkPub
                val pendingInvite = PendingInviteView(qrPayload = mockQr, createdAt = 0L)

                coEvery { mockClient.pollPendingInvites() } returns listOf(pollResult)
                coEvery { mockStore.listPendingInvites() } returns listOf(pendingInvite)
                coEvery { mockStore.listFriends() } returns emptyList()

                service.doPoll()
                advanceUntilIdle()

                assertNotNull(
                    fakeLocationSource.pendingInitPayload.value,
                    "doPoll() must set pendingInitPayload so the naming dialog appears " +
                        "and the QR sheet is dismissed",
                )
            } finally {
                controller.destroy()
            }
        }
}
