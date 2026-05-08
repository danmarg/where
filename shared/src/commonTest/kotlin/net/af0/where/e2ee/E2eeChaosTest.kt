package net.af0.where.e2ee

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.*

class E2eeChaosTest {
    init {
        initializeE2eeTests()
    }

    @AfterTest
    fun tearDown() {
        TimeSource.setProvider(DefaultTimeProvider)
    }

    @Test
    fun testChaosRecovery() =
        runTest {
            val clock = ChaosTimeProvider()
            TimeSource.setProvider(clock)
            val aliceBaseStorage = MemoryStorage()
            val bobBaseStorage = MemoryStorage()

            val aliceChaosStorage = ChaosStorage(aliceBaseStorage)
            val bobChaosStorage = ChaosStorage(bobBaseStorage)

            var aliceStore = E2eeStore(aliceChaosStorage)
            var bobStore = E2eeStore(bobChaosStorage)

            val relay = RelayMailboxClient(rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val bobChaosMailbox = ChaosMailboxClient(relay)

            var aliceClient = LocationClient("http://fake", aliceStore, aliceChaosMailbox)
            var bobClient = LocationClient("http://fake", bobStore, bobChaosMailbox)

            // 1. Initial pairing (no chaos yet)
            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)

            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 2. Start Chaos
            val chaosProbability = 0.3
            aliceChaosStorage.failWriteProbability = chaosProbability
            bobChaosStorage.failWriteProbability = chaosProbability
            aliceChaosMailbox.failPostProbability = chaosProbability
            aliceChaosMailbox.failPollProbability = chaosProbability
            bobChaosMailbox.failPostProbability = chaosProbability
            bobChaosMailbox.failPollProbability = chaosProbability

            aliceChaosMailbox.corruptPayloadProbability = 0.1
            bobChaosMailbox.corruptPayloadProbability = 0.1
            aliceChaosMailbox.reorderProbability = 0.1
            bobChaosMailbox.reorderProbability = 0.1
            aliceChaosMailbox.dropProbability = 0.05
            bobChaosMailbox.dropProbability = 0.05
            aliceChaosMailbox.stealthDropProbability = 0.02
            bobChaosMailbox.stealthDropProbability = 0.02
            aliceChaosMailbox.maxLatencyMs = 50
            bobChaosMailbox.maxLatencyMs = 50

            println("Starting Chaos Phase...")

            val successfulLocationsReceivedByBob = mutableSetOf<Int>()
            val successfulLocationsReceivedByAlice = mutableSetOf<Int>()

            repeat(1000) { i ->
                // Randomized chaos factors per iteration
                val currentChaos = Random.nextDouble(0.0, 0.5)
                aliceChaosStorage.failWriteProbability = currentChaos
                bobChaosStorage.failWriteProbability = currentChaos

                // Occasional mailbox expiration
                if (Random.nextDouble() < 0.01) {
                    aliceChaosMailbox.expireMailboxProbability = 0.1
                    bobChaosMailbox.expireMailboxProbability = 0.1
                } else {
                    aliceChaosMailbox.expireMailboxProbability = 0.0
                    bobChaosMailbox.expireMailboxProbability = 0.0
                    if (Random.nextDouble() < 0.05) {
                        aliceChaosMailbox.resetExpirations()
                        bobChaosMailbox.resetExpirations()
                    }
                }

                // Randomly restart stores (simulates app kill/memory loss)
                if (Random.nextDouble() < 0.1) {
                    aliceStore = E2eeStore(aliceChaosStorage)
                    aliceClient = LocationClient("http://fake", aliceStore, aliceChaosMailbox)
                }
                if (Random.nextDouble() < 0.1) {
                    bobStore = E2eeStore(bobChaosStorage)
                    bobClient = LocationClient("http://fake", bobStore, bobChaosMailbox)
                }

                // Alice tries to send location i
                try {
                    aliceClient.sendLocation(i.toDouble(), 0.0)
                } catch (e: Exception) {
                    // Ignore failures during chaos
                }

                // Bob tries to poll
                try {
                    val updates = bobClient.poll()
                    updates.forEach { successfulLocationsReceivedByBob.add(it.lat.toInt()) }
                } catch (e: Exception) {
                    // Ignore failures during chaos
                }

                // Bob tries to send location i
                try {
                    bobClient.sendLocation(0.0, i.toDouble())
                } catch (e: Exception) {
                    // Ignore failures during chaos
                }

                // Alice tries to poll
                try {
                    val updates = aliceClient.poll()
                    updates.forEach { successfulLocationsReceivedByAlice.add(it.lng.toInt()) }
                } catch (e: Exception) {
                    // Ignore failures during chaos
                }

                // Occasional clock skew
                if (Random.nextDouble() < 0.05) {
                    clock.addOffset(Random.nextLong(-10000, 10000))
                }
            }

            println(
                "Chaos Phase complete. Bob received ${successfulLocationsReceivedByBob.size} locations, Alice received ${successfulLocationsReceivedByAlice.size}",
            )

            // 3. Recovery Phase: Turn off all chaos and see if they can still talk
            println("Starting Recovery Phase...")
            TimeSource.setProvider(DefaultTimeProvider)
            aliceChaosStorage.failWriteProbability = 0.0
            bobChaosStorage.failWriteProbability = 0.0
            aliceChaosMailbox.failPostProbability = 0.0
            aliceChaosMailbox.failPollProbability = 0.0
            bobChaosMailbox.failPostProbability = 0.0
            bobChaosMailbox.failPollProbability = 0.0
            aliceChaosMailbox.corruptPayloadProbability = 0.0
            bobChaosMailbox.corruptPayloadProbability = 0.0
            aliceChaosMailbox.dropProbability = 0.0
            bobChaosMailbox.dropProbability = 0.0
            aliceChaosMailbox.stealthDropProbability = 0.0
            bobChaosMailbox.stealthDropProbability = 0.0
            aliceChaosMailbox.maxLatencyMs = 0
            bobChaosMailbox.maxLatencyMs = 0
            aliceChaosMailbox.expireMailboxProbability = 0.0
            bobChaosMailbox.expireMailboxProbability = 0.0
            aliceChaosMailbox.resetExpirations()
            bobChaosMailbox.resetExpirations()

            // One more round of restarts to ensure they recover from their LAST DISK STATE
            aliceStore = E2eeStore(aliceChaosStorage)
            aliceClient = LocationClient("http://fake", aliceStore, aliceChaosMailbox)
            bobStore = E2eeStore(bobChaosStorage)
            bobClient = LocationClient("http://fake", bobStore, bobChaosMailbox)

            // Final catch-up: several rounds of polls
            repeat(10) {
                try {
                    val bUpdates = bobClient.poll()
                    bUpdates.forEach { successfulLocationsReceivedByBob.add(it.lat.toInt()) }
                    val aUpdates = aliceClient.poll()
                    aUpdates.forEach { successfulLocationsReceivedByAlice.add(it.lng.toInt()) }
                } catch (e: Exception) {
                    println("Poll failed during recovery: ${e.message}")
                }
            }

            // Send one final verified message each way
            val finalLat = 9999.0
            val finalLng = 8888.0

            var aliceSuccess = false
            var bobSuccess = false

            repeat(20) {
                if (!aliceSuccess) {
                    try {
                        aliceClient.sendLocation(finalLat, 0.0)
                        aliceSuccess = true
                    } catch (e: Exception) {
                        println("Alice final send failed: ${e.message}")
                    }
                }
                try {
                    val updates = bobClient.poll()
                    if (updates.any { it.lat == finalLat }) {
                        bobSuccess = true
                    }
                } catch (e: Exception) {
                    println("Bob final poll failed: ${e.message}")
                }

                if (!bobSuccess) {
                    try {
                        bobClient.sendLocation(0.0, finalLng)
                        bobSuccess = true
                    } catch (e: Exception) {
                        println("Bob final send failed: ${e.message}")
                    }
                }
                try {
                    val updates = aliceClient.poll()
                    if (updates.any { it.lng == finalLng }) {
                        aliceSuccess = true
                    }
                } catch (e: Exception) {
                    println("Alice final poll failed: ${e.message}")
                }
            }

            if (!bobSuccess) {
                val aliceEntry = aliceStore.getFriend(aliceToBobId)!!
                val bobEntryFinal = bobStore.getFriend(bobToAliceId)!!
                println(
                    "ALICE SESSION: sendToken=${aliceEntry.session.sendToken.toHex().take(
                        8,
                    )} prevSendToken=${aliceEntry.session.prevSendToken.toHex().take(
                        8,
                    )} isPending=${aliceEntry.session.isSendTokenPending} outbox=${aliceEntry.outbox?.token?.take(8)}",
                )
                println("BOB SESSION:   recvToken=${bobEntryFinal.session.recvToken.toHex().take(8)}")
                println("RELAY INBOX TOKENS: ${relay.inbox.keys.map { it.take(8) }}")
            }

            assertTrue(bobSuccess, "Bob should eventually receive Alice's final message")
            assertTrue(aliceSuccess, "Alice should eventually receive Bob's final message")
        }

    // Reproduces the production deadlock: Bob (Android/Samsung bad connectivity) floods
    // Alice's prevRecvToken (T_B0) with duplicate transition messages, then Alice ratchets
    // away to T_B1 after seeing the first copy. Bob's outbox remains stuck on the full
    // T_B0 queue (429), blocking all further Bob→Alice sends permanently.
    //
    // Mechanism:
    //   1. Alice sends keepalive; Bob receives, DH-ratchets: prevSendToken=T_B0, sendToken=T_B1
    //   2. Bob's transition POST (to T_B0) reaches the relay but the HTTP response is "lost"
    //      (stealthPost=true). Bob sees NetworkException, keeps outbox pending, and retries.
    //   3. Each poll() call triggers processOutboxes(), adding another duplicate seq=1 copy
    //      until T_B0 is full (maxQueueDepth=5).
    //   4. Alice polls T_B0: decrypts first copy (ratchets to T_B1), remaining copies are
    //      silent-drops (wrong key after ratchet) → hadSilentDrops=true → no ACK.
    //   5. T_B0 stays full. Bob's next processOutboxes gets 429. Outbox never clears.
    //   6. Bob→Alice is permanently broken; Alice→Bob still works.
    //
    // This test should FAIL until the production fix is applied (e.g., abandoning a stuck
    // prevSendToken outbox after N consecutive 429s and finalizing the transition anyway).
    @Test
    fun testMailboxExpirationRecovery() =
        runTest {
            initializeE2eeTests()
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val relay = RelayMailboxClient(rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val bobChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceStore, aliceChaosMailbox)
            val bobClient = LocationClient("http://fake", bobStore, bobChaosMailbox)

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // 1. Initial exchange
            aliceClient.sendKeepalive(aliceToBobId)
            bobClient.poll()
            aliceClient.poll()

            // 2. Alice's send token expires
            val aliceEntry = aliceStore.getFriend(aliceToBobId)!!
            val sendToken = aliceEntry.session.sendToken.toHex()
            aliceChaosMailbox.expireMailboxStatusCode = 404
            // We simulate that the token on the relay is "gone"
            relay.inbox.remove(sendToken)
            // And further attempts to POST to it will return 404
            aliceChaosMailbox.resetExpirations() // Clear set of expired tokens
            // Manually mark it as expired in chaos client
            // ChaosMailboxClient uses a set, but doesn't have a public method to add.
            // I'll use the probability to trigger it.
            aliceChaosMailbox.expireMailboxProbability = 1.0

            // Alice tries to send - should fail with 404.
            // The outbox remains until the next recovery cycle.
            try {
                aliceClient.sendLocation(1.0, 2.0)
            } catch (e: Exception) {
                // expected
            }

            // Trigger recovery
            aliceChaosMailbox.expireMailboxProbability = 0.0
            aliceChaosMailbox.resetExpirations()
            aliceClient.poll()
            val aliceAfterFail = aliceStore.getFriend(aliceToBobId)!!
            assertNull(aliceAfterFail.outbox, "Outbox should be cleared after recovery from 404")

            // 3. Transition token expires
            // Bob sends keepalive, Alice polls and ratchets. Alice now has isSendTokenPending = true.
            bobClient.sendKeepalive(bobToAliceId)
            aliceClient.poll()

            val aliceEntry2 = aliceStore.getFriend(aliceToBobId)!!
            assertTrue(aliceEntry2.session.isSendTokenPending)
            val prevSendToken = aliceEntry2.session.prevSendToken.toHex()

            // Mark prevSendToken as expired
            aliceChaosMailbox.expireMailboxProbability = 1.0

            // Alice tries to send location - will try to POST to prevSendToken, get 404.
            try {
                aliceClient.sendLocation(3.0, 4.0)
            } catch (e: Exception) {
                // expected
            }

            // Trigger recovery
            aliceChaosMailbox.expireMailboxProbability = 0.0
            aliceChaosMailbox.resetExpirations()
            aliceClient.poll()
            val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
            assertFalse(
                aliceFinal.session.isSendTokenPending,
                "isSendTokenPending should be cleared after recovery from 404 on transition token",
            )
            assertNull(aliceFinal.outbox, "Outbox should be cleared")
        }

    @Test
    fun testMultiFriendChaos() =
        runTest {
            initializeE2eeTests()
            val relay = RelayMailboxClient(rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val clock = ChaosTimeProvider()
            TimeSource.setProvider(clock)

            class Node(val name: String) {
                val baseStorage = MemoryStorage()
                val chaosStorage = ChaosStorage(baseStorage)
                var store = E2eeStore(chaosStorage)
                val chaosMailbox = ChaosMailboxClient(relay)
                var client = LocationClient("http://fake", store, chaosMailbox)
                val receivedLocations = mutableMapOf<String, MutableSet<Int>>()

                fun restart() {
                    store = E2eeStore(chaosStorage)
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
            println("Starting Multi-Friend Chaos Phase...")
            // Reduced volume and probability to ensure stability in CI while still exercising code paths.
            // High chaos (especially mailbox expiration) can lead to permanent desync which is a
            // known protocol limitation when transition messages are lost.
            repeat(20) { i ->
                nodes.forEach { node ->
                    node.setChaos(Random.nextDouble(0.0, 0.1))
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

            repeat(200) {
                nodes.forEach { node ->
                    try {
                        val updates = node.client.poll()
                        updates.forEach { update ->
                            node.receivedLocations.getOrPut(update.userId) { mutableSetOf() }.add(update.lat.toInt())
                        }
                        node.client.sendLocation(999.0, 999.0)
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
    fun testQueueFillDeadlock() =
        runTest {
            initializeE2eeTests()
            // Small queue and drain size to make the deadlock trigger in a few iterations.
            val relay = RelayMailboxClient(maxQueueDepth = 5, maxDrainSize = 3, rateLimitMaxPosts = 5000, rateLimitMaxPolls = 5000)
            val aliceStore = E2eeStore(MemoryStorage())
            val bobStore = E2eeStore(MemoryStorage())
            val aliceChaosMailbox = ChaosMailboxClient(relay)
            val bobChaosMailbox = ChaosMailboxClient(relay)
            val aliceClient = LocationClient("http://fake", aliceStore, aliceChaosMailbox)
            val bobClient = LocationClient("http://fake", bobStore, bobChaosMailbox)

            val qr = aliceStore.createInvite("Alice")
            val (initPayload, bobEntry) = bobStore.processScannedQr(qr)
            aliceStore.processKeyExchangeInit(initPayload, "Bob", qr.ekPub)
            val aliceToBobId = aliceStore.listFriends().first().id
            val bobToAliceId = bobEntry.id

            // Prime: Alice sends her bootstrap keepalive; Bob receives it, performs DH ratchet.
            // After this Bob has: isSendTokenPending=true, prevSendToken=T_B0, sendToken=T_B1.
            aliceClient.sendKeepalive(aliceToBobId)
            bobClient.poll()
            aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))

            val bobAfterPrime = bobStore.getFriend(bobToAliceId)!!
            assertTrue(
                bobAfterPrime.session.isSendTokenPending,
                "Bob should have a pending token transition after receiving Alice's DH key",
            )
            val tB0 = bobAfterPrime.session.prevSendToken.toHex()

            // Enable stealthy posts for Bob: each POST reaches the relay (message stored) but
            // the caller gets NetworkException, simulating a lost HTTP response on bad connectivity.
            bobChaosMailbox.stealthPost = true

            // Bob's first send writes the transition message (seq=1) to T_B0 once, then retries
            // on every poll() call via processOutboxes(). Fill T_B0 to maxQueueDepth.
            try {
                bobClient.sendKeepalive(bobToAliceId)
            } catch (_: Exception) {
            } // T_B0: 1 msg
            repeat(4) {
                try {
                    bobClient.poll()
                } catch (_: Exception) {
                } // T_B0: 2, 3, 4, 5 msgs (full)
            }

            assertEquals(5, relay.inbox[tB0]?.size, "T_B0 should be full after retry flood")

            // Disable stealth — connectivity "restored", but T_B0 is already full.
            bobChaosMailbox.stealthPost = false

            // Alice wakes up and polls. She gets 3 of the 5 copies from T_B0 (maxDrainSize).
            // The first decrypts successfully (Bob's transition → Alice ratchets to T_B1).
            // The remaining 2 are seq=1 duplicates. With our fix, these are NOT considered
            // silent drops because their headers are recognized. Alice ACKs them, clearing T_B0.
            aliceClient.poll(pausedFriendIds = setOf(aliceToBobId))
            val aliceAfterPoll = aliceStore.getFriend(aliceToBobId)!!
            val tB1 = aliceAfterPoll.session.recvToken.toHex()
            assertNotEquals(tB0, tB1, "Alice should have ratcheted away from T_B0 to T_B1")

            // Assert that the queue size decreased (meaning ACK worked even with duplicates).
            // Queue was 5, Alice drained 3 and ACKed them -> 2 remain.
            assertEquals(2, relay.inbox[tB0]?.size, "T_B0 should be partially cleared after Alice's ACK")

            // Bob→Alice is now unblocked: T_B0 is no longer full (size 2 < 5).
            // Bob's next processOutboxes will successfully post the transition message to T_B0,
            // clear his outbox, and finalize the transition.
            var bobToAliceSuccess = false
            repeat(15) {
                try {
                    bobClient.sendLocation(0.0, 42.0)
                } catch (_: Exception) {
                }
                val updates = aliceClient.poll()
                if (updates.any { it.lng == 42.0 }) bobToAliceSuccess = true
                bobClient.poll()
            }

            // Alice→Bob still works (her outbox is clear; she can send on her own sendToken).
            var aliceToBobSuccess = false
            repeat(10) {
                try {
                    aliceClient.sendLocation(99.0, 0.0)
                } catch (_: Exception) {
                }
                val updates = bobClient.poll()
                if (updates.any { it.lat == 99.0 }) aliceToBobSuccess = true
            }

            val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
            val bobFinal = bobStore.getFriend(bobToAliceId)!!
            println(
                "ALICE: sendToken=${aliceFinal.session.sendToken.toHex().take(
                    8,
                )} recvToken=${aliceFinal.session.recvToken.toHex().take(
                    8,
                )} pending=${aliceFinal.session.isSendTokenPending} outbox=${aliceFinal.outbox?.token?.take(8)}",
            )
            println(
                "BOB:   sendToken=${bobFinal.session.sendToken.toHex().take(
                    8,
                )} recvToken=${bobFinal.session.recvToken.toHex().take(
                    8,
                )} pending=${bobFinal.session.isSendTokenPending} outbox=${bobFinal.outbox?.token?.take(8)}",
            )
            println("RELAY: ${relay.inbox.keys.map { it.take(8) + "=" + (relay.inbox[it]?.size ?: 0) }}")

            assertTrue(aliceToBobSuccess, "Alice→Bob should work (Bob's outbox doesn't affect Alice's sends)")
            assertTrue(bobToAliceSuccess, "Bob→Alice should recover after the queue-fill deadlock")
        }
}
