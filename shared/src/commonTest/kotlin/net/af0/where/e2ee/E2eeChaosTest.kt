package net.af0.where.e2ee

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class E2eeChaosTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testProcessBatchResilienceToStorageFailure() =
        runTest {
            val aliceManager = E2eeManager(MemoryStorage())
            val bobStorage = MemoryStorage()
            val chaosBobStorage = ChaosStorage(bobStorage)
            val bobManager = E2eeManager(chaosBobStorage)

            // Pair
            val qr = aliceManager.createInvite("Alice")
            val (init, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)

            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobManager.listFriends().first().id

            // Alice sends a message
            val (sess, msg) = Session.encryptMessage(aliceManager.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceManager.updateSession(aliceToBobId, sess)

            // Bob's storage fails during batch processing
            chaosBobStorage.failNextWrite = true
            val bobToken = bobManager.getFriend(bobToAliceId)!!.session.recvToken.toHex()
            try {
                bobManager.processBatch(bobToAliceId, bobToken, listOf(msg))
                fail("Should have thrown storage exception")
            } catch (_: Exception) {
            }

            // Invariant (§5.4): if storage fails, Bob's memory state and DB state must
            // remain consistent (no partial updates).
            val bobEntry = bobManager.getFriend(bobToAliceId)!!
            assertEquals(0, bobEntry.session.recvSeq, "Bob's state should not have advanced after storage failure")

            // Alice sends another message
            val (sess2, msg2) = Session.encryptMessage(aliceManager.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceManager.updateSession(aliceToBobId, sess2)

            // Bob retries with working storage — both messages should succeed
            chaosBobStorage.failNextWrite = false
            val result = bobManager.processBatch(bobToAliceId, bobToken, listOf(msg, msg2))
            assertNotNull(result)
            assertTrue(result.anySuccess)
            assertEquals(2, bobManager.getFriend(bobToAliceId)!!.session.recvSeq)
        }

    @Test
    fun testRecoveryAfterNetworkFailureOnTransitionFlush() =
        runTest {
            val relay = RelayMailboxClient()
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceManager, aliceChaosMailbox)
            val bobClient = LocationClient("http://fake", bobManager, relay)

            // 1. Pair
            val qr = aliceManager.createInvite("Alice")
            val (init, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val aliceToBobId = aliceManager.listFriends().first().id

            // 2. Bob sends a message to Alice (Triggering DH ratchet on Alice's side)
            bobClient.sendLocation(1.0, 1.0)
            aliceClient.poll()

            // 3. Alice now has needsRatchet=true. She sends a message (transition message).
            // We simulate a network failure AFTER it's posted to the server but BEFORE
            // Alice receives the response.
            aliceChaosMailbox.stealthPost = true
            try {
                aliceClient.sendLocation(2.0, 2.0)
            } catch (_: NetworkException) {
            }

            val aliceMid = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceMid.session.isSendTokenPending, "Alice should be pending transition")
            assertNotNull(aliceMid.outbox, "Alice should have outbox after post 'failure'")

            // 4. Bob polls and receives the transition message
            val bobUpdates = bobClient.poll()
            assertEquals(1, bobUpdates.size)
            assertEquals(2.0, bobUpdates[0].lat)

            // 5. Alice runs recovery (processOutboxes)
            // It will retry the post, get a 404 (because Bob already consumed it),
            // but finalizeTokenTransition should still run and clear the flag.
            aliceChaosMailbox.stealthPost = false
            aliceClient.poll()

            val aliceFinal = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(aliceFinal.session.isSendTokenPending, "isSendTokenPending should be cleared after recovery")
            assertNull(aliceFinal.outbox, "Outbox should be cleared")

            // 6. Alice sends another message — should use new sendToken and succeed
            aliceClient.sendLocation(3.0, 3.0)
            val bobUpdates2 = bobClient.poll()
            assertEquals(1, bobUpdates2.size)
            assertEquals(3.0, bobUpdates2[0].lat)
        }

    @Test
    fun testRecoveryAfterMailboxExpirationDuringTransition() =
        runTest {
            val relay = RelayMailboxClient()
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceManager, aliceChaosMailbox)
            val bobClient = LocationClient("http://fake", bobManager, relay)

            // 1. Pair
            val qr = aliceManager.createInvite("Alice")
            val (init, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val aliceToBobId = aliceManager.listFriends().first().id

            // 2. Bob sends a message (Alice ratchets)
            bobClient.sendLocation(1.0, 1.0)
            aliceClient.poll()

            // 3. Alice sends transition message, but the OLD token has expired on server
            aliceChaosMailbox.expireMailboxProbability = 1.0
            aliceChaosMailbox.expireMailboxStatusCode = 404
            try {
                aliceClient.sendLocation(2.0, 2.0)
            } catch (_: Exception) {
            }

            val aliceMid = aliceManager.getFriend(aliceToBobId)!!
            assertTrue(aliceMid.session.isSendTokenPending, "Alice should be pending")
            assertNotNull(aliceMid.outbox, "Alice should have outbox")

            // 4. Trigger recovery
            aliceChaosMailbox.expireMailboxProbability = 0.0
            aliceChaosMailbox.resetExpirations()
            aliceClient.poll()
            val aliceFinal = aliceManager.getFriend(aliceToBobId)!!
            assertFalse(
                aliceFinal.session.isSendTokenPending,
                "isSendTokenPending should be cleared after recovery from 404 on transition token",
            )
            assertNull(aliceFinal.outbox, "Outbox should be cleared")
        }

    @Test
    fun testMultiFriendChaos() = runMultiFriendChaos(iterations = 20, minChaos = 0.0, maxChaos = 0.1)

    @Test
    @Ignore
    fun testMultiFriendChaosHighStress() = runMultiFriendChaos(iterations = 50, minChaos = 0.3, maxChaos = 0.5)

    private fun runMultiFriendChaos(
        iterations: Int,
        minChaos: Double,
        maxChaos: Double,
    ) =
        runTest {
            initializeE2eeTests()
            val relay = RelayMailboxClient(rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val clock = ChaosTimeProvider()
            TimeSource.setProvider(clock)

            class Node(val name: String) {
                val baseStorage = MemoryStorage()
                val chaosStorage = ChaosStorage(baseStorage)
                var store = E2eeManager(chaosStorage)
                val chaosMailbox = ChaosMailboxClient(relay)
                var client = LocationClient("http://fake", store, chaosMailbox)
                val receivedLocations = mutableMapOf<String, MutableSet<Int>>()

                fun restart() {
                    store = E2eeManager(chaosStorage)
                    client = LocationClient("http://fake", store, chaosMailbox)
                }

                fun setChaos(p: Double) {
                    chaosStorage.failWriteProbability = p
                    chaosMailbox.failPostProbability = p
                    chaosMailbox.failPollProbability = p
                    chaosMailbox.killProbability = p * 0.5 // High kill probability for stress
                    chaosMailbox.corruptPayloadProbability = p * 0.3
                    chaosMailbox.reorderProbability = p * 0.3
                    chaosMailbox.dropProbability = p * 0.2
                    chaosMailbox.stealthDropProbability = p * 0.1
                    chaosMailbox.maxLatencyMs = (p * 100).toLong()
                }
            }

            val alice = Node("Alice")
            val bob = Node("Bob")
            val charlie = Node("Charlie")
            val nodes = listOf(alice, bob, charlie)

            // 1. Establish Triangle Friendships (No Chaos)
            fun pair(
                a: Node,
                b: Node,
            ) {
                runBlocking {
                    val qr = a.store.createInvite(a.name)
                    val (init, _) = b.store.processScannedQr(qr)
                    a.store.processKeyExchangeInit(init, b.name, qr.ekPub)
                }
            }
            pair(alice, bob)
            pair(alice, charlie)
            pair(bob, charlie)

            val aliceToBobId = runBlocking { alice.store.listFriends().find { it.name == "Bob" }!!.id }
            val aliceToCharlieId = runBlocking { alice.store.listFriends().find { it.name == "Charlie" }!!.id }
            val bobToAliceId = runBlocking { bob.store.listFriends().find { it.name == "Alice" }!!.id }
            val bobToCharlieId = runBlocking { bob.store.listFriends().find { it.name == "Charlie" }!!.id }
            val charlieToAliceId = runBlocking { charlie.store.listFriends().find { it.name == "Alice" }!!.id }
            val charlieToBobId = runBlocking { charlie.store.listFriends().find { it.name == "Bob" }!!.id }

            // 2. Chaos Phase
            println("Starting Multi-Friend Chaos Phase (min=$minChaos, max=$maxChaos)...")
            repeat(iterations) { i ->
                nodes.forEach { node ->
                    node.setChaos(Random.nextDouble(minChaos, maxChaos))
                    if (Random.nextDouble() < 0.01) node.restart()

                    // Send to all friends occasionally
                    if (Random.nextDouble() < 0.3) {
                        try {
                            node.client.sendLocation(i.toDouble(), i.toDouble())
                        } catch (e: ProcessKilledException) {
                            node.restart()
                        } catch (_: Exception) {
                        }
                    }

                    // Poll
                    try {
                        val updates = node.client.poll()
                        updates.forEach { update ->
                            node.receivedLocations.getOrPut(update.userId) { mutableSetOf() }.add(update.lat.toInt())
                        }
                    } catch (e: ProcessKilledException) {
                        node.restart()
                    } catch (_: Exception) {
                    }
                }

                if (Random.nextDouble() < 0.02) {
                    clock.addOffset(Random.nextLong(-200, 200))
                }
                if (Random.nextDouble() < 0.001) {
                    nodes.forEach {
                        it.chaosMailbox.expireMailboxProbability = 0.01
                        if (Random.nextDouble() < 0.5) it.chaosMailbox.resetExpirations()
                    }
                }
            }

            // 3. Recovery Phase
            println("Starting Multi-Friend Recovery Phase...")
            TimeSource.setProvider(DefaultTimeProvider)
            nodes.forEach { node ->
                node.setChaos(0.0)
                node.chaosMailbox.expireMailboxProbability = 0.0
                node.chaosMailbox.resetExpirations()
                node.restart()
            }

            repeat(500) {
                nodes.forEach { node ->
                    try {
                        val updates = node.client.poll()
                        updates.forEach { update ->
                            node.receivedLocations.getOrPut(update.userId) { mutableSetOf() }.add(update.lat.toInt())
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            // Send recovery signal
            nodes.forEach { node ->
                try {
                    node.client.sendLocation(999.0, 999.0)
                } catch (_: Exception) {
                }
            }
            repeat(500) {
                nodes.forEach { node ->
                    try {
                        val updates = node.client.poll()
                        updates.forEach { update ->
                            node.receivedLocations.getOrPut(update.userId) { mutableSetOf() }.add(update.lat.toInt())
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // Verification: Everyone should have eventually received at least some locations from everyone else,
            // and crucially, the final recovery messages should arrive.
            fun verify(
                receiver: Node,
                senderId: String,
                senderName: String,
            ) {
                val received = receiver.receivedLocations[senderId] ?: emptySet()
                if (!received.contains(999)) {
                    val entry = runBlocking { receiver.store.getFriend(senderId)!! }
                    println("VERIFY FAIL: ${receiver.name} did not receive 999 from $senderName")
                    println(
                        "  Session with $senderName: sendSeq=${entry.session.sendSeq} recvSeq=${entry.session.recvSeq} pending=${entry.session.isSendTokenPending} outbox=${entry.outbox?.token?.take(
                            8,
                        )}",
                    )
                    println(
                        "  Relay state for tokens: recvToken=${entry.session.recvToken.toHex().take(
                            8,
                        )} inboxSize=${relay.inbox[entry.session.recvToken.toHex()]?.size ?: 0}",
                    )
                }
                assertTrue(received.contains(999), "${receiver.name} should have received final message from $senderName")
            }

            verify(alice, aliceToBobId, "Bob")
            verify(alice, aliceToCharlieId, "Charlie")
            verify(bob, bobToAliceId, "Alice")
            verify(bob, bobToCharlieId, "Charlie")
            verify(charlie, charlieToAliceId, "Alice")
            verify(charlie, charlieToBobId, "Bob")
        }

    @Test
    fun testCorruptPayloadAdvancement() =
        runTest {
            initializeE2eeTests()
            val relay = RelayMailboxClient()
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val bobChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceManager, relay)
            val bobClient = LocationClient("http://fake", bobManager, bobChaosMailbox)

            // 1. Pair
            val qr = aliceManager.createInvite("Alice")
            val (init, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val bobToAliceId = bobManager.listFriends().first().id
            val aliceToBobId = aliceManager.listFriends().first().id

            // 2. Alice sends a message that will be corrupted (Payload only).
            // This message triggers an Epoch transition/Ratchet on Alice's side.
            aliceClient.sendLocation(1.1, 1.1)
            bobChaosMailbox.corruptNextPayloadOnly = true

            // 3. Bob polls. The header is valid, so he should ratchet forward even if CT decryption fails.
            val result = bobClient.poll()
            assertTrue(result.isEmpty(), "Decryption should fail due to corruption")

            // The session ratchet is based on header processing.
            // RecvSeq should increment.
            val bobEntry = bobManager.getFriend(bobToAliceId)!!
            assertTrue(bobEntry.session.recvSeq > 1, "Session should have ratcheted forward")

            // 4. Alice sends another message. This one should succeed.
            aliceClient.sendLocation(2.2, 2.2)
            val result2 = bobClient.poll()
            assertEquals(1, result2.size)
            assertEquals(2.2, result2[0].lat)
        }

    @Test
    fun testQueueFillDeadlock() =
        runTest {
            initializeE2eeTests()
            // Small queue and drain size to make the deadlock trigger in a few iterations.
            val relay = RelayMailboxClient(maxQueueDepth = 5, maxDrainSize = 3, rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val aliceManager = E2eeManager(MemoryStorage())
            val bobManager = E2eeManager(MemoryStorage())
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val bobChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceManager, aliceChaosMailbox)
            val bobClient = LocationClient("http://fake", bobManager, bobChaosMailbox)

            // 1. Pair
            val qr = aliceManager.createInvite("Alice")
            val (init, _) = bobManager.processScannedQr(qr)
            aliceManager.processKeyExchangeInit(init, "Bob", qr.ekPub)
            val aliceToBobId = aliceManager.listFriends().first().id
            val bobToAliceId = bobManager.listFriends().first().id

            // 2. Alice fills Bob's inbox (T_B0) manually to maxQueueDepth.
            val tB0 = bobManager.getFriend(bobToAliceId)!!.session.recvToken.toHex()
            val (aliceSess1, aliceMsg1) = Session.encryptMessage(aliceManager.getFriend(aliceToBobId)!!.session, MessagePlaintext.Keepalive())
            aliceManager.updateSession(aliceToBobId, aliceSess1)
            repeat(5) {
                relay.post("http://fake", tB0, aliceMsg1)
            }
            assertEquals(5, relay.inbox[tB0]?.size, "T_B0 should be full")

            // 3. Bob ratchets (getting Alice's messages)
            bobClient.poll()
            
            // 4. Bob now tries to send a message. His transition message needs to go to T_A0.
            // We fill Alice's T_A0 AFTER Alice has already polled, to ensure it's full when Bob posts.
            aliceClient.poll() // Drain Alice's mailbox first
            
            val tA0 = aliceManager.getFriend(aliceToBobId)!!.session.recvToken.toHex()
            val (bobSess1, bobMsg1) = Session.encryptMessage(bobManager.getFriend(bobToAliceId)!!.session, MessagePlaintext.Keepalive())
            bobManager.updateSession(bobToAliceId, bobSess1)
            repeat(5) {
                relay.post("http://fake", tA0, bobMsg1)
            }
            assertEquals(5, relay.inbox[tA0]?.size, "T_A0 should be full")

            // 5. Bob tries to send a REAL message. His post to T_A0 will fail with 429.
            try {
                bobClient.sendKeepalive(bobToAliceId)
            } catch (_: ServerException) {
            }
            val bobMid = bobManager.getFriend(bobToAliceId)!!
            assertNotNull(bobMid.outbox, "Bob's outbox should still be pending due to 429")

            // 6. Deadlock condition: Alice is polling T_A1, but Bob's outbox (with his new DH key)
            // is stuck in his retry queue because T_A0 is full of old messages that Alice
            // is no longer polling.
            // SOLUTION: Multi-token polling allows Alice to drain T_A0 even after ratcheting to T_A1.
            aliceClient.poll()
            
            // After Alice's poll, T_A0 should be partially cleared.
            assertTrue(relay.inbox[tA0]!!.size < 5, "T_A0 should be partially cleared after Alice's poll")

            // 7. Recovery: Bob can now post his transition message.
            var bobToAliceSuccess = false
            repeat(15) {
                try {
                    bobClient.poll() // Trigger processOutboxes
                    bobClient.sendLocation(0.0, 42.0)
                } catch (_: Exception) {
                }
                val updates = aliceClient.poll()
                if (updates.any { it.lng == 42.0 }) bobToAliceSuccess = true
            }

            assertTrue(bobToAliceSuccess, "Bob→Alice should recover after the queue-fill deadlock")
        }
}
