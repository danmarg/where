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
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient()
            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, relay)

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceId = aliceStore.listFriends().first().id
            val bobId = bobStore.listFriends().first().id

            // 1. Bob sends M1 (B1).
            bobClient.sendLocation(1.0, 1.0)

            // 2. Alice sends A1 keepalive. Bob polls and ratchets to B2.
            aliceClient.sendKeepalive(aliceId)
            bobClient.poll()

            // 3. Bob sends M2 (B2).
            bobClient.sendLocation(2.0, 2.0)

            // 4. Now Alice's mailbox at Bob's T_B0 contains [M1 (B1), M2 (B2)].
            val recvToken = aliceStore.getFriend(aliceId)!!.session.recvToken.toHex()
            val messages = relay.poll("http://fake", recvToken)
            assertEquals(2, messages.size)

            // 5. Provide [M2, M1] in a batch to a FRESH Alice (so she hasn't ratcheted yet).
            // We'll simulate this by using the existing Alice but she is still on B0.

            // Reverse the batch to ensure sorting works.
            val shuffledBatch = listOf(messages[1], messages[0])
            val result = aliceStore.processBatch(aliceId, recvToken, shuffledBatch)
            assertNotNull(result)

            // With corrected sorting, M1 (1.0) is processed first, Alice ratchets to B1.
            // Then M2 (2.0) is processed, Alice ratchets to B2.
            assertEquals(listOf(1.0, 2.0), result.decryptedLocations.map { it.lat }, "Messages should be processed in chronological order")

            val aliceSessionFinal = aliceStore.getFriend(aliceId)!!.session
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
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
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

            val aliceClient = LocationClient("http://fake", aliceStore, relay)
            val bobClient = LocationClient("http://fake", bobStore, bobMailbox)

            // 1. Pairing
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceId = aliceStore.listFriends().first().id
            val bobId = bobStore.listFriends().first().id

            // 2. Alice sends A1, Bob ratchets to B1.
            aliceClient.sendKeepalive(aliceId)
            bobClient.poll()

            val bobSession1 = bobStore.getFriend(bobId)!!.session
            assertTrue(bobSession1.isSendTokenPending)
            val t0 = bobSession1.prevSendToken.toHex()

            // 3. Bob sends M1 (B1) to T0. Response lost.
            bobPostShouldFail = true
            try {
                bobClient.sendLocation(1.1, 1.1)
            } catch (e: NetworkException) {
            }

            val bobEntry2 = bobStore.getFriend(bobId)!!
            assertNotNull(bobEntry2.outbox)
            assertEquals(t0, bobEntry2.outbox!!.token)

            // 4. Alice polls Bob's M1 (B1), ratchets to A2. Then Alice sends A2.
            aliceClient.poll()
            aliceClient.sendLocation(2.2, 2.2)

            // 5. Bob polls A2, ratchets to B2.
            // Use processBatch directly to simulate background poll without clearing outbox.
            val bobRecvToken = bobStore.getFriend(bobId)!!.session.recvToken.toHex()
            val msgsForBob = relay.poll("http://fake", bobRecvToken)
            bobStore.processBatch(bobId, bobRecvToken, msgsForBob)

            val bobSession3 = bobStore.getFriend(bobId)!!.session
            assertTrue(bobSession3.isSendTokenPending, "Bob should still have pending transition for B2")
            val t1 = bobSession3.prevSendToken.toHex()
            assertNotEquals(t0, t1, "Epoch transition token should have advanced to T1")

            // 6. Bob calls poll() which retries outbox (T0).
            // With the FIX, this should NOT clear isSendTokenPending because T0 != T1.
            bobClient.poll()

            val bobEntry4 = bobStore.getFriend(bobId)!!
            assertNull(bobEntry4.outbox, "Outbox T0 should be cleared")

            // If the fix works, Bob's next send should still carry the B2 ratchet (to T1).
            bobClient.sendLocation(3.3, 3.3)

            val aliceUpdates = aliceClient.poll()
            assertTrue(aliceUpdates.any { it.lat == 3.3 }, "Alice should have received Bob's message on T1")
        }
}
