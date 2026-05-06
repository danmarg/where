package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DormancyTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var aliceStore: E2eeStore
    private lateinit var bobStore: E2eeStore
    private lateinit var aliceClient: LocationClient
    private lateinit var bobClient: LocationClient
    private lateinit var relay: RelayMailboxClient
    private lateinit var timeProvider: ChaosTimeProvider

    @BeforeTest
    fun setup() {
        timeProvider = ChaosTimeProvider()
        TimeSource.setProvider(timeProvider)

        relay = RelayMailboxClient()
        aliceStore = E2eeStore(MemoryStorage())
        bobStore = E2eeStore(MemoryStorage())
        aliceClient = LocationClient("https://api.where.af0.net", aliceStore, relay)
        bobClient = LocationClient("https://api.where.af0.net", bobStore, relay)
    }

    @AfterTest
    fun tearDown() {
        TimeSource.setProvider(DefaultTimeProvider)
    }

    @Test
    fun testStaleTransitionTimeout() = runTest {
        // 1. Pair Alice and Bob
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, _) = bobStore.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(qr, initPayload)

        val results = aliceClient.pollPendingInvites()
        assertEquals(1, results.size)
        val aliceEntry = aliceStore.processKeyExchangeInit(results[0].payload, "Bob", qr.ekPub)
        assertNotNull(aliceEntry)

        // 2. Alice sends a message, enters PENDING_SEND (isSendTokenPending=true)
        // We simulate a network failure on POST so the outbox remains and isSendTokenPending stays true.
        val chaosMailbox = ChaosMailboxClient(relay)
        chaosMailbox.failNextPost = true
        val aliceClientWithChaos = LocationClient("https://api.where.af0.net", aliceStore, chaosMailbox)

        try {
            aliceClientWithChaos.sendLocation(1.0, 2.0)
        } catch (_: Exception) {}

        val aliceFriend = aliceStore.getFriend(aliceEntry.id)!!
        assertTrue(aliceFriend.session.isSendTokenPending)
        assertNotNull(aliceFriend.session.sendTokenPendingSinceMs, "sendTokenPendingSinceMs should be set automatically")
        val prevToken = aliceFriend.session.prevSendToken

        // 3. Time passes (7+ days)
        timeProvider.addOffset(PENDING_TRANSITION_TIMEOUT_MS + 1000)

        // 4. Call processOutboxes (via poll or sendLocation)
        // Disable post completely during poll so it can't recover the outbox.
        chaosMailbox.failPostProbability = 1.0
        aliceClientWithChaos.poll()

        // 5. Assert transition is abandoned
        val aliceFriendStale = aliceStore.getFriend(aliceEntry.id)!!
        assertFalse(aliceFriendStale.session.isSendTokenPending)
        assertNull(aliceFriendStale.session.sendTokenPendingSinceMs)
        assertEquals(prevToken.toHex(), aliceFriendStale.session.sendToken.toHex())
    }

    @Test
    fun testReconnectAfterAbandonment() = runTest {
        // 1. Pair Alice and Bob
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, _) = bobStore.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(qr, initPayload)

        val results = aliceClient.pollPendingInvites()
        val aliceEntry = aliceStore.processKeyExchangeInit(results[0].payload, "Bob", qr.ekPub)
        val friendId = aliceEntry!!.id
        val bobToAliceId = bobStore.listFriends().first().id

        // 2. Alice sends a message, enters PENDING_SEND
        val chaosMailbox = ChaosMailboxClient(relay)
        chaosMailbox.failNextPost = true
        val aliceClientWithChaos = LocationClient("https://api.where.af0.net", aliceStore, chaosMailbox)

        try {
            aliceClientWithChaos.sendLocation(1.0, 2.0)
        } catch (_: Exception) {}

        val friendBefore = aliceStore.getFriend(friendId)!!
        assertTrue(friendBefore.session.isSendTokenPending)
        assertNotNull(friendBefore.session.sendTokenPendingSinceMs)

        // 3. Time passes, Alice abandons transition
        timeProvider.addOffset(PENDING_TRANSITION_TIMEOUT_MS + 1000)
        chaosMailbox.failPostProbability = 1.0
        aliceClientWithChaos.poll()
        assertFalse(aliceStore.getFriend(friendId)!!.session.isSendTokenPending)

        // 4. Bob (the peer) finally wakes up and sends a message
        // Reset time so Bob doesn't expire.
        timeProvider.addOffset(-(PENDING_TRANSITION_TIMEOUT_MS + 1000))

        bobClient.sendLocation(3.0, 4.0)

        // 5. Alice polls and should decrypt successfully
        val updates = aliceClient.poll()
        assertEquals(1, updates.size)
        assertEquals(3.0, updates[0].lat)

        // 6. Alice sends a new message. It should still carry the new localDhPub
        // and Bob should be able to ratchet.
        aliceClient.sendLocation(5.0, 6.0)

        var received = false
        repeat(10) {
            val bobUpdates = bobClient.poll()
            if (bobUpdates.any { it.lat == 5.0 }) received = true
            aliceClient.poll()
        }

        assertTrue(received, "Bob should eventually receive Alice's message after abandonment")
    }

    @Test
    fun testLegacySessionUpgrade() = runTest {
        // 1. Create a "legacy" friend entry where sendTokenPendingSinceMs is null but isSendTokenPending is true
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, _) = bobStore.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(qr, initPayload)

        val results = aliceClient.pollPendingInvites()
        val aliceEntry = aliceStore.processKeyExchangeInit(results[0].payload, "Bob", qr.ekPub)
        val friendId = aliceEntry!!.id

        val chaosMailbox = ChaosMailboxClient(relay)
        chaosMailbox.failNextPost = true
        val aliceClientWithChaos = LocationClient("https://api.where.af0.net", aliceStore, chaosMailbox)

        try {
            aliceClientWithChaos.sendLocation(1.0, 2.0)
        } catch (_: Exception) {}

        val friend = aliceStore.getFriend(friendId)!!
        assertTrue(friend.session.isSendTokenPending)

        // Manually strip the timestamp to simulate legacy data
        aliceStore.updateSession(friendId, friend.session.copy(sendTokenPendingSinceMs = null))
        assertNull(aliceStore.getFriend(friendId)!!.session.sendTokenPendingSinceMs)

        // 2. Time passes
        timeProvider.addOffset(PENDING_TRANSITION_TIMEOUT_MS + 1000)

        // 3. Alice polls
        chaosMailbox.failPostProbability = 1.0
        aliceClientWithChaos.poll()

        // 4. Assert transition is NOT abandoned because timestamp was null
        val friendAfter = aliceStore.getFriend(friendId)!!
        assertTrue(friendAfter.session.isSendTokenPending)
        assertNull(friendAfter.session.sendTokenPendingSinceMs)
    }
}
