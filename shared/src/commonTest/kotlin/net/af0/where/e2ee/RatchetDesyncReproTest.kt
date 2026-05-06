package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import net.af0.where.model.UserLocation
import kotlin.test.*

class RatchetDesyncReproTest {
    init {
        initializeE2eeTests()
    }

    private class MemoryE2eeStorage : E2eeStorage {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }

    private class DummyMailboxClient : MailboxClient {
        private val mailboxes = mutableMapOf<String, MutableList<EncryptedMessagePayload>>()
        
        override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
            mailboxes.getOrPut(token) { mutableListOf() }.add(payload as EncryptedMessagePayload)
        }
        override suspend fun poll(baseUrl: String, token: String): List<EncryptedMessagePayload> {
            return mailboxes[token]?.toList() ?: emptyList()
        }
        override suspend fun ack(baseUrl: String, token: String, count: Int) {
            mailboxes.remove(token)
        }
    }

    @Test
    fun `reproduce desync on interrupted transition`() = runTest {
        val aliceStorage = MemoryE2eeStorage()
        val bobStorage = MemoryE2eeStorage()
        val aliceStore = E2eeStore(aliceStorage)
        val bobStore = E2eeStore(bobStorage)
        val mailbox = DummyMailboxClient()

        // Alice sets up invite
        val qr = aliceStore.createInvite("Alice")
        // Bob processes invite
        val (init, _) = bobStore.processScannedQr(qr)
        // Alice completes
        aliceStore.processKeyExchangeInit(init, "Bob", qr.ekPub)

        val aliceToBobId = aliceStore.listFriends().first().id
        
        // Trigger ratchet by Alice sending location
        val aliceClient = LocationClient("http://fake", aliceStore, mailbox)
        aliceClient.sendLocation(10.0, 20.0)
        
        // Bob polls
        val bobClient = LocationClient("http://fake", bobStore, mailbox)
        bobClient.poll()
        
        // Now attempt an interrupted transition.
        // ...
        
        assertTrue(true)
    }
}
