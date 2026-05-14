package net.af0.where

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import net.af0.where.e2ee.*
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.af0.where.db.WhereDatabase
import kotlin.random.Random
import kotlin.test.*

/**
* End-to-end bidirectional E2EE test that validates the full production code paths:
* 1. Key exchange with name verification (via real HTTP mailbox)
* 2. Bidirectional location sharing via LocationClient.sendLocation() + poll()
* 3. Random timing to catch async/concurrency bugs
*
* Uses the same LocationClient / processBatch / sendLocation code as the real apps,
* so bugs in those paths are caught here before manifesting on device.
*/
class E2eeBidirectionalEndToEndTest {
    private fun getServerUrl(): String = System.getenv("WHERE_TEST_SERVER_URL") ?: "http://localhost:18080"

    private fun isLocalhost(): Boolean = getServerUrl().contains("localhost")

    private fun createTestSqlDriver(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WhereDatabase.Schema.create(driver)
        return driver
    }

    @Test
    fun `bidirectional e2ee location sync with random timing`() {
        initializeLibsodium()

        if (isLocalhost()) {
            val server = embeddedServer(Netty, port = 18080) { module(ServerState()) }
            server.start(wait = false)
            try {
                runBlocking { runBidirectionalTest(getServerUrl()) }
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            }
        } else {
            println("Testing against: ${getServerUrl()}")
            runBlocking { runBidirectionalTest(getServerUrl()) }
        }
    }

    private suspend fun runBidirectionalTest(baseUrl: String) {
        coroutineScope {
            val aliceStorage = MemoryRawKeyValueStorage()
            val bobStorage = MemoryRawKeyValueStorage()
            val aliceManager = E2eeManager(aliceStorage, createTestSqlDriver())
            val bobManager = E2eeManager(bobStorage, createTestSqlDriver())
            val aliceClient = LocationClient(baseUrl, aliceManager)
            val bobClient = LocationClient(baseUrl, bobManager)

            // Pairing
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr, "Bob")
            KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), initPayload)
            val aliceEntry = aliceManager.processKeyExchangeInit(
                KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                "Bob",
                qr.ekPub
            )!!
            val idBforA = bobManager.listFriends().first().id
            val idAforB = aliceEntry.id

            // Alice sends location
            aliceClient.sendLocation(37.7749, -122.4194)
            drainStability(aliceClient to aliceManager, bobClient to bobManager)

            // Bob sends location
            bobClient.sendLocation(51.5074, -0.1278)
            drainStability(aliceClient to aliceManager, bobClient to bobManager)

            // Final state check
            val finalAlice = aliceManager.getFriend(idAforB)!!.session
            val finalBob = bobManager.getFriend(idBforA)!!.session
            
            val aActiveSend = if (finalAlice.isSendTokenPending) finalAlice.prevSendToken else finalAlice.sendToken
            val bActiveSend = if (finalBob.isSendTokenPending) finalBob.prevSendToken else finalBob.sendToken
            
            assertContentEquals(aActiveSend, finalBob.recvToken, "Alice active send = Bob recv")
            assertContentEquals(bActiveSend, finalAlice.recvToken, "Bob active send = Alice recv")
        }
    }

    @Test
    fun `three party hub and spoke - all four directions work`() {
        initializeLibsodium()

        val port = 18081
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking { runThreePartyTest(baseUrl) }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private suspend fun drainStability(
        vararg nodes: Pair<LocationClient, E2eeManager>,
        timeoutMs: Long = 30_000,
    ): List<net.af0.where.model.UserLocation> {
        val start = System.currentTimeMillis()
        var quiet = 0
        val allUpdates = mutableListOf<net.af0.where.model.UserLocation>()
        while (quiet < 5) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw Exception("drainStability timed out after ${timeoutMs}ms")
            }

            var anyActivity = false
            for ((client, store) in nodes) {
                val updates = client.poll()
                if (updates.isNotEmpty()) {
                    allUpdates.addAll(updates)
                    anyActivity = true
                }
                if (store.listFriends().any { store.getOutbox(it.id).isNotEmpty() }) {
                    anyActivity = true
                }
            }

            if (!anyActivity) quiet++ else quiet = 0
            delay(50)
        }
        return allUpdates
    }

    private suspend fun runThreePartyTest(baseUrl: String) {
        coroutineScope {
            val aStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
            val bStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
            val cStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)
            val cClient = LocationClient(baseUrl, cStore)

            // A ↔ B
            val qrAB = aStore.createInvite("A")
            val (initAB, _) = bStore.processScannedQr(qrAB, "B")
            KtorMailboxClient.post(baseUrl, qrAB.discoveryToken().toHex(), initAB)
            val aEntryB = aStore.processKeyExchangeInit(KtorMailboxClient.poll(baseUrl, qrAB.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(), "B", qrAB.ekPub)!!
            val idBforA = bStore.listFriends().first().id
            val idAforB = aEntryB.id

            // A ↔ C
            val qrAC = aStore.createInvite("A")
            val (initAC, _) = cStore.processScannedQr(qrAC, "C")
            KtorMailboxClient.post(baseUrl, qrAC.discoveryToken().toHex(), initAC)
            val aEntryC = aStore.processKeyExchangeInit(KtorMailboxClient.poll(baseUrl, qrAC.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(), "C", qrAC.ekPub)!!
            val idCforA = cStore.listFriends().first().id
            val idAforC = aEntryC.id

            // Stabilize
            aClient.sendLocation(0.0, 0.0)
            drainStability(aClient to aStore, bClient to bStore, cClient to cStore)

            // A → B and A → C
            aClient.sendLocation(1.1, 2.2)
            val updates1 = drainStability(aClient to aStore, bClient to bStore, cClient to cStore)
            assertTrue(updates1.any { it.userId == idBforA && it.lat == 1.1 }, "B received A")
            assertTrue(updates1.any { it.userId == idCforA && it.lat == 1.1 }, "C received A")

            // B → A and C → A
            bClient.sendLocation(3.3, 4.4)
            cClient.sendLocation(5.5, 6.6)
            val updates2 = drainStability(aClient to aStore, bClient to bStore, cClient to cStore)
            assertTrue(updates2.any { it.userId == idAforB && it.lat == 3.3 }, "A received B")
            assertTrue(updates2.any { it.userId == idAforC && it.lat == 5.5 }, "A received C")

            // Token integrity
            val finalAB = aStore.getFriend(idAforB)!!.session
            val finalBA = bStore.getFriend(idBforA)!!.session
            val finalAC = aStore.getFriend(idAforC)!!.session
            val finalCA = cStore.getFriend(idCforA)!!.session

            val aActiveSendB = if (finalAB.isSendTokenPending) finalAB.prevSendToken else finalAB.sendToken
            val bActiveSend = if (finalBA.isSendTokenPending) finalBA.prevSendToken else finalBA.sendToken
            val aActiveSendC = if (finalAC.isSendTokenPending) finalAC.prevSendToken else finalAC.sendToken
            val cActiveSend = if (finalCA.isSendTokenPending) finalCA.prevSendToken else finalCA.sendToken

            assertContentEquals(aActiveSendB, finalBA.recvToken, "A→B token sync")
            assertContentEquals(bActiveSend, finalAB.recvToken, "B→A token sync")
            assertContentEquals(aActiveSendC, finalCA.recvToken, "A→C token sync")
            assertContentEquals(cActiveSend, finalAC.recvToken, "C→A token sync")
        }
    }

    @Test
    fun `concurrent sendLocation and poll do not desync session tokens`() {
        initializeLibsodium()
        val port = 18082
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val bStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val aClient = LocationClient(baseUrl, aStore)
                val bClient = LocationClient(baseUrl, bStore)

                val qr = aStore.createInvite("A")
                val (init, _) = bStore.processScannedQr(qr, "B")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aEntry = aStore.processKeyExchangeInit(KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(), "B", qr.ekPub)!!
                val idBforA = bStore.listFriends().first().id
                val idAforB = aEntry.id

                aClient.sendLocation(0.0, 0.0)
                drainStability(aClient to aStore, bClient to bStore)

                val random = Random(currentTimeMillis())
                withContext(Dispatchers.IO) {
                    val jobs = listOf(
                        launch { repeat(5) { aClient.sendLocation(random.nextDouble(), random.nextDouble()); delay(random.nextLong(5, 15)) } },
                        launch { repeat(10) { aClient.poll(); delay(random.nextLong(5, 15)) } },
                        launch { repeat(5) { bClient.sendLocation(random.nextDouble(), random.nextDouble()); delay(random.nextLong(5, 15)) } },
                        launch { repeat(10) { bClient.poll(); delay(random.nextLong(5, 15)) } }
                    )
                    jobs.joinAll()
                }

                drainStability(aClient to aStore, bClient to bStore)
                val finalA = aStore.getFriend(idAforB)!!.session
                val finalB = bStore.getFriend(idBforA)!!.session
                val aActiveSend = if (finalA.isSendTokenPending) finalA.prevSendToken else finalA.sendToken
                val bActiveSend = if (finalB.isSendTokenPending) finalB.prevSendToken else finalB.sendToken
                assertContentEquals(aActiveSend, finalB.recvToken, "Concurrent A→B sync")
                assertContentEquals(bActiveSend, finalA.recvToken, "Concurrent B→A sync")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `finalizeTokenTransition keepalive failure recovers on next send`() {
        initializeLibsodium()
        val port = 18083
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val bStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val qr = aStore.createInvite("A")
                val (init, _) = bStore.processScannedQr(qr, "B")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aEntry = aStore.processKeyExchangeInit(KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(), "B", qr.ekPub)!!
                val idBforA = bStore.listFriends().first().id
                val idAforB = aEntry.id

                val aClient = LocationClient(baseUrl, aStore)
                val bClient = LocationClient(baseUrl, bStore)
                aClient.sendLocation(0.0, 0.0)
                drainStability(aClient to aStore, bClient to bStore)

                // B sends, Alice polls, triggering Alice ratchet
                bClient.sendLocation(1.0, 1.0)
                delay(50)
                aClient.poll()
                
                val aSessionMid = aStore.getFriend(idAforB)!!.session
                assertTrue(aSessionMid.isSendTokenPending, "Alice should be pending")
                val tokenToFail = aSessionMid.sendToken.toHex()

                val failingClient = object : MailboxClient {
                    override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
                        if (token == tokenToFail) throw RuntimeException("Fail")
                        KtorMailboxClient.post(baseUrl, token, payload)
                    }
                    override suspend fun poll(baseUrl: String, token: String) = KtorMailboxClient.poll(baseUrl, token)
                }
                val aClientFailing = LocationClient(baseUrl, aStore, failingClient)

                // Alice sends location. Main post to prevSendToken succeeds. Keepalive to sendToken fails.
                aClientFailing.sendLocation(2.2, 2.2)
                
                assertFalse(aStore.getFriend(idAforB)!!.session.isSendTokenPending, "Cleared after first send")
                
                // Bob polls prevSendToken, gets location, ratchets
                drainStability(aClient to aStore, bClient to bStore)
                
                // Bob sends ACK back
                bClient.sendLocation(3.3, 3.3)
                drainStability(aClient to aStore, bClient to bStore)

                assertFalse(aStore.getFriend(idAforB)!!.session.isSendTokenPending, "Pending cleared after ACK")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `poll does not ACK when all messages fail decryption`() {
        initializeLibsodium()
        val port = 18084
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val aClient = LocationClient(baseUrl, aStore)
                val qr = aStore.createInvite("A")
                // Fake handshake enough to have a session
                val bStore = E2eeManager(MemoryRawKeyValueStorage(), createTestSqlDriver())
                val (init, _) = bStore.processScannedQr(qr, "B")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aEntry = aStore.processKeyExchangeInit(KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(), "B", qr.ekPub)!!
                val idAforB = aEntry.id
                
                aClient.sendLocation(0.0, 0.0)
                val bClient = LocationClient(baseUrl, bStore)
                drainStability(aClient to aStore, bClient to bStore)

                val aRecvToken = aStore.getFriend(idAforB)!!.session.recvToken.toHex()
                
                // Post garbage
                KtorMailboxClient.post(baseUrl, aRecvToken, EncryptedMessagePayload(v=PROTOCOL_VERSION, envelope=ByteArray(80), ct=ByteArray(64)))
                
                var acked = false
                val trackingClient = object : MailboxClient {
                    override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) = KtorMailboxClient.post(baseUrl, token, payload)
                    override suspend fun poll(baseUrl: String, token: String) = KtorMailboxClient.poll(baseUrl, token)
                    override suspend fun ackIds(baseUrl: String, token: String, msgIds: List<String>) { acked = true; KtorMailboxClient.ackIds(baseUrl, token, msgIds) }
                }
                LocationClient(baseUrl, aStore, trackingClient).poll()
                assertFalse(acked, "Should not ACK garbage")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private class MemoryRawKeyValueStorage : RawKeyValueStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }
}
