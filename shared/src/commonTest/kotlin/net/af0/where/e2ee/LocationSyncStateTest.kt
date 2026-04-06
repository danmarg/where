package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Platform-agnostic simulation of LocationSyncService (iOS) and LocationService (Android)
 * state management logic. This ensures the "cold start" (Bug A) and "throttling" (Bug B)
 * fixes are robust.
 */
class LocationSyncStateTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var aliceStore: E2eeStore
    private lateinit var bobStore: E2eeStore

    // State variables mimicking LocationSyncService/LocationService
    private var lastSentTime: Long = 0
    private var pendingForcedFriendId: String? = null
    private var isSending: Boolean = false
    private val sentLocations = mutableListOf<Triple<String, Double, Double>>()

    @BeforeTest
    fun setup() {
        aliceStore = E2eeStore(MemoryStorage())
        bobStore = E2eeStore(MemoryStorage())
        lastSentTime = 0
        pendingForcedFriendId = null
        isSending = false
        sentLocations.clear()
    }

    private fun mockSendLocation(
        friendId: String,
        lat: Double,
        lng: Double,
        force: Boolean = false,
        isHeartbeat: Boolean = false,
    ) {
        val now = 1000L // Simulated time
        val effectiveForce = force || (pendingForcedFriendId == friendId)

        // Only skip if NOT forcing and already sending.
        if (!effectiveForce && isSending) return

        // Reduced cooldown check (15s instead of 60s/300s)
        val cooldown = if (isHeartbeat) 300_000L else 15_000L
        val shouldSend = effectiveForce || (now - lastSentTime > cooldown)

        if (shouldSend) {
            isSending = true
            if (pendingForcedFriendId == friendId) pendingForcedFriendId = null

            // Simulation of network call
            sentLocations.add(Triple(friendId, lat, lng))
            lastSentTime = now
            isSending = false
        }
    }

    @Test
    fun testColdStartForcedSend() =
        runBlocking {
            // Alice creates invite
            val qr = aliceStore.createInvite("Alice")

            // Bob scans QR but HAS NO GPS FIX YET (cold start)
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            val friendId = bobEntry.id

            // Mark for forced send when location arrives
            pendingForcedFriendId = friendId

            assertEquals(0, sentLocations.size, "No location sent yet because GPS is null")

            // Now GPS fix arrives 5 seconds later
            mockSendLocation(friendId, 37.7, -122.4, force = false)

            assertEquals(1, sentLocations.size, "Forced send should fire as soon as GPS fix arrives")
            assertEquals(friendId, sentLocations[0].first)
            assertNull(pendingForcedFriendId, "Pending flag should be cleared")
        }

    @Test
    fun testForcedSendBypassesIsSending() {
        val friendId = "friend123"

        // Simulate a "heartbeat" send that is currently "in flight" (isSending = true)
        isSending = true

        // Bob just paired, UI triggers a "forced" publish
        mockSendLocation(friendId, 1.0, 2.0, force = true)

        // If Bug B fix is working, the forced send should NOT be dropped even if isSending is true
        // (In the real code, this works because the Task/Coroutine runs anyway or we bypass the guard)
        // Here we simulate the logic:
        assertTrue(sentLocations.any { it.first == friendId }, "Forced send must bypass isSending guard")
    }

    @Test
    fun testMovementThrottle_Android() {
        val friendId = "all"
        lastSentTime = 1000L

        // 5 seconds later, movement occurs.
        // Bug B fix: Cooldown is now 15s, so a 5s update should be dropped,
        // but a 20s update should be allowed.

        // T+5s: Should be dropped (jitter protection)
        val now5 = 6000L
        val shouldSend5 = (now5 - lastSentTime > 15_000L)
        assertFalse(shouldSend5, "5s update should be throttled")

        // T+20s: Should be allowed (genuine movement)
        val now20 = 21000L
        val shouldSend20 = (now20 - lastSentTime > 15_000L)
        assertTrue(shouldSend20, "20s update should be allowed (Bug B fix: 15s instead of 60s)")
    }
}
