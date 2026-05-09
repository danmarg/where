package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DesyncReproTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testProcessBatchDivergenceStuckBug() =
        runTest {
            val storage = MemoryStorage()
            val chaosStorage = ChaosStorage(storage)
            val store = E2eeManager(chaosStorage)

            val aliceManager = E2eeManager(MemoryStorage())
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = store.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 1. Alice sends a message
            val (aSess, msg) =
                Session.encryptMessage(
                    aliceManager.getFriend(aliceToBobId)!!.session,
                    MessagePlaintext.Location(1.0, 1.0, 1.0, 1000L),
                )
            aliceManager.updateSession(aliceToBobId, aSess)

            // 2. Bob processes batch, but DISK WRITE FAILS
            chaosStorage.failNextWrite = true
            val recvToken = store.getFriend(bobToAliceId)!!.session.recvToken.toHex()

            assertFailsWith<Exception> {
                store.processBatch(bobToAliceId, recvToken, listOf(msg))
            }

            // 3. Verify NO divergence (due to our fix)
            val inMemorySeq = store.getFriend(bobToAliceId)!!.session.recvSeq
            assertEquals(0L, inMemorySeq, "In-memory state should NOT be ratcheted if disk write failed")

            val reloadedStore = E2eeManager(storage)
            val onDiskSeq = reloadedStore.getFriend(bobToAliceId)!!.session.recvSeq
            assertEquals(0L, onDiskSeq, "On-disk state should NOT be ratcheted")

            // 4. Bob tries to process the SAME batch again (because he didn't ACK)
            // This time disk write succeeds
            val result = store.processBatch(bobToAliceId, recvToken, listOf(msg))
            assertNotNull(result)
            assertTrue(result.anySuccess, "Should NOW have success because in-memory state didn't diverge")

            // 5. Verify we are NOT STUCK
            val finalInMemorySeq = store.getFriend(bobToAliceId)!!.session.recvSeq
            assertEquals(1L, finalInMemorySeq, "In-memory state should now be at 1")
        }
}
