package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OutboxRecoveryTest {
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
    fun testOutboxPersistence() =
        runTest {
            // Setup a friend session briefly
            val aliceStore = E2eeStore(MemoryStorage())
            val qr = aliceStore.createInvite("Alice")
            val (_, bobEntry) = store.processScannedQr(qr)
            val friendId = bobEntry.id

            // Encrypt a message but DO NOT post it yet.
            // encryptAndStore is atomic and saves to disk.
            val plaintext = MessagePlaintext.Location(1.0, 2.0, 3.0, 1000L)
            store.encryptAndStore(friendId, plaintext)

            // Verify it was persisted
            val friendBefore = store.getFriend(friendId)
            assertNotNull(friendBefore?.outbox)
            val originalSeq = friendBefore?.session?.sendSeq ?: 0L
            assertTrue(originalSeq > 0)

            // Simulate crash: create a new store instance from same storage
            val recoveredStore = E2eeStore(storage)
            val friendRecovered = recoveredStore.getFriend(friendId)

            assertNotNull(friendRecovered?.outbox)
            assertEquals(friendBefore?.outbox?.token, friendRecovered?.outbox?.token)
            assertEquals(originalSeq, friendRecovered?.session?.sendSeq)

            // Ensure nonce safety: next encryptAndStore MUST advance seq further
            val nextPlaintext = MessagePlaintext.Location(1.1, 2.1, 3.1, 2000L)
            recoveredStore.encryptAndStore(friendId, nextPlaintext)

            val friendFinal = recoveredStore.getFriend(friendId)
            assertTrue(friendFinal!!.session.sendSeq > originalSeq)
        }

    @Test
    fun testOutboxPermanentFailureClearsOutbox() =
        runTest {
            // use 'store' (Bob's side, pre-initialized with 'storage' in setup())
            // and 'aliceStore' (Alice's side, sharing the same storage)
            val aliceStore = E2eeStore(storage)

            val qr = aliceStore.createInvite("Alice")
            val (_, bobEntry) = store.processScannedQr(qr)
            val friendId = bobEntry.id

            // 1. Setup outbox with a message for Alice in Bob's store
            store.encryptAndStore(friendId, MessagePlaintext.Keepalive())
            assertNotNull(store.getFriend(friendId)?.outbox, "Outbox should be populated")

            // 2. Setup mock client that throws 404
            val fakeMailbox =
                object : MailboxClient {
                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        throw ServerException(404, "Not Found")
                    }

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> = emptyList()
                }
            val client = LocationClient("http://fake", store, fakeMailbox)

            // 3. Process outboxes.
            // Recovery logic is triggered at the start of any poll (§5.4).
            client.poll()

            // 4. Verify outbox is cleared
            val finalFriend = store.getFriend(friendId)
            assertNull(finalFriend?.outbox, "Outbox should be cleared after 404")
        }
}
