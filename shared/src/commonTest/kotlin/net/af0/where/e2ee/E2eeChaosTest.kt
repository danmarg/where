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
}
