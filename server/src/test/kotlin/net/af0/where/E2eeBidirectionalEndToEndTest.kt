package net.af0.where

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import net.af0.where.e2ee.*
import net.af0.where.e2ee.PROTOCOL_VERSION
import kotlin.random.Random
import kotlin.test.*

class E2eeBidirectionalEndToEndTest {
    private fun getServerUrl(): String = System.getenv("WHERE_TEST_SERVER_URL") ?: "http://localhost:18080"

    private fun isLocalhost(): Boolean = getServerUrl().contains("localhost")

    @Test
    fun `bidirectional e2ee location sync with random timing`() {
        LibsodiumInitializer.ensureInitialized()

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
            val aliceManager = E2eeManager(aliceStorage)
            val bobManager = E2eeManager(bobStorage)
            val aliceClient = LocationClient(baseUrl, aliceManager)
            val bobClient = LocationClient(baseUrl, bobManager)

            println("\n════════════════════════════════════════════════════════════")
            println("  E2EE Bidirectional End-to-End Test")
            println("  Mode: ${if (isLocalhost()) "Embedded Netty ($baseUrl)" else "Remote ($baseUrl)"}")
            println("════════════════════════════════════════════════════════════\n")

            println("PHASE 1: Alice Creates Invite")
            val qr = aliceManager.createInvite("Alice")
            assertNotNull(qr.ekPub, "QR should contain Alice's ephemeral key")

            println("PHASE 2: Bob Joins Using Invite")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            val discoveryHex = qr.discoveryToken().toHex()
            KtorMailboxClient.post(baseUrl, discoveryHex, initPayload)

            val pending = aliceClient.pollPendingInvites()
            for (p in pending) {
                aliceManager.processKeyExchangeInit(p.payload, "Bob", p.aliceEkPub)
            }
            val aliceFriends = aliceManager.listFriends()
            assertTrue(aliceFriends.isNotEmpty(), "Alice should have Bob as a friend")
            val aliceFriendId = aliceFriends.first().id

            println("PHASE 3: Alice Sends Location (San Francisco)")
            val aliceLocation = Pair(37.7749, -122.4194)
            aliceClient.sendLocation(aliceLocation.first, aliceLocation.second)

            println("\nPHASE 4: Bob Polls for Alice's Location")
            val allUpdates1 = drainStability(aliceClient to aliceManager, bobClient to bobManager)
            val aliceLocFromBob = allUpdates1.firstOrNull { it.userId == aliceFriendId }
            assertNotNull(aliceLocFromBob, "Bob should receive Alice's location")

            println("PHASE 5: Bob Sends Location (London)")
            val bobLocation = Pair(51.5074, -0.1278)
            bobClient.sendLocation(bobLocation.first, bobLocation.second)

            println("\nPHASE 6: Alice Polls for Bob's Location")
            val allUpdates2 = drainStability(aliceClient to aliceManager, bobClient to bobManager)
            val bobLocFromAlice = allUpdates2.firstOrNull { it.userId == aliceFriendId }
            assertNotNull(bobLocFromAlice, "Alice should receive Bob's location")

            println("PHASE 7: Stress Test — Interleaved Sends")
            val locations = listOf(Pair(40.7128, -74.0060), Pair(48.8566, 2.3522), Pair(35.6762, 139.6503))
            for (i in 0..2) {
                val (lat, lng) = locations[i % locations.size]
                aliceClient.sendLocation(lat, lng)
                bobClient.sendLocation(lat + 0.01, lng + 0.01)
                delay(10)
            }
            drainStability(aliceClient to aliceManager, bobClient to bobManager)

            println("PHASE 8: Verify State Integrity")
            val finalAliceSession = aliceManager.getFriend(aliceFriendId)!!.session
            val finalBobSession = bobManager.getFriend(aliceFriendId)!!.session
            val aliceActiveSend = if (finalAliceSession.isSendTokenPending) finalAliceSession.prevSendToken else finalAliceSession.sendToken
            assertContentEquals(aliceActiveSend, finalBobSession.recvToken, "Alice active send = Bob recv (final)")
            val bobActiveSend = if (finalBobSession.isSendTokenPending) finalBobSession.prevSendToken else finalBobSession.sendToken
            assertContentEquals(bobActiveSend, finalAliceSession.recvToken, "Bob active send = Alice recv (final)")
            println("  Alice sendSeq=${finalAliceSession.sendSeq}, recvSeq=${finalAliceSession.recvSeq}")
            println("  Bob   sendSeq=${finalBobSession.sendSeq}, recvSeq=${finalBobSession.recvSeq}")
        }
    }

    @Test
    fun `three party hub and spoke - all four directions work`() {
        LibsodiumInitializer.ensureInitialized()
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
        timeoutMs: Long = 60_000,
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
            }
            if (!anyActivity) quiet++ else quiet = 0
            kotlinx.coroutines.delay(20)
        }
        return allUpdates
    }

    private suspend fun runThreePartyTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryRawKeyValueStorage()
            val bStorage = MemoryRawKeyValueStorage()
            val cStorage = MemoryRawKeyValueStorage()
            val aStore = E2eeManager(aStorage)
            val bStore = E2eeManager(bStorage)
            val cStore = E2eeManager(cStorage)
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)
            val cClient = LocationClient(baseUrl, cStore)

            val qrAB = aStore.createInvite("Hub-A")
            val (initAB, _) = bStore.processScannedQr(qrAB, "B")
            KtorMailboxClient.post(baseUrl, qrAB.discoveryToken().toHex(), initAB)

            val qrAC = aStore.createInvite("Hub-A")
            val (initAC, _) = cStore.processScannedQr(qrAC, "C")
            KtorMailboxClient.post(baseUrl, qrAC.discoveryToken().toHex(), initAC)

            val pending = aClient.pollPendingInvites()
            for (p in pending) {
                aStore.processKeyExchangeInit(p.payload, p.payload.suggestedName, p.aliceEkPub)
            }
            val aFriends = aStore.listFriends()
            assertEquals(2, aFriends.size)
            val friendIdAB = aFriends.find { it.name == "B" }?.id ?: aFriends[0].id
            val friendIdAC = aFriends.find { it.name == "C" }?.id ?: aFriends[1].id

            aClient.sendLocation(0.0, 0.0)
            drainStability(aClient to aStore, bClient to bStore, cClient to cStore)

            val aLat = 37.7749
            aClient.sendLocation(aLat, -122.4194)
            val allUpdates1 = drainStability(aClient to aStore, bClient to bStore, cClient to cStore)
            assertNotNull(allUpdates1.firstOrNull { it.userId == friendIdAB && it.lat == aLat })
            assertNotNull(allUpdates1.firstOrNull { it.userId == friendIdAC && it.lat == aLat })

            val bLat = 51.5074
            val cLat = 35.6762
            bClient.sendLocation(bLat, -0.1278)
            cClient.sendLocation(cLat, 139.6503)
            val allUpdates2 = drainStability(aClient to aStore, bClient to bStore, cClient to cStore)
            assertNotNull(allUpdates2.firstOrNull { it.userId == friendIdAB && it.lat == bLat })
            assertNotNull(allUpdates2.firstOrNull { it.userId == friendIdAC && it.lat == cLat })
        }
    }

    @Test
    fun `poll does not ACK when all messages fail decryption`() {
        LibsodiumInitializer.ensureInitialized()
        val port = 18084
        val baseUrl = "http://localhost:$port"
        val server = embeddedServer(Netty, port = port) { module(ServerState()) }
        server.start(wait = false)
        try {
            runBlocking { runNoAckOnAllFailureTest(baseUrl) }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private suspend fun runNoAckOnAllFailureTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryRawKeyValueStorage()
            val bStorage = MemoryRawKeyValueStorage()
            val aStore = E2eeManager(aStorage)
            val bStore = E2eeManager(bStorage)
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)

            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            KtorMailboxClient.post(baseUrl, qr.discoveryToken().toHex(), init)

            val pending = aClient.pollPendingInvites()
            val aEntry = aStore.processKeyExchangeInit(pending.first().payload, "B", qr.ekPub)!!
            val friendId = aEntry.id

            aClient.sendLocation(0.0, 0.0)
            drainStability(aClient to aStore, bClient to bStore)

            val aRecvToken = aStore.getFriend(friendId)!!.session.recvToken.toHex()
            val garbageMsg = EncryptedMessagePayload(v = PROTOCOL_VERSION, envelope = ByteArray(80) { it.toByte() }, ct = ByteArray(64) { it.toByte() })
            KtorMailboxClient.post(baseUrl, aRecvToken, garbageMsg)

            var ackCallCount = 0
            val trackingClient = object : MailboxClient {
                override suspend fun post(u: String, t: String, p: MailboxPayload) = KtorMailboxClient.post(u, t, p)
                override suspend fun poll(u: String, t: String) = KtorMailboxClient.poll(u, t)
                override suspend fun ack(u: String, t: String, ids: List<String>) { ackCallCount++; KtorMailboxClient.ack(u, t, ids) }
            }
            val aClientTracking = LocationClient(baseUrl, aStore, trackingClient)
            aClientTracking.poll()
            assertEquals(0, ackCallCount)
            assertEquals(1, KtorMailboxClient.poll(baseUrl, aRecvToken).size)
        }
    }

    private class MemoryRawKeyValueStorage : RawKeyValueStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }
}
