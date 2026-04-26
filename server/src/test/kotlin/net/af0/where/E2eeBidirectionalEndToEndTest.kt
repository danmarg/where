package net.af0.where

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import net.af0.where.e2ee.*
import net.af0.where.e2ee.PROTOCOL_VERSION
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
 *
 * Can run against localhost (default, starts an embedded Netty server) or a remote
 * server via WHERE_TEST_SERVER_URL env var.
 *
 * Examples:
 *   ./gradlew :server:test --tests E2eeBidirectionalEndToEndTest
 *   WHERE_TEST_SERVER_URL=https://where-api.fly.dev ./gradlew :server:test --tests E2eeBidirectionalEndToEndTest
 */
class E2eeBidirectionalEndToEndTest {
    private fun getServerUrl(): String = System.getenv("WHERE_TEST_SERVER_URL") ?: "http://localhost:18080"

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
            KtorMailboxClient.post(baseUrl, discoveryHex, initPayload)
            println("✓ Bob posted KeyExchangeInit to discovery=$discoveryHex")

            // Alice polls the discovery token and processes the init (as pollPendingInvite does)
            val discoveryMessages = KtorMailboxClient.poll(baseUrl, discoveryHex)
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

            // Verify initial session symmetry.
            // In the new Sealed Envelope protocol, Alice performs an initial DH ratchet
            // rotation (Epoch 1) immediately after handshake. Bob remains in Epoch 0.
            assertContentEquals(aliceSession.prevSendToken, bobSession.recvToken, "Alice send (prev) = Bob recv")
            assertContentEquals(aliceSession.recvToken, bobSession.sendToken, "Alice recv = Bob send")

            // Recv chains should match (both Epoch 0)
            assertContentEquals(aliceSession.recvChainKey, bobSession.sendChainKey, "Alice recv chain = Bob send chain")
            println("✓ Bidirectional tokens verified")
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

            var aliceUpdates = aliceClient.poll()
            var bobLocFromAlice = aliceUpdates.firstOrNull { it.userId == aliceFriendId }
            if (bobLocFromAlice == null) {
                // Alice's first poll might have only consumed the RatchetAck and committed the
                // rotation, switching her recvToken to the new one. Poll again to get the location.
                aliceUpdates = aliceClient.poll()
                bobLocFromAlice = aliceUpdates.firstOrNull { it.userId == aliceFriendId }
            }
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

            val locations =
                listOf(
                    // New York
                    Pair(40.7128, -74.0060),
                    // Paris
                    Pair(48.8566, 2.3522),
                    // Tokyo
                    Pair(35.6762, 139.6503),
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

            // Poll in a loop to ensure everything is settled.
            // Asynchronous messages (rotations, location updates) may take multiple
            // poll cycles to converge across the network.
            suspend fun stabilize() {
                var quietRounds = 0
                while (quietRounds < 3) {
                    val aLen = aliceClient.poll().size
                    val bLen = bobClient.poll().size

                    val aOutbox = aliceStore.listFriends().any { it.outbox != null }
                    val bOutbox = bobStore.listFriends().any { it.outbox != null }

                    if (aLen == 0 && bLen == 0 && !aOutbox && !bOutbox) {
                        quietRounds++
                    } else {
                        quietRounds = 0
                    }
                    delay(50)
                }
            }
            stabilize()
            println("✓ Bob and Alice mailboxes fully drained and state stabilized")
            println()

            // ============================================================================
            // PHASE 8: Verify state integrity
            // ============================================================================
            println("PHASE 8: Verify State Integrity")
            println("─────────────────────────────────────────────────────────────")

            val finalAliceSession = aliceStore.getFriend(aliceFriendId)!!.session
            val finalBobSession = bobStore.getFriend(aliceFriendId)!!.session

            println("Final Session State:")
            println(
                "  Alice: sendToken=${finalAliceSession.sendToken.toHex().take(
                    8,
                )}... (prev=${finalAliceSession.prevSendToken.toHex().take(
                    8,
                )}...) recvToken=${finalAliceSession.recvToken.toHex().take(8)}... pending=${finalAliceSession.isSendTokenPending}",
            )
            println(
                "  Bob:   sendToken=${finalBobSession.sendToken.toHex().take(
                    8,
                )}... (prev=${finalBobSession.prevSendToken.toHex().take(
                    8,
                )}...) recvToken=${finalBobSession.recvToken.toHex().take(8)}... pending=${finalBobSession.isSendTokenPending}",
            )

            // The active send token on one side should match the recv token on the other.
            // If a rotation is pending, the peer is still polling the 'prev' token.
            val aliceActiveSend = if (finalAliceSession.isSendTokenPending) finalAliceSession.prevSendToken else finalAliceSession.sendToken
            assertContentEquals(aliceActiveSend, finalBobSession.recvToken, "Alice active send = Bob recv (final)")

            val bobActiveSend = if (finalBobSession.isSendTokenPending) finalBobSession.prevSendToken else finalBobSession.sendToken
            assertContentEquals(bobActiveSend, finalAliceSession.recvToken, "Bob active send = Alice recv (final)")
            println("✓ Session state integrity verified")
            println("  Alice sendSeq=${finalAliceSession.sendSeq}, recvSeq=${finalAliceSession.recvSeq}")
            println("  Bob   sendSeq=${finalBobSession.sendSeq}, recvSeq=${finalBobSession.recvSeq}")
            println()

            println("════════════════════════════════════════════════════════════")
            println("  ✓ All Tests Passed")
            println("════════════════════════════════════════════════════════════")
        }
    }

    @Test
    fun `mailbox POST failure during Bob exchange`() {
        runBlocking {
            initializeLibsodium()
            val bobStore = E2eeStore(MemoryE2eeStorage())
            val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
            val (initPayload, _) = bobStore.processScannedQr(qr, "Bob")

            // Use a non-existent host to trigger a failure
            val badUrl = "http://localhost:1"
            assertFailsWith<Exception> {
                KtorMailboxClient.post(badUrl, "discovery-token", initPayload)
            }
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

    private suspend fun runThreePartyTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryE2eeStorage()
            val bStorage = MemoryE2eeStorage()
            val cStorage = MemoryE2eeStorage()
            val aStore = E2eeStore(aStorage)
            val bStore = E2eeStore(bStorage)
            val cStore = E2eeStore(cStorage)
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)
            val cClient = LocationClient(baseUrl, cStore)

            println("\n══ THREE-PARTY TEST ══")

            // Pairing: A (QR creator) ↔ B (scanner)
            val qrAB = aStore.createInvite("Hub-A")
            val (initAB, _) = bStore.processScannedQr(qrAB, "B")
            KtorMailboxClient.post(baseUrl, qrAB.discoveryToken().toHex(), initAB)
            val aEntryForB =
                aStore.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qrAB.discoveryToken().toHex())
                        .filterIsInstance<KeyExchangeInitPayload>().first(),
                    "B",
                )!!
            val friendIdAB = aEntryForB.id

            // Pairing: A (QR creator) ↔ C (scanner)
            val qrAC = aStore.createInvite("Hub-A")
            val (initAC, _) = cStore.processScannedQr(qrAC, "C")
            KtorMailboxClient.post(baseUrl, qrAC.discoveryToken().toHex(), initAC)
            val aEntryForC =
                aStore.processKeyExchangeInit(
                    KtorMailboxClient.poll(baseUrl, qrAC.discoveryToken().toHex())
                        .filterIsInstance<KeyExchangeInitPayload>().first(),
                    "C",
                )!!
            val friendIdAC = aEntryForC.id

            suspend fun stabilize() {
                var quiet = 0
                while (quiet < 3) {
                    val aLen = aClient.poll().size
                    val bLen = bClient.poll().size
                    val cLen = cClient.poll().size
                    val pending =
                        aStore.listFriends().any { it.outbox != null } ||
                            bStore.listFriends().any { it.outbox != null } ||
                            cStore.listFriends().any { it.outbox != null }
                    if (aLen == 0 && bLen == 0 && cLen == 0 && !pending) quiet++ else quiet = 0
                    delay(50)
                }
            }

            // Initial flush: A has isSendTokenPending=true for both B and C after handshake.
            // sendLocation posts to prevSendToken for each friend, triggering their DH ratchets.
            println("THREE-PARTY: Initial flush (A→B and A→C)")
            aClient.sendLocation(0.0, 0.0)
            stabilize()
            println("THREE-PARTY: Tokens stabilized after initial flush")

            // ── A → B and A → C ──────────────────────────────────────
            println("THREE-PARTY: Testing A → B and A → C")
            val aLat = 37.7749
            val aLng = -122.4194
            aClient.sendLocation(aLat, aLng)
            delay(400)

            val bPoll = bClient.poll()
            val aLocFromB = bPoll.firstOrNull { it.userId == friendIdAB }
            assertNotNull(aLocFromB, "B should receive A's location (A→B direction)")
            assertEquals(aLat, aLocFromB.lat, 0.0001)
            println("✓ A→B: B received A's location")

            val cPoll = cClient.poll()
            val aLocFromC = cPoll.firstOrNull { it.userId == friendIdAC }
            assertNotNull(aLocFromC, "C should receive A's location (A→C direction)")
            assertEquals(aLat, aLocFromC.lat, 0.0001)
            println("✓ A→C: C received A's location")

            // ── B → A and C → A ──────────────────────────────────────
            println("THREE-PARTY: Testing B → A and C → A")
            val bLat = 51.5074
            val bLng = -0.1278
            val cLat = 35.6762
            val cLng = 139.6503
            bClient.sendLocation(bLat, bLng)
            cClient.sendLocation(cLat, cLng)
            delay(600)

            var aUpdates = aClient.poll()
            if (aUpdates.size < 2) {
                delay(300)
                aUpdates = aUpdates + aClient.poll()
            }

            val bLocFromA = aUpdates.firstOrNull { it.userId == friendIdAB }
            assertNotNull(bLocFromA, "A should receive B's location (B→A direction)")
            assertEquals(bLat, bLocFromA.lat, 0.0001)
            println("✓ B→A: A received B's location")

            val cLocFromA = aUpdates.firstOrNull { it.userId == friendIdAC }
            assertNotNull(cLocFromA, "A should receive C's location (C→A direction)")
            assertEquals(cLat, cLocFromA.lat, 0.0001)
            println("✓ C→A: A received C's location")

            stabilize()

            // ── Token integrity ───────────────────────────────────────
            val finalAB = aStore.getFriend(friendIdAB)!!.session
            val finalBA = bStore.getFriend(friendIdAB)!!.session
            val finalAC = aStore.getFriend(friendIdAC)!!.session
            val finalCA = cStore.getFriend(friendIdAC)!!.session

            val aActiveSendB = if (finalAB.isSendTokenPending) finalAB.prevSendToken else finalAB.sendToken
            val bActiveSend = if (finalBA.isSendTokenPending) finalBA.prevSendToken else finalBA.sendToken
            val aActiveSendC = if (finalAC.isSendTokenPending) finalAC.prevSendToken else finalAC.sendToken
            val cActiveSend = if (finalCA.isSendTokenPending) finalCA.prevSendToken else finalCA.sendToken

            assertContentEquals(aActiveSendB, finalBA.recvToken, "A active send (B-pair) = B recv (final)")
            assertContentEquals(bActiveSend, finalAB.recvToken, "B active send = A recv B-pair (final)")
            assertContentEquals(aActiveSendC, finalCA.recvToken, "A active send (C-pair) = C recv (final)")
            assertContentEquals(cActiveSend, finalAC.recvToken, "C active send = A recv C-pair (final)")

            println("✓ THREE-PARTY: Token integrity verified for both pairs")
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
            runBlocking { runConcurrentSendPollTest(baseUrl) }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private suspend fun runConcurrentSendPollTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryE2eeStorage()
            val bStorage = MemoryE2eeStorage()
            val aStore = E2eeStore(aStorage)
            val bStore = E2eeStore(bStorage)
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)

            println("\n══ CONCURRENT SEND+POLL TEST ══")

            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            val aEntry = aStore.processKeyExchangeInit(init, "B")!!
            val friendId = aEntry.id

            // Initial flush
            aClient.sendLocation(0.0, 0.0)
            delay(300)
            bClient.poll()
            delay(200)
            aClient.poll()

            // Concurrent stress: sendLocation (no pollMutex) races against poll (holds pollMutex).
            // This exercises the interleaving between processOutboxes and sendMessageToFriendInternal
            // for the same friend, which can corrupt isSendTokenPending / sendSeq if not properly
            // serialized. See LocationClient.sendLocation vs poll/processOutboxes.
            val random = Random(System.currentTimeMillis())
            println("CONCURRENT TEST: Running interleaved sends and polls…")
            withContext(Dispatchers.IO) {
                val aSend =
                    launch {
                        repeat(15) {
                            runCatching {
                                aClient.sendLocation(
                                    random.nextDouble(-90.0, 90.0),
                                    random.nextDouble(-180.0, 180.0),
                                )
                            }
                            delay(random.nextLong(10, 80))
                        }
                    }
                val aPoll =
                    launch {
                        repeat(30) {
                            runCatching { aClient.poll() }
                            delay(random.nextLong(15, 60))
                        }
                    }
                val bSend =
                    launch {
                        repeat(15) {
                            runCatching {
                                bClient.sendLocation(
                                    random.nextDouble(-90.0, 90.0),
                                    random.nextDouble(-180.0, 180.0),
                                )
                            }
                            delay(random.nextLong(10, 80))
                        }
                    }
                val bPoll =
                    launch {
                        repeat(30) {
                            runCatching { bClient.poll() }
                            delay(random.nextLong(15, 60))
                        }
                    }
                aSend.join()
                aPoll.join()
                bSend.join()
                bPoll.join()
            }

            // Drain all pending messages
            var quiet = 0
            while (quiet < 5) {
                val aLen = aClient.poll().size
                val bLen = bClient.poll().size
                val pending =
                    aStore.listFriends().any { it.outbox != null } ||
                        bStore.listFriends().any { it.outbox != null }
                if (aLen == 0 && bLen == 0 && !pending) quiet++ else quiet = 0
                delay(100)
            }

            // Token integrity
            val finalA = aStore.getFriend(friendId)!!.session
            val finalB = bStore.getFriend(friendId)!!.session
            val aActiveSend = if (finalA.isSendTokenPending) finalA.prevSendToken else finalA.sendToken
            val bActiveSend = if (finalB.isSendTokenPending) finalB.prevSendToken else finalB.sendToken
            assertContentEquals(aActiveSend, finalB.recvToken, "A active send = B recv (after concurrent stress)")
            assertContentEquals(bActiveSend, finalA.recvToken, "B active send = A recv (after concurrent stress)")

            // Final ping: confirm the channel is still functional end-to-end
            val pingLat = 12.3456
            aClient.sendLocation(pingLat, 0.0)
            delay(500)
            var bFinal = bClient.poll()
            if (bFinal.none { it.userId == friendId }) {
                delay(300)
                bFinal = bFinal + bClient.poll()
            }
            assertNotNull(
                bFinal.firstOrNull { it.userId == friendId && Math.abs(it.lat - pingLat) < 0.001 },
                "A→B channel must still work after concurrent stress (token desync would break this)",
            )

            println("✓ CONCURRENT TEST: Token integrity and channel function verified")
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
            runBlocking { runFinalizeTransitionFailureTest(baseUrl) }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private suspend fun runFinalizeTransitionFailureTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryE2eeStorage()
            val bStorage = MemoryE2eeStorage()
            val aStore = E2eeStore(aStorage)
            val bStore = E2eeStore(bStorage)
            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)

            println("\n══ FINALIZE-TRANSITION KEEPALIVE FAILURE TEST ══")

            // Pair and do initial flush so both sides are stable
            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            val aEntry = aStore.processKeyExchangeInit(init, "B")!!
            val friendId = aEntry.id

            aClient.sendLocation(0.0, 0.0)
            var quiet = 0
            while (quiet < 3) {
                val aLen = aClient.poll().size
                val bLen = bClient.poll().size
                val pending =
                    aStore.listFriends().any { it.outbox != null } ||
                        bStore.listFriends().any { it.outbox != null }
                if (aLen == 0 && bLen == 0 && !pending) quiet++ else quiet = 0
                delay(50)
            }
            println("Initial flush complete — both sides stable")

            // B sends a location; A polls to receive it, triggering A's send-token ratchet.
            bClient.sendLocation(10.0, 20.0)
            delay(200)
            aClient.poll()
            delay(100)

            val aSessionMid = aStore.getFriend(friendId)!!.session
            println("After B→A send: A needsRatchet=${aSessionMid.needsRatchet} isSendTokenPending=${aSessionMid.isSendTokenPending}")

            // isSendTokenPending=true means A's send side ratcheted. A's next send will go to
            // prevSendToken (the transition message), then finalizeTokenTransition tries to post
            // a keepalive to sendToken. We want to fail that keepalive persistently.
            val tokenToFail = aSessionMid.sendToken.toHex()
            println("Will permanently fail POSTs to ${tokenToFail.take(8)}... (A's new sendToken)")

            // Failing client: drops all POSTs to A's new sendToken (simulates a persistent
            // network failure for the finalizeTokenTransition keepalive, and any subsequent
            // outbox retry for that keepalive).
            val failingMailboxClient =
                object : MailboxClient {
                    override suspend fun post(
                        baseUrl: String,
                        token: String,
                        payload: MailboxPayload,
                    ) {
                        if (token == tokenToFail) {
                            println("  [FailingMailbox] dropping POST to ${token.take(8)}...")
                            throw RuntimeException("Injected: POST failure for A's new sendToken")
                        }
                        KtorMailboxClient.post(baseUrl, token, payload)
                    }

                    override suspend fun poll(
                        baseUrl: String,
                        token: String,
                    ): List<MailboxPayload> = KtorMailboxClient.poll(baseUrl, token)
                }
            val aClientFailing = LocationClient(baseUrl, aStore, failingMailboxClient)

            // A sends a location with the failing client.
            // - Main POST goes to prevSendToken (NOT tokenToFail) → succeeds → B will see A's location
            // - finalizeTokenTransition clears isSendTokenPending, then tries keepalive to tokenToFail → FAILS
            // - Exception is swallowed; the keepalive is left in A's outbox (token=tokenToFail)
            runCatching { aClientFailing.sendLocation(40.0, 50.0) }

            val aSessionAfterFailure = aStore.getFriend(friendId)!!.session
            assertFalse(
                aSessionAfterFailure.isSendTokenPending,
                "isSendTokenPending must be false — finalizeTokenTransition clears it before attempting the keepalive POST",
            )
            println("✓ isSendTokenPending=false after keepalive failure")

            // B polls prevSendToken → receives A's location and follows chain to tokenToFail.
            delay(200)
            var bAfterFailure = bClient.poll()
            if (bAfterFailure.none { it.userId == friendId }) {
                delay(300)
                bAfterFailure = bAfterFailure + bClient.poll()
            }
            val aLocFromBAfterFailure = bAfterFailure.firstOrNull { it.userId == friendId }
            assertNotNull(aLocFromBAfterFailure, "B must receive A's location from prevSendToken despite keepalive failure")
            println("✓ B received A's location from prevSendToken (${aLocFromBAfterFailure.lat}, ${aLocFromBAfterFailure.lng})")

            // Now switch to the normal client. The outbox retry in processOutboxes will post the
            // swallowed keepalive to tokenToFail. Then the recovery send also goes to tokenToFail.
            val recoveryLat = 55.123
            aClient.sendLocation(recoveryLat, 30.0)
            delay(300)

            var bFinal = bClient.poll()
            if (bFinal.none { it.userId == friendId && Math.abs(it.lat - recoveryLat) < 0.001 }) {
                delay(300)
                bFinal = bFinal + bClient.poll()
            }
            assertNotNull(
                bFinal.firstOrNull { it.userId == friendId && Math.abs(it.lat - recoveryLat) < 0.001 },
                "B must receive A's recovery location from new sendToken — channel must be functional after keepalive failure",
            )
            println("✓ B received recovery location — channel fully recovered")

            // Final token invariant
            var quietRounds = 0
            while (quietRounds < 3) {
                val aLen = aClient.poll().size
                val bLen = bClient.poll().size
                val pending =
                    aStore.listFriends().any { it.outbox != null } ||
                        bStore.listFriends().any { it.outbox != null }
                if (aLen == 0 && bLen == 0 && !pending) quietRounds++ else quietRounds = 0
                delay(50)
            }
            val finalA = aStore.getFriend(friendId)!!.session
            val finalB = bStore.getFriend(friendId)!!.session
            val aActiveSend = if (finalA.isSendTokenPending) finalA.prevSendToken else finalA.sendToken
            val bActiveSend = if (finalB.isSendTokenPending) finalB.prevSendToken else finalB.sendToken
            assertContentEquals(aActiveSend, finalB.recvToken, "A active send = B recv (after recovery)")
            assertContentEquals(bActiveSend, finalA.recvToken, "B active send = A recv (after recovery)")
            println("✓ Token invariant holds after full recovery")
            println("══ FINALIZE-TRANSITION KEEPALIVE FAILURE TEST PASSED ══")
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
            runBlocking { runNoAckOnAllFailureTest(baseUrl) }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private suspend fun runNoAckOnAllFailureTest(baseUrl: String) {
        coroutineScope {
            val aStorage = MemoryE2eeStorage()
            val bStorage = MemoryE2eeStorage()
            val aStore = E2eeStore(aStorage)
            val bStore = E2eeStore(bStorage)

            // Pair and stabilize
            val qr = aStore.createInvite("A")
            val (init, _) = bStore.processScannedQr(qr, "B")
            val aEntry = aStore.processKeyExchangeInit(init, "B")!!
            val friendId = aEntry.id

            val aClient = LocationClient(baseUrl, aStore)
            val bClient = LocationClient(baseUrl, bStore)
            aClient.sendLocation(0.0, 0.0)
            var quiet = 0
            while (quiet < 3) {
                val aLen = aClient.poll().size
                val bLen = bClient.poll().size
                val pending =
                    aStore.listFriends().any { it.outbox != null } ||
                        bStore.listFriends().any { it.outbox != null }
                if (aLen == 0 && bLen == 0 && !pending) quiet++ else quiet = 0
                delay(50)
            }

            // Post a garbage EncryptedMessagePayload to A's recvToken.
            // The envelope bytes are random noise — AEAD decryption will fail,
            // so processBatch returns anySuccess=false.
            val aRecvToken = aStore.getFriend(friendId)!!.session.recvToken.toHex()
            val garbageMsg =
                EncryptedMessagePayload(
                    v = PROTOCOL_VERSION,
                    // envelope must be ≥ 77 bytes (12 nonce + 16 tag + 49 plaintext) to pass size check
                    envelope = ByteArray(80) { (it * 37 + 13).toByte() },
                    ct = ByteArray(64) { (it * 7 + 5).toByte() },
                )
            KtorMailboxClient.post(baseUrl, aRecvToken, garbageMsg)

            // Confirm the message is on the server before A polls
            assertEquals(1, KtorMailboxClient.poll(baseUrl, aRecvToken).size, "garbage message should be present before poll")

            // Track ACK calls with a counting wrapper
            var ackCallCount = 0
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

                    override suspend fun ack(
                        baseUrl: String,
                        token: String,
                        count: Int,
                    ) {
                        ackCallCount++
                        KtorMailboxClient.ack(baseUrl, token, count)
                    }
                }
            val aClientTracking = LocationClient(baseUrl, aStore, trackingClient)

            // A polls — garbage message fails decryption → anySuccess=false → ACK must be suppressed
            aClientTracking.poll()

            assertEquals(0, ackCallCount, "ACK must NOT be sent when all messages fail decryption")

            // The garbage message must still be on the server (was not ACKed away)
            assertEquals(
                1,
                KtorMailboxClient.poll(baseUrl, aRecvToken).size,
                "garbage message must remain on server after failed-decryption poll",
            )
            println("✓ NO-ACK-ON-FAILURE: ACK correctly suppressed; garbage message preserved on server")
        }
    }

    private class MemoryE2eeStorage : E2eeStorage {
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
