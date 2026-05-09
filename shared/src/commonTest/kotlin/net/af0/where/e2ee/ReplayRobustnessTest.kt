package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ReplayRobustnessTest {
    init {
        initializeE2eeTests()
    }

    class MemoryStorage : RawKeyValueStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }

    @Test
    fun testLostAckReplayDoesNotStall() = runTest {
        val aliceManager = E2eeManager(MemoryStorage())
        val bobManager = E2eeManager(MemoryStorage())
        
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
        val aliceClient = LocationClient("http://fake", aliceManager, mailbox)
        val bobClient = LocationClient("http://fake", bobManager, mailbox)

        // 1. Pairing
        val qr = aliceManager.createInvite("Alice")
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr)
        aliceManager.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
        val aliceToBobId = aliceManager.listFriends().find { it.name == "Bob" }!!.id
        val bobToAliceId = bobEntry.id

        // 2. Alice sends A1 (Transition Message)
        aliceClient.sendLocation(1.0, 1.0)
        val t0 = aliceManager.getFriend(aliceToBobId)!!.session.prevSendToken.toHex()
        
        // 3. Bob polls T0. Bob receives A1, ratchets to B1.
        // Bob attempts to ACK T0, but we simulate the ACK is lost (messages stay in mailbox).
        bobClient.poll()
        val bobEntry1 = bobManager.getFriend(bobToAliceId)!!
        // recvSeq=2 because of A1 + automated Keepalive
        assertEquals(2L, bobEntry1.session.recvSeq)
        
        // 4. Bob polls T0 AGAIN (because the messages weren't deleted).
        // Bob should see A1 again. 
        // This is a REPLAY. Alice has already ratcheted away. 
        // Bob should detect it's an old message and NOT throw or get stuck.
        val updates = bobClient.poll()
        assertTrue(updates.isEmpty(), "Replayed location should be ignored")
        
        // Bob's state should still be healthy
        val bobEntry2 = bobManager.getFriend(bobToAliceId)!!
        // recvSeq=2 because of A1 + automated Keepalive
        assertEquals(2L, bobEntry2.session.recvSeq, "Sequence should not advance on replay")
        
        // IMPORTANT: Verify that Bob ACKed the replay batch!
        // First poll: Bob polls T0 (1 message), ratchets, polls T1 (1 message). ackCount = 2.
        // Second poll: Bob polls T1 (same 1 message). Bob detects replay, ACKs.
        assertTrue(mailbox.ackCount >= 3, "Bob should ACK replay batches to clear the mailbox")
        
        // 5. Verify they can still talk. Alice sends A2.
        // Alice already ratcheted to T1.
        aliceClient.sendLocation(2.0, 2.0)
        
        // Bob polls. Bob's recvToken is already T1 (from step 3).
        // pollFriend() starts by polling T1.
        // 
        // If the mailbox still has A1 at T0 (due to lost ACK), Bob doesn't care
        // because he is already polling T1. However, if A1 were NOT a transition
        // message (same token), step 4 verified that Bob would detect the replay,
        // ACK it, and move on.
        //
        // In this case, Alice has sent A2 to T1. Bob polls T1, decrypts A2,
        // and succeeds.
        val updates2 = bobClient.poll()
        assertTrue(updates2.any { it.lat == 2.0 }, "Bob should receive the new message even after a replay stall")
    }
}
