package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocationHandshakeTest {
    private val mailbox = MemoryMailboxClient()
    
    private class MemoryMailboxClient : MailboxClient {
        val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()
        override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
            mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
        }
        override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> =
            mailboxes[token] ?: emptyList()
        override suspend fun ackId(baseUrl: String, token: String, msgId: String) {
            mailboxes[token]?.removeAll { it.msgId == msgId }
        }
        override suspend fun ackIds(baseUrl: String, token: String, msgIds: List<String>) {
            mailboxes[token]?.removeAll { it.msgId in msgIds }
        }
    }

    init {
        initializeE2eeTests()
    }

    @Test
    fun testHandshakeMatchingWithMultipleInvites() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = E2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)

        // 1. Alice creates 3 different invites
        val qr1 = aliceManager.createInvite("Invite1")
        val qr2 = aliceManager.createInvite("Invite2")
        val qr3 = aliceManager.createInvite("Invite3")

        // 2. Bob scans the SECOND invite
        val bobDriver = createTestSqlDriver()
        val bobManager = E2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)
        
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr2, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr2, initPayload)

        // 3. Alice polls. She should get exactly 1 handshake and it should match qr2
        val pending = aliceClient.pollPendingInvites()
        assertEquals(1, pending.size, "Alice should see exactly one handshake")
        
        val result = pending[0]
        assertTrue(result.inviteEkPub.contentEquals(qr2.ekPub), "The result should point to the second invite's public key")
        assertFalse(result.inviteEkPub.contentEquals(qr1.ekPub), "The result should NOT point to the first invite")

        // 4. Alice completes the handshake using the key provided in the result
        val aliceEntry = aliceManager.processKeyExchangeInit(result.payload, "Bob", result.inviteEkPub)
        assertNotNull(aliceEntry)
        assertEquals("Bob", aliceEntry.name)
        
        // 5. Verify the session is functional
        val updates = aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
        assertTrue(updates.isEmpty(), "No locations yet")
    }

    @Test
    fun testLocationUpdatesFlowImmediatelyAfterHandshake() = runTest {
        val aliceDriver = createTestSqlDriver()
        val aliceManager = E2eeManager(aliceDriver)
        val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
        val qr = aliceManager.createInvite("Alice")

        val bobDriver = createTestSqlDriver()
        val bobManager = E2eeManager(bobDriver)
        val bobClient = LocationClient("http://localhost", bobManager, mailbox)

        // 1. Bob scans and posts handshake
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

        // 2. Bob tries to send his first location immediately. 
        // This used to fail because postKeyExchangeInit was using the wrong ID to clear the outbox,
        // so the WAL would think the outbox is still full.
        bobClient.sendLocation(37.0, -122.0, emptySet())
        
        // If we reached here without an exception, the WAL allowed the second message.
        // Let's verify Alice can receive it.
        
        // Alice processes handshake
        val pending = aliceClient.pollPendingInvites()
        assertEquals(1, pending.size)
        aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub)
        
        // Alice polls location
        val locations = aliceClient.poll(isForeground = true, pausedFriendIds = emptySet())
        assertEquals(1, locations.size, "Alice should have received Bob's first location update")
        assertEquals(37.0, locations[0].lat)
    }
}
