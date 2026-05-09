package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RandomizationTest {
    init {
        initializeE2eeTests()
    }

    private class CallTrackerMailbox : MailboxClient {
        val pollTokens = mutableListOf<String>()
        val postTokens = mutableListOf<String>()

        override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
            postTokens.add(token)
        }

        override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
            pollTokens.add(token)
            return emptyList()
        }

        fun reset() {
            pollTokens.clear()
            postTokens.clear()
        }
    }

    @Test
    fun testPollShufflesFriendOrder() = runTest {
        val storage = MemoryStorage()
        val store = E2eeManager(storage)
        val aliceManager = E2eeManager(MemoryStorage())

        // Create 5 friends
        for (i in 1..5) {
            val qr = aliceManager.createInvite("Friend $i")
            val (init, session) = KeyExchange.bobProcessQr(qr, "Me")
            store.processScannedQr(qr, "Friend $i") // This adds to 'friends' map in store
        }

        val tracker = CallTrackerMailbox()
        val client = LocationClient("http://fake", store, tracker)

        val seenOrders = mutableSetOf<List<String>>()

        repeat(50) {
            tracker.reset()
            client.poll()
            seenOrders.add(tracker.pollTokens.toList())
        }

        assertTrue(seenOrders.size > 1, "Should have seen multiple different poll orders for friends (got ${seenOrders.size})")
    }

    @Test
    fun testPollFriendShufflesTokenOrder() = runTest {
        val storage = MemoryStorage()
        val store = E2eeManager(storage)
        val aliceManager = E2eeManager(MemoryStorage())

        // Create a friend
        val qr = aliceManager.createInvite("Friend")
        val (init, _) = store.processScannedQr(qr, "Friend")
        val friendId = store.listFriends().first().id

        // Manually inject tokens to poll
        store.updateFriend(friendId) { entry ->
            entry.copy(session = entry.session.copy(
                recvToken = "new_token".encodeToByteArray(),
                prevRecvToken = "old_token".encodeToByteArray()
            ))
        }

        val tracker = CallTrackerMailbox()
        val client = LocationClient("http://fake", store, tracker)

        val seenOrders = mutableSetOf<List<String>>()

        repeat(50) {
            tracker.reset()
            client.pollFriend(friendId)
            // Filter to only include the initial tokens we expect
            val initialPolls = tracker.pollTokens.filter { it == "6e65775f746f6b656e" || it == "6f6c645f746f6b656e" }
            if (initialPolls.size >= 2) {
                seenOrders.add(initialPolls.take(2))
            }
        }

        assertTrue(seenOrders.size > 1, "Should have seen different orders for token polling (got ${seenOrders.size})")
    }

    @Test
    fun testSendLocationShufflesFriendOrder() = runTest {
        val storage = MemoryStorage()
        val store = E2eeManager(storage)
        val aliceManager = E2eeManager(MemoryStorage())

        for (i in 1..5) {
            val qr = aliceManager.createInvite("Friend $i")
            store.processScannedQr(qr, "Friend $i")
        }

        val tracker = CallTrackerMailbox()
        val client = LocationClient("http://fake", store, tracker)

        val seenOrders = mutableSetOf<List<String>>()

        repeat(50) {
            tracker.reset()
            client.sendLocation(0.0, 0.0)
            seenOrders.add(tracker.postTokens.toList())
        }

        assertTrue(seenOrders.size > 1, "Should have seen multiple different post orders for friends (got ${seenOrders.size})")
    }
}
