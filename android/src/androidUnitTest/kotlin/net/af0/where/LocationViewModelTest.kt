package net.af0.where

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.LocationClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocationViewModelTest {
    private val app: Application = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private var viewModel: LocationViewModel? = null

    private class FakeE2eeStorage : E2eeStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }

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
        viewModel?.stopPolling()
        io.mockk.unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testInviteLifecycle_AliceSide() = runTest {
        val store = E2eeStore(FakeE2eeStorage())
        val client = LocationClient("http://localhost", store)
        // Disable automatic polling loop to prevent hangs
        viewModel = LocationViewModel(app, store, client, startPolling = false)
        val vm = viewModel!!

        // 1. Create invite
        vm.createInvite()
        assertNotNull(vm.pendingInviteQr.value)
        assertNotNull(store.pendingQrPayload)
        
        // 2. Simulate finding an init payload via polling
        val initPayload = KeyExchangeInitPayload(
            v = 1,
            token = "token",
            ekPub = byteArrayOf(1, 2, 3),
            keyConfirmation = byteArrayOf(4, 5, 6),
            suggestedName = "Bob"
        )
        
        io.mockk.coEvery { net.af0.where.e2ee.E2eeMailboxClient.poll(any(), any()) } returns listOf(initPayload)
        
        // Manually trigger poll
        vm.pollPendingInvite()
        
        assertNotNull(vm.pendingInitPayload.value)
        assertNull(vm.pendingInviteQr.value)
        assertEquals("Bob", vm.pendingInitPayload.value?.suggestedName)

        // 3. Alice cancels naming Bob
        vm.cancelPendingInit()
        
        assertNull(vm.pendingInitPayload.value)
        assertNull(vm.pendingInviteQr.value)
        assertNull(store.pendingQrPayload, "Store should be cleared when Alice cancels")
    }

    @Test
    fun testCancelQrScan_BobSide() = runTest {
        viewModel = LocationViewModel(app, startPolling = false)
        val vm = viewModel!!
        
        val qr = QrPayload(byteArrayOf(1,2,3), "Alice", "fp")
        
        // Bob scans - simulate by setting the private field via reflection since it's Bob's side
        val pendingQrField = LocationViewModel::class.java.getDeclaredField("_pendingQrForNaming")
        pendingQrField.isAccessible = true
        val pendingQrFlow = pendingQrField.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<QrPayload?>
        pendingQrFlow.value = qr
        
        assertEquals(qr, vm.pendingQrForNaming.value)
        
        // Bob cancels
        vm.cancelQrScan()
        assertNull(vm.pendingQrForNaming.value)
    }
}
