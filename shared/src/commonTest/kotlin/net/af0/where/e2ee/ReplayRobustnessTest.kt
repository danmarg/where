package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReplayRobustnessTest {
    init {
        initializeE2eeTests()
    }

    class MemoryStorage : E2eeStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }

    @Test
    fun testLostAckReplayDoesNotStall() = runTest {
        val aliceStore = E2eeStore(MemoryStorage())
        val bobStore = E2eeStore(MemoryStorage())
        
        // Custom mailbox that allows us to simulate a "lost ACK"
        class ReplayMailbox : MailboxClient {
            val messages = mutableMapOf<String, MutableList<MailboxPayload>>()
            var ackCount = 0

            override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
                messages.getOrPut(token) { mutableListOf() }.add(payload)
            }

            override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
                return messages[token]?.toList() ?: emptyList()
            }

            override suspend fun ack(baseUrl: String, token: String, count: Int) {
                ackCount++
                // To simulate LOST ACK, we just don't remove the messages from the map
                // unless we want a successful ACK.
            }
            
            fun clear(token: String) {
                messages.remove(token)
            }
        }

        val mailbox = ReplayMailbox()
        val aliceClient = LocationClient("http://fake", aliceStore, mailbox)
        val bobClient = LocationClient("http://fake", bobStore, mailbox)

        // 1. Pairing
        val qr = aliceStore.createInvite("Alice")
        val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
        aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
        val aliceToBobId = aliceStore.listFriends().find { it.name == "Bob" }!!.id
        val bobToAliceId = bobEntry.id

        // 2. Alice sends A1 (Transition Message)
        aliceClient.sendLocation(1.0, 1.0)
        val t0 = aliceStore.getFriend(aliceToBobId)!!.session.prevSendToken.toHex()
        
        // 3. Bob polls T0. Bob receives A1, ratchets to B1.
        // Bob attempts to ACK T0, but we simulate the ACK is lost (messages stay in mailbox).
        bobClient.poll()
        val bobEntry1 = bobStore.getFriend(bobToAliceId)!!
        // recvSeq=2 because of A1 + automated Keepalive
        assertEquals(2L, bobEntry1.session.recvSeq)
        
        // 4. Bob polls T0 AGAIN (because the messages weren't deleted).
        // Bob should see A1 again. 
        // This is a REPLAY. Alice has already ratcheted away. 
        // Bob should detect it's an old message and NOT throw or get stuck.
        val updates = bobClient.poll()
        assertTrue(updates.isEmpty(), "Replayed location should be ignored")
        
        // Bob's state should still be healthy
        val bobEntry2 = bobStore.getFriend(bobToAliceId)!!
        // recvSeq=2 because of A1 + automated Keepalive
        assertEquals(2L, bobEntry2.session.recvSeq, "Sequence should not advance on replay")
        
        // IMPORTANT: Verify that Bob ACKed the replay batch!
        // First poll: Bob polls T0 (1 message), ratchets, polls T1 (1 message). ackCount = 2.
        // Second poll: Bob polls T1 (same 1 message). Bob detects replay, ACKs. ackCount = 3.
        assertEquals(3, mailbox.ackCount, "Bob should ACK a replay batch to clear the mailbox")
        
        // 5. Verify they can still talk. Alice sends A2.
        // Alice already ratcheted to T1.
        aliceClient.sendLocation(2.0, 2.0)
        
        // Bob polls. He will poll T0 (which still has A1) AND then rotate to T1 (to get A2).
        // Wait, LocationClient only rotates if poll(currentToken) returns success.
        // If poll(T0) returns A1 (which Bob already saw), does Bob rotate?
        
        // In pollFriend:
        // val result = store.processBatch(...)
        // val newToken = updatedFriend.session.recvToken.toHex()
        // if (newToken != currentTokenToPoll) { rotate }
        
        // Since A1 was already processed, processBatch will see it as a failure or skip.
        // If it's a skip, does the session rotate?
        
        val updates2 = bobClient.poll()
        // If the code is robust, Bob should eventually find A2 at T1.
        assertTrue(updates2.any { it.lat == 2.0 }, "Bob should receive the new message even after a replay stall")
    }
}
