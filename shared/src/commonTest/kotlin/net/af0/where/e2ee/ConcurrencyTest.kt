package net.af0.where.e2ee

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ConcurrencyTest {
    init {
        initializeE2eeTests()
    }

    private class DelayStorage(private val delegate: E2eeStorage) : E2eeStorage {
        override fun getString(key: String): String? = delegate.getString(key)
        override fun putString(key: String, value: String) {
            // Inject delay before write to expand the TOCTOU window.
            // Using a portable busy-wait instead of Thread.sleep to keep this in commonTest.
            val start = currentTimeMillis()
            while (currentTimeMillis() - start < 100) {
                // busy wait
            }
            delegate.putString(key, value)
        }
    }

    @Test
    fun testConcurrentEncryptAndStore() = runBlocking(Dispatchers.Default) {
        val storage = MemoryStorage()
        val delayStorage = DelayStorage(storage)
        val aliceStore = E2eeStore(delayStorage)
        val bobStore = E2eeStore(MemoryStorage())

        // 1. Pair
        val qr = aliceStore.createInvite("Alice")
        val (init, _) = bobStore.processScannedQr(qr)
        aliceStore.processKeyExchangeInit(init, "Bob", qr.ekPub)
        val bobId = aliceStore.listFriends().first().id

        // 2. Launch two concurrent encryptAndStore calls
        val payload1 = MessagePlaintext.Location(1.0, 1.0, 1.0, 1000)
        val payload2 = MessagePlaintext.Location(2.0, 2.0, 1.0, 2000)

        val job1 = async {
            try {
                aliceStore.encryptAndStore(bobId, payload1)
                null
            } catch (e: Exception) {
                e
            }
        }
        val job2 = async {
            // Small delay to ensure they are slightly staggered but still overlap in the 100ms window
            delay(20)
            try {
                aliceStore.encryptAndStore(bobId, payload2)
                null
            } catch (e: Exception) {
                e
            }
        }

        val res1 = job1.await()
        val res2 = job2.await()

        // 3. Verify exactly one failed
        val failures = listOfNotNull(res1, res2)
        assertEquals(1, failures.size, "Exactly one call should fail due to outbox conflict")
        assertIs<OutboxConflictException>(failures[0])

        // 4. Verify session state is consistent (no nonce reuse if it had advanced)
        val entry = aliceStore.getFriend(bobId)!!
        assertNotNull(entry.outbox)
        // If it was the first send after pairing, seq should be 1 (transition was already seq 0)
        // actually Session.encryptMessage for transition (Alice init) sets seq=0, pn=session.sendSeq (0)
        // Wait, KeyExchange.aliceProcessInit creates a session with sendSeq=0.
        // Session.encryptMessage increments sendSeq to 1.
        assertEquals(1, entry.session.sendSeq)
    }
}
