package net.af0.where.e2ee

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

class E2eeChaosTest {

    @Test
    fun testRobustnessUnderChaos() = runTest(timeout = 10.minutes) {
        val seed = platformCurrentTimeMillis()
        println("Chaos Test Seed: $seed")
        // Note: For full reproducibility we should use a Random instance with this seed,
        // but currently ChaosInfrastructure uses Random.nextDouble() which is the global generator.

        initializeE2eeTests()

        val aliceStorage = MemoryStorage()
        val bobStorage = MemoryStorage()

        val aliceManager = E2eeManager(aliceStorage)
        val bobManager = E2eeManager(bobStorage)

        val mailbox = MockMailbox()
        val mailboxStore = object : MailboxClient {
            override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
                mailbox.post(token, aliceManager.persistence.json.encodeToJsonElement(MailboxPayload.serializer(), payload))
            }
            override suspend fun poll(baseUrl: String, token: String): List<MailboxMessage> {
                return mailbox.drain(token)
            }
            override suspend fun ack(baseUrl: String, token: String, ids: List<String>) {
                mailbox.delete(token, ids)
            }
        }

        val aliceClient = LocationClient("http://chaos", aliceManager, mailboxStore)
        val bobClient = LocationClient("http://chaos", bobManager, mailboxStore)

        // 1. Setup session
        val qr = aliceManager.createInvite("Alice")
        val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
        aliceClient.postKeyExchangeInit(qr, initPayload)

        // Alice polls pending invites
        var paired = false
        repeat(20) {
            if (!paired) {
                val pendingResults = aliceClient.pollPendingInvites()
                for (res in pendingResults) {
                     aliceManager.processKeyExchangeInit(res.payload, "Bob", res.aliceEkPub)
                     paired = true
                }
            }
        }

        if (aliceManager.listFriends().isEmpty()) {
            fail("SETUP FAILED TO ESTABLISH SESSION")
        }

        val aliceChaosStorage = ChaosStorage(aliceStorage)
        val bobChaosStorage = ChaosStorage(bobStorage)
        val aliceManagerChaos = E2eeManager(aliceChaosStorage)
        val bobManagerChaos = E2eeManager(bobChaosStorage)
        val aliceChaosMailbox = ChaosMailboxClient(mailboxStore)
        val bobChaosMailbox = ChaosMailboxClient(mailboxStore)
        val aliceClientChaos = LocationClient("http://chaos", aliceManagerChaos, aliceChaosMailbox)
        val bobClientChaos = LocationClient("http://chaos", bobManagerChaos, bobChaosMailbox)

        // Set chaos probabilities
        aliceChaosStorage.failWriteProbability = 0.1
        bobChaosStorage.failWriteProbability = 0.1
        aliceChaosMailbox.failPostProbability = 0.1
        aliceChaosMailbox.failPollProbability = 0.1
        bobChaosMailbox.failPostProbability = 0.1
        bobChaosMailbox.failPollProbability = 0.1
        aliceChaosMailbox.reorderProbability = 0.05
        bobChaosMailbox.reorderProbability = 0.05

        val iterations = 500
        for (i in 1..iterations) {
            try { aliceClientChaos.sendLocation(1.0 + i, 2.0 + i) } catch (e: Exception) {}
            try { bobClientChaos.poll() } catch (e: Exception) {}

            if (i % 50 == 0) {
                // Periodic healing
                aliceChaosStorage.failWriteProbability = 0.0
                bobChaosStorage.failWriteProbability = 0.0
                aliceChaosMailbox.failPostProbability = 0.0
                aliceChaosMailbox.failPollProbability = 0.0
                bobChaosMailbox.failPostProbability = 0.0
                bobChaosMailbox.failPollProbability = 0.0

                aliceClientChaos.poll()
                bobClientChaos.poll()

                aliceChaosStorage.failWriteProbability = 0.1
                bobChaosStorage.failWriteProbability = 0.1
                aliceChaosMailbox.failPostProbability = 0.1
                aliceChaosMailbox.failPollProbability = 0.1
                bobChaosMailbox.failPostProbability = 0.1
                bobChaosMailbox.failPollProbability = 0.1
            }
            delay(1)
        }

        // Final healing
        aliceChaosStorage.failWriteProbability = 0.0
        bobChaosStorage.failWriteProbability = 0.0
        aliceChaosMailbox.failPostProbability = 0.0
        aliceChaosMailbox.failPollProbability = 0.0
        bobChaosMailbox.failPostProbability = 0.0
        bobChaosMailbox.failPollProbability = 0.0

        repeat(20) {
            aliceClientChaos.poll()
            bobClientChaos.poll()
            delay(1)
        }

        val aliceFriends = aliceManagerChaos.listFriends()
        val aliceFriend = aliceManagerChaos.getFriend(bobEntry.id)!!
        val bobFriend = bobManagerChaos.getFriend(aliceFriends.first().id)!!

        println("Final State: Alice.sendSeq=${aliceFriend.session.sendSeq} Bob.recvSeq=${bobFriend.session.recvSeq}")

        assertEquals(aliceFriend.session.sendSeq, bobFriend.session.recvSeq, "Sequence mismatch after chaos")
        assertTrue(aliceFriend.pendingOutbox.isEmpty(), "Alice has pending outbox messages")
        assertTrue(bobFriend.pendingAcks.isEmpty(), "Bob has pending ACKs")
    }
}
