package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.random.Random

class E2eeChaosTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun testChaosRecovery() = runTest {
        val aliceBaseStorage = MemoryStorage()
        val bobBaseStorage = MemoryStorage()

        val aliceChaosStorage = ChaosStorage(aliceBaseStorage)
        val bobChaosStorage = ChaosStorage(bobBaseStorage)

        var aliceStore = E2eeStore(aliceChaosStorage)
        var bobStore = E2eeStore(bobChaosStorage)

        val relay = RelayMailboxClient()
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

        println("Starting Chaos Phase...")

        val successfulLocationsReceivedByBob = mutableSetOf<Int>()
        val successfulLocationsReceivedByAlice = mutableSetOf<Int>()

        repeat(1000) { i ->
            // Randomized chaos factors per iteration
            val currentChaos = Random.nextDouble(0.0, 0.5)
            aliceChaosStorage.failWriteProbability = currentChaos
            bobChaosStorage.failWriteProbability = currentChaos
            
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
        }

        println("Chaos Phase complete. Bob received ${successfulLocationsReceivedByBob.size} locations, Alice received ${successfulLocationsReceivedByAlice.size}")

        // 3. Recovery Phase: Turn off all chaos and see if they can still talk
        println("Starting Recovery Phase...")
        aliceChaosStorage.failWriteProbability = 0.0
        bobChaosStorage.failWriteProbability = 0.0
        aliceChaosMailbox.failPostProbability = 0.0
        aliceChaosMailbox.failPollProbability = 0.0
        bobChaosMailbox.failPostProbability = 0.0
        bobChaosMailbox.failPollProbability = 0.0
        aliceChaosMailbox.corruptPayloadProbability = 0.0
        bobChaosMailbox.corruptPayloadProbability = 0.0

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
            println("ALICE SESSION: sendToken=${aliceEntry.session.sendToken.toHex().take(8)} prevSendToken=${aliceEntry.session.prevSendToken.toHex().take(8)} isPending=${aliceEntry.session.isSendTokenPending} outbox=${aliceEntry.outbox?.token?.take(8)}")
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
    fun testQueueFillDeadlock() = runTest {
        // Small queue and drain size to make the deadlock trigger in a few iterations.
        val relay = RelayMailboxClient(maxQueueDepth = 5, maxDrainSize = 3)
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
        assertTrue(bobAfterPrime.session.isSendTokenPending, "Bob should have a pending token transition after receiving Alice's DH key")
        val tB0 = bobAfterPrime.session.prevSendToken.toHex()

        // Enable stealthy posts for Bob: each POST reaches the relay (message stored) but
        // the caller gets NetworkException, simulating a lost HTTP response on bad connectivity.
        bobChaosMailbox.stealthPost = true

        // Bob's first send writes the transition message (seq=1) to T_B0 once, then retries
        // on every poll() call via processOutboxes(). Fill T_B0 to maxQueueDepth.
        try { bobClient.sendKeepalive(bobToAliceId) } catch (_: Exception) {}  // T_B0: 1 msg
        repeat(4) {
            try { bobClient.poll() } catch (_: Exception) {}  // T_B0: 2, 3, 4, 5 msgs (full)
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
            try { bobClient.sendLocation(0.0, 42.0) } catch (_: Exception) {}
            val updates = aliceClient.poll()
            if (updates.any { it.lng == 42.0 }) bobToAliceSuccess = true
            bobClient.poll()
        }

        // Alice→Bob still works (her outbox is clear; she can send on her own sendToken).
        var aliceToBobSuccess = false
        repeat(10) {
            try { aliceClient.sendLocation(99.0, 0.0) } catch (_: Exception) {}
            val updates = bobClient.poll()
            if (updates.any { it.lat == 99.0 }) aliceToBobSuccess = true
        }

        val aliceFinal = aliceStore.getFriend(aliceToBobId)!!
        val bobFinal = bobStore.getFriend(bobToAliceId)!!
        println("ALICE: sendToken=${aliceFinal.session.sendToken.toHex().take(8)} recvToken=${aliceFinal.session.recvToken.toHex().take(8)} pending=${aliceFinal.session.isSendTokenPending} outbox=${aliceFinal.outbox?.token?.take(8)}")
        println("BOB:   sendToken=${bobFinal.session.sendToken.toHex().take(8)} recvToken=${bobFinal.session.recvToken.toHex().take(8)} pending=${bobFinal.session.isSendTokenPending} outbox=${bobFinal.outbox?.token?.take(8)}")
        println("RELAY: ${relay.inbox.keys.map { it.take(8) + "=" + (relay.inbox[it]?.size ?: 0) }}")

        assertTrue(aliceToBobSuccess, "Alice→Bob should work (Bob's outbox doesn't affect Alice's sends)")
        assertTrue(bobToAliceSuccess, "Bob→Alice should recover after the queue-fill deadlock")
    }
}
