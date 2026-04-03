package net.af0.where

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import net.af0.where.e2ee.*
import kotlin.random.Random
import kotlin.test.*

/**
 * End-to-end bidirectional E2EE test that validates the full production code paths:
 * 1. Key exchange with name verification (via real HTTP mailbox)
 * 2. OPK bundle posting (Bob → Alice)
 * 3. Bidirectional location sharing via LocationClient.sendLocation() + poll()
 * 4. Random timing to catch async/concurrency bugs
 *
 * Uses the same LocationClient / processBatch / sendLocation code as the real apps,
 * so bugs in those paths are caught here before manifesting on device.
 *
 * Can run against localhost (default, starts an embedded Netty server) or a remote
 * server via WHERE_TEST_SERVER_URL env var.
 *
 * Examples:
 *   ./gradlew :server:test --tests E2eeBidirectionalEndToEndTest
 *   WHERE_TEST_SERVER_URL=https://where-api.fly.dev ./gradlew :server:test --tests E2eeBidirectionalEndToEndTest
 */
class E2eeBidirectionalEndToEndTest {
    private fun getServerUrl(): String =
        System.getenv("WHERE_TEST_SERVER_URL") ?: "http://localhost:18080"

    private fun isLocalhost(): Boolean = getServerUrl().contains("localhost")

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
            val random = Random(System.currentTimeMillis())

            val aliceStorage = MemoryE2eeStorage()
            val bobStorage = MemoryE2eeStorage()
            val aliceStore = E2eeStore(aliceStorage)
            val bobStore = E2eeStore(bobStorage)
            val aliceClient = LocationClient(baseUrl, aliceStore)
            val bobClient = LocationClient(baseUrl, bobStore)

            println("\n════════════════════════════════════════════════════════════")
            println("  E2EE Bidirectional End-to-End Test")
            println("  Mode: ${if (isLocalhost()) "Embedded Netty ($baseUrl)" else "Remote ($baseUrl)"}")
            println("════════════════════════════════════════════════════════════\n")

            // ============================================================================
            // PHASE 1: Alice creates invite
            // ============================================================================
            println("PHASE 1: Alice Creates Invite")
            println("─────────────────────────────────────────────────────────────")

            val qr = aliceStore.createInvite("Alice")
            assertNotNull(qr.ekPub, "QR should contain Alice's ephemeral key")
            assertEquals("Alice", qr.suggestedName)
            println("✓ Alice created invite: fingerprint=${qr.fingerprint}")
            println()

            // ============================================================================
            // PHASE 2: Bob joins — real HTTP posts, mirroring the app code
            // ============================================================================
            println("PHASE 2: Bob Joins Using Invite")
            println("─────────────────────────────────────────────────────────────")

            val (initPayload, bobEntry) = bobStore.processScannedQr(qr, "Bob")
            assertEquals("Alice", bobEntry.name)

            val discoveryHex = qr.discoveryToken().toHex()
            // Bob posts KeyExchangeInit to the discovery token (exactly as confirmQrScan does)
            E2eeMailboxClient.post(baseUrl, discoveryHex, initPayload)
            println("✓ Bob posted KeyExchangeInit to discovery=$discoveryHex")

            // Bob posts his initial OPK bundle to his send token (exactly as the apps do)
            bobClient.postOpkBundle(bobEntry.id)
            println("✓ Bob posted OPK bundle to sendToken=${bobEntry.session.sendToken.toHex()}")

            // Alice polls the discovery token and processes the init (as pollPendingInvite does)
            val discoveryMessages = E2eeMailboxClient.poll(baseUrl, discoveryHex)
            val initMsg = discoveryMessages.filterIsInstance<KeyExchangeInitPayload>().firstOrNull()
            assertNotNull(initMsg, "Alice should find Bob's KeyExchangeInit on the discovery token")

            val aliceEntry = aliceStore.processKeyExchangeInit(initMsg, initMsg.suggestedName)
            assertNotNull(aliceEntry, "Alice should process KeyExchangeInit successfully")
            val aliceFriendId = aliceEntry.id
            println("✓ Alice processed KeyExchangeInit, friendId=${aliceFriendId.take(8)}")
            println()

            // Verify both sides have matching, symmetric tokens
            val aliceSession = aliceStore.getFriend(aliceFriendId)!!.session
            val bobSession = bobStore.getFriend(aliceFriendId)!!.session
            assertContentEquals(aliceSession.sendToken, bobSession.recvToken, "Alice send = Bob recv")
            assertContentEquals(aliceSession.recvToken, bobSession.sendToken, "Alice recv = Bob send")
            println("✓ Bidirectional tokens verified")

            // Alice polls once to drain Bob's OPK bundle (mirrors the app's first poll after pairing)
            val afterPairUpdates = aliceClient.poll()
            assertEquals(0, afterPairUpdates.size, "OPK bundle poll should return 0 location updates")
            println("✓ Alice drained Bob's OPK bundle (0 locations, as expected)")
            println()

            // ============================================================================
            // PHASE 3–4: Alice → Bob (via production LocationClient code)
            // ============================================================================
            println("PHASE 3: Alice Sends Location (San Francisco)")
            println("─────────────────────────────────────────────────────────────")

            val aliceLocation = Pair(37.7749, -122.4194)
            aliceClient.sendLocation(aliceLocation.first, aliceLocation.second)
            println("✓ Alice sent location via LocationClient")

            val delayBeforeBobReceive = random.nextLong(200, 800)
            println("  Waiting ${delayBeforeBobReceive}ms before Bob polls…")
            delay(delayBeforeBobReceive)

            println("\nPHASE 4: Bob Polls for Alice's Location")
            println("─────────────────────────────────────────────────────────────")

            val bobUpdates = bobClient.poll()
            val aliceLocFromBob = bobUpdates.firstOrNull { it.userId == aliceFriendId }
            assertNotNull(aliceLocFromBob, "Bob should receive Alice's location via poll()")
            assertEquals(aliceLocation.first, aliceLocFromBob.lat, 0.0001)
            assertEquals(aliceLocation.second, aliceLocFromBob.lng, 0.0001)
            println("✓ Bob received Alice's location: lat=${aliceLocFromBob.lat}, lng=${aliceLocFromBob.lng}")
            println()

            // ============================================================================
            // PHASE 5–6: Bob → Alice (the direction that was broken for iOS→CLI)
            // ============================================================================
            println("PHASE 5: Bob Sends Location (London)")
            println("─────────────────────────────────────────────────────────────")

            val bobLocation = Pair(51.5074, -0.1278)
            bobClient.sendLocation(bobLocation.first, bobLocation.second)
            println("✓ Bob sent location via LocationClient")

            val delayBeforeAliceReceive = random.nextLong(200, 800)
            println("  Waiting ${delayBeforeAliceReceive}ms before Alice polls…")
            delay(delayBeforeAliceReceive)

            println("\nPHASE 6: Alice Polls for Bob's Location")
            println("─────────────────────────────────────────────────────────────")

            val aliceUpdates = aliceClient.poll()
            val bobLocFromAlice = aliceUpdates.firstOrNull { it.userId == aliceFriendId }
            assertNotNull(bobLocFromAlice, "Alice should receive Bob's location via poll()")
            assertEquals(bobLocation.first, bobLocFromAlice.lat, 0.0001)
            assertEquals(bobLocation.second, bobLocFromAlice.lng, 0.0001)
            println("✓ Alice received Bob's location: lat=${bobLocFromAlice.lat}, lng=${bobLocFromAlice.lng}")
            println()

            // ============================================================================
            // PHASE 7: Stress test — interleaved sends from both sides
            // ============================================================================
            println("PHASE 7: Stress Test — Interleaved Sends")
            println("─────────────────────────────────────────────────────────────")

            val locations = listOf(
                Pair(40.7128, -74.0060),  // New York
                Pair(48.8566, 2.3522),    // Paris
                Pair(35.6762, 139.6503),  // Tokyo
            )

            for (i in 0..2) {
                val (lat, lng) = locations[i % locations.size]
                aliceClient.sendLocation(lat, lng)
                println("  Alice sent location $i: ($lat, $lng)")
                delay(random.nextLong(50, 150))
                bobClient.sendLocation(lat + 0.01, lng + 0.01)
                println("  Bob sent location $i: (${lat + 0.01}, ${lng + 0.01})")
                delay(random.nextLong(50, 150))
            }

            // Both sides poll and should see updates
            val finalBobUpdates = bobClient.poll()
            val finalAliceUpdates = aliceClient.poll()
            assertTrue(finalBobUpdates.isNotEmpty(), "Bob should receive Alice's stress-test locations")
            assertTrue(finalAliceUpdates.isNotEmpty(), "Alice should receive Bob's stress-test locations")
            println("✓ Bob received ${finalBobUpdates.size} update(s), Alice received ${finalAliceUpdates.size} update(s)")
            println()

            // ============================================================================
            // PHASE 8: Verify state integrity
            // ============================================================================
            println("PHASE 8: Verify State Integrity")
            println("─────────────────────────────────────────────────────────────")

            val finalAliceSession = aliceStore.getFriend(aliceFriendId)!!.session
            val finalBobSession = bobStore.getFriend(aliceFriendId)!!.session
            assertContentEquals(finalAliceSession.sendToken, finalBobSession.recvToken, "Alice send = Bob recv (final)")
            assertContentEquals(finalAliceSession.recvToken, finalBobSession.sendToken, "Alice recv = Bob send (final)")
            assertEquals(finalAliceSession.epoch, finalBobSession.epoch, "Epochs should match")
            println("✓ Session state integrity verified")
            println("  Final epoch: ${finalAliceSession.epoch}")
            println("  Alice sendSeq=${finalAliceSession.sendSeq}, recvSeq=${finalAliceSession.recvSeq}")
            println("  Bob   sendSeq=${finalBobSession.sendSeq}, recvSeq=${finalBobSession.recvSeq}")
            println()

            println("════════════════════════════════════════════════════════════")
            println("  ✓ All Tests Passed")
            println("════════════════════════════════════════════════════════════")
        }
    }

    private class MemoryE2eeStorage : E2eeStorage {
        private val data = mutableMapOf<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String) { data[key] = value }
    }
}
