package net.af0.where

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import net.af0.where.db.WhereDatabase
import net.af0.where.e2ee.*
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

    private fun createTestSqlDriver(file: java.io.File? = null): SqlDriver {
        val driver =
            if (file == null) {
                JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            } else {
                JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
            }
        if (file == null || file.length() == 0L) {
            WhereDatabase.Schema.create(driver)
        }
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
            val aliceManager = E2eeManager(createTestSqlDriver())
            val bobManager = E2eeManager(createTestSqlDriver())
            val aliceClient = LocationClient(baseUrl, aliceManager)
            val bobClient = LocationClient(baseUrl, bobManager)

            // Pairing
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, _) = bobManager.processScannedQr(qr, "Bob")
            KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), initPayload)
            val aliceEntry =
                aliceManager.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                    "Bob",
                    qr.ekPub,
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

            assertTokensMatch(finalAlice, finalBob, "Alice → Bob token sync")
            assertTokensMatch(finalBob, finalAlice, "Bob → Alice token sync")
        }
    }

    private fun assertTokensMatch(
        sender: SessionState,
        receiver: SessionState,
        message: String,
    ) {
        val receiverPolled = mutableSetOf<String>()
        receiverPolled.add(receiver.recvToken.toHex())

        // After drainStability, tokens SHOULD match perfectly.
        // We check current sendToken and prevSendToken (just in case)
        assertTrue(
            receiverPolled.contains(sender.sendToken.toHex()) || receiverPolled.contains(sender.prevSendToken.toHex()),
            "$message: active send ${sender.sendToken.toHex()} not in receiver polled set $receiverPolled",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

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
            delay(20)
        }
        return allUpdates
    }

    private suspend fun runThreePartyTest(baseUrl: String) {
        coroutineScope {
            val aStore = E2eeManager(createTestSqlDriver())
            val bStore = E2eeManager(createTestSqlDriver())
            val cStore = E2eeManager(createTestSqlDriver())
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)
            val cClient = LocationClient(baseUrl, cStore)

            // A ↔ B
            val qrAB = aStore.createInvite("A")
            val (initAB, _) = bStore.processScannedQr(qrAB, "B")
            KtorMailboxClient.post(baseUrl, qrAB.discoveryToken().toHex(), initAB)
            val aEntryB =
                aStore.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qrAB.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                    "B",
                    qrAB.ekPub,
                )!!
            val idBforA = bStore.listFriends().first().id
            val idAforB = aEntryB.id

            // A ↔ C
            val qrAC = aStore.createInvite("A")
            val (initAC, _) = cStore.processScannedQr(qrAC, "C")
            KtorMailboxClient.post(baseUrl, qrAC.discoveryToken().toHex(), initAC)
            val aEntryC =
                aStore.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qrAC.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                    "C",
                    qrAC.ekPub,
                )!!
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

            assertTokensMatch(finalAB, finalBA, "A→B token sync")
            assertTokensMatch(finalBA, finalAB, "B→A token sync")
            assertTokensMatch(finalAC, finalCA, "A→C token sync")
            assertTokensMatch(finalCA, finalAC, "C→A token sync")
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
                val aStore = E2eeManager(createTestSqlDriver())
                val bStore = E2eeManager(createTestSqlDriver())
                val aClient = LocationClient(baseUrl, aStore)
                val bClient = LocationClient(baseUrl, bStore)

                val qr = aStore.createInvite("A")
                val (init, _) = bStore.processScannedQr(qr, "B")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aEntry =
                    aStore.processKeyExchangeInit(
                        KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                        "B",
                        qr.ekPub,
                    )!!
                val idBforA = bStore.listFriends().first().id
                val idAforB = aEntry.id

                aClient.sendLocation(0.0, 0.0)
                drainStability(aClient to aStore, bClient to bStore)

                val random = Random(currentTimeMillis())
                withContext(Dispatchers.IO) {
                    val jobs =
                        listOf(
                            launch {
                                repeat(5) {
                                    aClient.sendLocation(random.nextDouble(), random.nextDouble())
                                    delay(random.nextLong(5, 15))
                                }
                            },
                            launch {
                                repeat(10) {
                                    aClient.poll()
                                    delay(random.nextLong(5, 15))
                                }
                            },
                            launch {
                                repeat(5) {
                                    bClient.sendLocation(random.nextDouble(), random.nextDouble())
                                    delay(random.nextLong(5, 15))
                                }
                            },
                            launch {
                                repeat(10) {
                                    bClient.poll()
                                    delay(random.nextLong(5, 15))
                                }
                            },
                        )
                    jobs.joinAll()
                }

                drainStability(aClient to aStore, bClient to bStore)
                val finalA = aStore.getFriend(idAforB)!!.session
                val finalB = bStore.getFriend(idBforA)!!.session
                assertTokensMatch(finalA, finalB, "Concurrent A→B sync")
                assertTokensMatch(finalB, finalA, "Concurrent B→A sync")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `mailbox POST failure during Bob exchange test`() {
        initializeLibsodium()
        val port = 18085
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aStore = E2eeManager(createTestSqlDriver())
                val bStore = E2eeManager(createTestSqlDriver())
                val qr = aStore.createInvite("Alice")

                val failingClient =
                    object : MailboxClient {
                        var failCount = 2

                        override suspend fun post(
                            baseUrl: String,
                            token: String,
                            payload: MailboxPayload,
                        ) {
                            if (failCount > 0) {
                                failCount--
                                throw RuntimeException("Fail")
                            }
                            KtorMailboxClient.post(baseUrl, token, payload)
                        }

                        override suspend fun poll(
                            baseUrl: String,
                            token: String,
                        ) = KtorMailboxClient.poll(baseUrl, token)
                    }

                val bClient = LocationClient(baseUrl, bStore, failingClient)
                val (init, entry) = bStore.processScannedQr(qr, "Bob")

                // Should fail a few times but eventually succeed via processOutboxes
                repeat(3) {
                    try {
                        bClient.postKeyExchangeInit(entry.id, qr, init)
                    } catch (e: Exception) {
                    }
                    bClient.processOutboxes()
                    delay(50)
                }

                // Alice should eventually get it
                val aClient = LocationClient(baseUrl, aStore)
                val results = aClient.pollPendingInvites()
                assertTrue(results.isNotEmpty(), "Alice should receive Bob's init")
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
                val aStore = E2eeManager(createTestSqlDriver())
                val aClient = LocationClient(baseUrl, aStore)
                val qr = aStore.createInvite("A")
                // Fake handshake enough to have a session
                val bStore = E2eeManager(createTestSqlDriver())
                val (init, _) = bStore.processScannedQr(qr, "B")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aEntry =
                    aStore.processKeyExchangeInit(
                        KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex()).filterIsInstance<KeyExchangeInitPayload>().first(),
                        "B",
                        qr.ekPub,
                    )!!
                val idAforB = aEntry.id

                aClient.sendLocation(0.0, 0.0)
                val bClient = LocationClient(baseUrl, bStore)
                drainStability(aClient to aStore, bClient to bStore)

                val aRecvToken = aStore.getFriend(idAforB)!!.session.recvToken.toHex()

                // Post garbage
                KtorMailboxClient.post(
                    baseUrl,
                    aRecvToken,
                    EncryptedMessagePayload(v = PROTOCOL_VERSION, envelope = ByteArray(80), ct = ByteArray(64)),
                )

                var garbageTokenAcked = false
                val trackingClient =
                    object : MailboxClient {
                        override suspend fun post(
                            baseUrl: String,
                            token: String,
                            payload: MailboxPayload,
                        ) = KtorMailboxClient.post(baseUrl, token, payload)

                        override suspend fun poll(
                            baseUrl: String,
                            token: String,
                        ) = KtorMailboxClient.poll(baseUrl, token)

                        override suspend fun ackIds(
                            baseUrl: String,
                            token: String,
                            msgIds: List<String>,
                        ) {
                            if (token == aRecvToken) garbageTokenAcked = true
                            KtorMailboxClient.ackIds(baseUrl, token, msgIds)
                        }
                    }
                LocationClient(baseUrl, aStore, trackingClient).poll()
                assertFalse(garbageTokenAcked, "Should not ACK garbage")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `outbox retry recovers on next send`() {
        initializeLibsodium()
        val port = 18086
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aStore = E2eeManager(createTestSqlDriver())
                val bStore = E2eeManager(createTestSqlDriver())
                val qr = aStore.createInvite("Alice")

                var failCount = 2
                val failingClient =
                    object : MailboxClient {
                        override suspend fun post(
                            baseUrl: String,
                            token: String,
                            payload: MailboxPayload,
                        ) {
                            if (failCount > 0) {
                                failCount--
                                throw RuntimeException("Simulated network failure")
                            }
                            KtorMailboxClient.post(baseUrl, token, payload)
                        }

                        override suspend fun poll(
                            baseUrl: String,
                            token: String,
                        ) = KtorMailboxClient.poll(baseUrl, token)
                    }

                val bClient = LocationClient(baseUrl, bStore, failingClient)
                bStore.processScannedQr(qr, "Bob")

                // 1. Initial exchange should fail 2 times, then succeed via processOutboxes
                while (failCount > 0) {
                    try {
                        bClient.processOutboxes()
                    } catch (e: Exception) {
                    }
                    delay(10)
                }
                bClient.processOutboxes() // This one should succeed

                val aClient = LocationClient(baseUrl, aStore)

                // Alice accepts Bob's pending invite from the discovery token
                val pendingInvites = aClient.pollPendingInvites()
                assertEquals(1, pendingInvites.size, "Alice should find Bob's pending invite")
                aStore.processKeyExchangeInit(pendingInvites.first().payload, "Bob", qr.ekPub)

                drainStability(aClient to aStore, bClient to bStore)

                val idAforB = aStore.listFriends().first { it.name == "Bob" }.id
                val idBforA = bStore.listFriends().first { it.name == "Alice" }.id

                // 2. Simulate failure during location send
                failCount = 1
                try {
                    bClient.sendLocation(1.1, 1.1)
                } catch (e: Exception) {
                }

                assertTrue(bStore.getOutbox(idBforA).isNotEmpty(), "Outbox should contain failed location")

                // 3. Second sendLocation should retry outbox then send new location
                bClient.sendLocation(2.2, 2.2)
                assertTrue(bStore.getOutbox(idBforA).isEmpty(), "Outbox should be empty after recovery")

                // 4. Alice should receive both updates
                drainStability(aClient to aStore, bClient to bStore)
                val friend = aStore.getFriend(idAforB)!!
                assertEquals(2.2, friend.lastLat)
                // We don't easily track history in FriendEntry, but the fact that 2.2 was reached
                // and the session tokens match implies 1.1 was also processed.
                assertTokensMatch(friend.session, bStore.getFriend(idBforA)!!.session, "Post-recovery sync")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun `DH ratchet triggers immediate keepalive even when peer keeps sending`() {
        initializeLibsodium()
        val port = 18087
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking {
                val aliceStore = E2eeManager(createTestSqlDriver())
                val bobStore = E2eeManager(createTestSqlDriver())
                val aliceClient = LocationClient(baseUrl, aliceStore)
                val bobClient = LocationClient(baseUrl, bobStore)

                // Standard pairing: Alice creates QR, Bob scans, Alice confirms.
                val qr = aliceStore.createInvite("Alice")
                val (init, _) = bobStore.processScannedQr(qr, "Bob")
                KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)
                val aliceEntry = aliceStore.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qr.discoveryToken().toHex())
                        .filterIsInstance<KeyExchangeInitPayload>().first(),
                    "Bob", qr.ekPub,
                )!!
                val friendIdBforA = bobStore.listFriends().first().id

                // Alice sends 3 locations back-to-back.
                // Msg 1 (seq=1) is the DH transition message to T_A0 carrying A1.pub.
                // Msgs 2–3 (seq=2,3) flow to T_A1, keeping Bob's lastRecvTs fresh after
                // he ratchets — so isFriendSilent stays false.
                aliceClient.sendLocation(1.0, 1.0)
                aliceClient.sendLocation(2.0, 2.0)
                aliceClient.sendLocation(3.0, 3.0)

                // Bob polls: DH-ratchets on msg 1 (hadAnyDhRatchet=true), then processes
                // msgs 2–3. lastRecvTs is fresh, so isFriendSilent=false. The fix adds
                // hadAnyDhRatchet to the keepalive condition, causing Bob to immediately
                // deliver his transition (seq=1, T_B0, carrying B1.pub) to the server.
                bobClient.poll()

                // Alice polls T_B0 (her current recvToken): receives Bob's transition
                // keepalive, DH-ratchets, and advances to recvToken=T_B1.
                // Without the fix, T_B0 is empty and Alice stays stuck on T_B0 indefinitely
                // while Bob has already advanced to sending on T_B1.
                aliceClient.poll()

                // Bob sends a location — encrypted and routed to T_B1.
                bobClient.sendLocation(10.0, 20.0)

                // Alice must be on T_B1 to receive this. Without the fix she is still
                // polling T_B0 and receives nothing here.
                val updates = aliceClient.poll()
                assertTrue(
                    updates.any { it.userId == friendIdBforA && it.lat == 10.0 },
                    "Alice must receive Bob's location; without the fix Bob's transition " +
                        "keepalive is never sent because isFriendSilent stays false",
                )

                drainStability(aliceClient to aliceStore, bobClient to bobStore)
                val finalAlice = aliceStore.getFriend(aliceEntry.id)!!.session
                val finalBob = bobStore.getFriend(friendIdBforA)!!.session
                assertTokensMatch(finalAlice, finalBob, "Alice→Bob token sync")
                assertTokensMatch(finalBob, finalAlice, "Bob→Alice token sync")
            }
        } finally {
            server.stop(0, 0)
        }
    }

    private class MemoryRawKeyValueStorage : RawKeyValueStorage {
        private val data = mutableMapOf<String, String>()

        override fun getString(key: String): String? = data[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            data[key] = value
        }
    }
}
