package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
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
    fun testOutboxPersistence() = runBlocking {
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
}
