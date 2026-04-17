package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MultiFriendSyncTest {
    init {
        initializeE2eeTests()
    }

    private lateinit var storage: MemoryStorage
    private lateinit var store: E2eeStore

    @BeforeTest
    fun setup() {
        storage = MemoryStorage()
        store = E2eeStore(storage)
    }

    @Test
    fun `poll returns updates from successful friends even if one fails`() = runTest {
        // Setup two friends: Friend A (success) and Friend B (failure)
        val aliceStore = E2eeStore(MemoryStorage())
        val qrA = aliceStore.createInvite("AliceA")
        val qrB = aliceStore.createInvite("AliceB")

        val (initA, entryA) = store.processScannedQr(qrA, "FriendA")
        val (initB, entryB) = store.processScannedQr(qrB, "FriendB")

        // Alice processes both inits to complete handshakes
        val friendA = aliceStore.processKeyExchangeInit(initA, "FriendA")!!
        val friendB = aliceStore.processKeyExchangeInit(initB, "FriendB")!!

        // Friend A sends a location to Alice
        val locationA = MessagePlaintext.Location(lat = 1.0, lng = 2.0, acc = 0.0, ts = 100L)
        val encryptedA = Session.encryptMessage(friendA.session, locationA).second

        // Mock MailboxClient
        val fakeMailbox = object : MailboxClient {
            var pollACount = 0
            var pollBCount = 0

            override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
                return when (token) {
                    friendA.session.sendToken.toHex() -> {
                        pollACount++
                        listOf(encryptedA)
                    }
                    friendB.session.sendToken.toHex() -> {
                        pollBCount++
                        throw Exception("Friend B mailbox failure")
                    }
                    else -> emptyList()
                }
            }

            override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {}
        }

        val client = LocationClient("http://fake", aliceStore, fakeMailbox)
        
        // Before the fix, this would throw Exception("Friend B mailbox failure")
        // and updates from Friend A would be lost.
        val updates = client.poll()

        assertEquals(1, updates.size, "Should have 1 update from Friend A")
        assertEquals(entryA.id, updates[0].userId)
        assertEquals(1.0, updates[0].lat)
        
        assertEquals(1, fakeMailbox.pollACount)
        assertEquals(1, fakeMailbox.pollBCount)
    }

    @Test
    fun `sendLocation succeeds if at least one friend succeeds`() = runTest {
        // Setup two friends: Friend A (success) and Friend B (failure)
        val aliceStore = E2eeStore(MemoryStorage())
        val qrA = aliceStore.createInvite("AliceA")
        val qrB = aliceStore.createInvite("AliceB")

        val (initA, _) = store.processScannedQr(qrA, "FriendA")
        val (initB, _) = store.processScannedQr(qrB, "FriendB")

        aliceStore.processKeyExchangeInit(initA, "FriendA")!!
        aliceStore.processKeyExchangeInit(initB, "FriendB")!!

        // Mock MailboxClient
        val fakeMailbox = object : MailboxClient {
            var postACount = 0
            var postBCount = 0

            override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> = emptyList()

            override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
                // Determine which friend this is by token (simplification)
                if (token.length > 0) {
                    if (postACount == 0) {
                        postACount++
                        // Success for first friend
                    } else {
                        postBCount++
                        throw Exception("Friend B post failure")
                    }
                }
            }
        }

        val client = LocationClient("http://fake", aliceStore, fakeMailbox)
        
        // Before the fix, this would throw Exception("Friend B post failure")
        client.sendLocation(1.0, 2.0)

        assertEquals(1, fakeMailbox.postACount)
        assertEquals(1, fakeMailbox.postBCount)
    }

    @Test
    fun `poll throws if ALL friends fail`() = runTest {
        val aliceStore = E2eeStore(MemoryStorage())
        val qrA = aliceStore.createInvite("AliceA")
        val (initA, _) = store.processScannedQr(qrA, "FriendA")
        aliceStore.processKeyExchangeInit(initA, "FriendA")!!

        val fakeMailbox = object : MailboxClient {
            override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
                throw Exception("Total failure")
            }
            override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {}
        }

        val client = LocationClient("http://fake", aliceStore, fakeMailbox)
        
        assertFailsWith<Exception> {
            client.poll()
        }
    }
}
