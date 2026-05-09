package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProtocolRobustnessTest {
    init {
        initializeE2eeTests()
    }

    /**
     * Verifies that processBatch correctly sorts messages from the previous epoch
     * before the current epoch, ensuring chronological processing even if the
     * relay returns them out of order.
     */
    @Test
    fun testProcessBatchSortingChronological() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceManager, relay)
            val bobClient = LocationClient("http://fake", bobManager, relay)

            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceId = aliceManager.listFriends().first().id
            val bobId = bobManager.listFriends().first().id

            // 1. Bob sends M1 (B1).
            bobClient.sendLocation(1.0, 1.0)

            // 2. Alice sends A1 keepalive. Bob polls and ratchets to B2.
            aliceClient.sendKeepalive(aliceId)
            bobClient.poll()

            // 3. Bob sends M2 (B2).
            bobClient.sendLocation(2.0, 2.0)

            // 4. Now Alice's mailbox at Bob's T_B0 contains [M1 (B1), M2 (B2)].
            val recvToken = aliceManager.getFriend(aliceId)!!.session.recvToken.toHex()
            val messages = relay.poll("http://fake", recvToken)
            assertEquals(2, messages.size)

            // 5. Provide [M2, M1] in a batch to a FRESH Alice (so she hasn't ratcheted yet).
            // We'll simulate this by using the existing Alice but she is still on B0.

            // Reverse the batch to ensure sorting works.
            val shuffledBatch = listOf(messages[1], messages[0])
            val result = aliceManager.processBatch(aliceId, recvToken, shuffledBatch)
            assertNotNull(result)

            // With corrected sorting, M1 (1.0) is processed first, Alice ratchets to B1.
            // Then M2 (2.0) is processed, Alice ratchets to B2.
            assertEquals(listOf(1.0, 2.0), result.decryptedLocations.map { it.lat }, "Messages should be processed in chronological order")

            val aliceSessionFinal = aliceManager.getFriend(aliceId)!!.session
            assertEquals(2.0, result.decryptedLocations.last().lat)
        }

    /**
     * Verifies the fix for the one-way ratchet desync bug.
     * finalizedTokenTransition must NOT clear the isSendTokenPending flag
     * if the outbox being cleared belongs to an older DH epoch than the current transition.
     */
    @Test
    fun testOneWayRatchetDesyncFix() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val relay = RelayMailboxClient()

            var bobPostShouldFail = false
            val bobMailbox =
                object : MailboxClient by relay {
                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        // Post to relay successfully
                        relay.post(baseUrl, token, payload)
                        // But optionally throw NetworkException to Bob
                        if (bobPostShouldFail) {
                            bobPostShouldFail = false
                            throw NetworkException("Simulated response loss")
                        }
                    }
                }

            val aliceClient = LocationClient("http://fake", aliceManager, relay)
            val bobClient = LocationClient("http://fake", bobManager, bobMailbox)

            // 1. Pairing
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceId = aliceManager.listFriends().first().id
            val bobId = bobManager.listFriends().first().id

            // 2. Alice sends A1, Bob ratchets to B1.
            aliceClient.sendKeepalive(aliceId)
            bobClient.poll()

            val bobSession1 = bobManager.getFriend(bobId)!!.session
            assertTrue(bobSession1.isSendTokenPending)
            val t0 = bobSession1.prevSendToken.toHex()

            // 3. Bob sends M1 (B1) to T0. Response lost.
            bobPostShouldFail = true
            try {
                bobClient.sendLocation(1.1, 1.1)
            } catch (e: NetworkException) {
            }

            val bobEntry2 = bobManager.getFriend(bobId)!!
            assertNotNull(bobEntry2.outbox)
            assertEquals(t0, bobEntry2.outbox!!.token)

            // 4. Alice polls Bob's M1 (B1), ratchets to A2. Then Alice sends A2.
            aliceClient.poll()
            aliceClient.sendLocation(2.2, 2.2)

            // 5. Bob polls A2, ratchets to B2.
            // Use processBatch directly to simulate background poll without clearing outbox.
            val bobRecvToken = bobManager.getFriend(bobId)!!.session.recvToken.toHex()
            val msgsForBob = relay.poll("http://fake", bobRecvToken)
            bobManager.processBatch(bobId, bobRecvToken, msgsForBob)

            val bobSession3 = bobManager.getFriend(bobId)!!.session
            assertTrue(bobSession3.isSendTokenPending, "Bob should still have pending transition for B2")
            val t1 = bobSession3.prevSendToken.toHex()
            assertNotEquals(t0, t1, "Epoch transition token should have advanced to T1")

            // 6. Bob calls poll() which retries outbox (T0).
            // With the FIX, this should NOT clear isSendTokenPending because T0 != T1.
            bobClient.poll()

            val bobEntry4 = bobManager.getFriend(bobId)!!
            assertNull(bobEntry4.outbox, "Outbox T0 should be cleared")

            // If the fix works, Bob's next send should still carry the B2 ratchet (to T1).
            bobClient.sendLocation(3.3, 3.3)

            val aliceUpdates = aliceClient.poll()
            assertTrue(aliceUpdates.any { it.lat == 3.3 }, "Alice should have received Bob's message on T1")
        }

    /**
     * Verifies that consecutive soft failures (header OK, payload fail) correctly 
     * advance the DH ratchet to prevent desync.
     */
    @Test
    fun testConsecutiveSoftFailures() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceManager, relay)
            val bobClient = LocationClient("http://fake", bobManager, relay)

            // 1. Pairing
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceId = aliceManager.listFriends().first().id
            val bobId = bobManager.listFriends().first().id

            // 2. Bob sends 5 messages.
            repeat(5) { i ->
                bobClient.sendLocation(1.0 + i, 2.0)
            }

            val aliceRecvToken = aliceManager.getFriend(aliceId)!!.session.recvToken.toHex()
            val originalBatch = relay.poll("http://fake", aliceRecvToken)
            assertEquals(5, originalBatch.size)

            // 3. Corrupt the ciphertext of all 5 messages.
            val corruptedBatch =
                originalBatch.filterIsInstance<EncryptedMessagePayload>().map { msg ->
                    val corruptedCt = msg.ct.copyOf()
                    if (corruptedCt.isNotEmpty()) {
                        corruptedCt[0] = (corruptedCt[0] + 1).toByte()
                    }
                    msg.copy(ct = corruptedCt)
                }

            // 4. Alice processes the corrupted batch.
            val result = aliceManager.processBatch(aliceId, aliceRecvToken, corruptedBatch)
            assertNotNull(result)

            // 5. Verify results.
            assertEquals(0, result.decryptedLocations.size)
            assertEquals(5, result.failCount)
            assertTrue(result.hadStateUpdate, "Session should have updated despite soft failures")

            // Verify Alice's session advanced (ratcheted 5 times in the receiving chain).
            val aliceSession = aliceManager.getFriend(aliceId)!!.session
            assertEquals(5, aliceSession.recvSeq.toInt())

            // 6. Bob sends a VALID message.
            bobClient.sendLocation(6.0, 2.0)
            val nextBatch = relay.poll("http://fake", aliceRecvToken)
            val nextResult = aliceManager.processBatch(aliceId, aliceRecvToken, nextBatch)
            assertNotNull(nextResult)

            assertEquals(1, nextResult.decryptedLocations.size)
            assertEquals(6.0, nextResult.decryptedLocations.first().lat)
            assertEquals(6, aliceManager.getFriend(aliceId)!!.session.recvSeq.toInt())
        }
}
