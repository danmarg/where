package net.af0.where.e2ee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.af0.where.model.UserLocation
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class E2eeChaosTest {
    companion object {
        private val testMutex = kotlinx.coroutines.sync.Mutex()
    }

    private class MemoryMailboxClient : MailboxClient {
        private val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()
        private val receivedIds = mutableMapOf<String, MutableSet<String>>()
        private val lock = kotlinx.coroutines.sync.Mutex()

        override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) = lock.withLock {
            val ids = receivedIds.getOrPut(token) { mutableSetOf() }
            if (ids.contains(payload.msgId)) return@withLock
            
            mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
            ids.add(payload.msgId)
        }

        override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> = lock.withLock {
            mailboxes[token]?.toList() ?: emptyList()
        }

        override suspend fun ack(baseUrl: String, token: String, count: Int) = lock.withLock {
            val list = mailboxes[token] ?: return@withLock
            repeat(count) { if (list.isNotEmpty()) list.removeAt(0) }
        }

        override suspend fun ackId(baseUrl: String, token: String, msgId: String) {
            lock.withLock {
                mailboxes[token]?.removeAll { payload: MailboxPayload -> payload.msgId == msgId }
            }
        }

        override suspend fun ackIds(baseUrl: String, token: String, msgIds: List<String>) {
            lock.withLock {
                mailboxes[token]?.removeAll { payload: MailboxPayload -> payload.msgId in msgIds }
            }
        }
    }

    private class MemoryStorage : RawKeyValueStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
    }

    init {
        initializeE2eeTests()
    }

    @BeforeTest
    fun setupTime() {
        // We set the provider at the start of each test inside runTest
    }

    @Test
    fun testRobustnessUnderChaos() = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        testMutex.withLock {
            TimeSource.setProvider(object : TimeProvider {
                override fun currentTimeMillis() = testScheduler.currentTime
                override fun currentTimeSeconds() = testScheduler.currentTime / 1000
            })
            val mailbox = MemoryMailboxClient()
            val aliceStorage = MemoryStorage()
            val bobStorage = MemoryStorage()
            
            val aliceManager = E2eeManager(aliceStorage)
            val qr = aliceManager.createInvite("Alice")
            
            val bobManager = E2eeManager(bobStorage)
            val (initPayload, _) = bobManager.processScannedQr(qr, "Bob")
            
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            aliceClient.postKeyExchangeInit(qr, initPayload)
            
            val results = aliceClient.pollPendingInvites()
            assertEquals(1, results.size)
            aliceManager.processKeyExchangeInit(results[0].payload, "Bob", qr.ekPub)
            
            val bobClient = LocationClient("http://localhost", bobManager, mailbox)
            
            // 2. Start Chaos
            val chaosMailbox = ChaosMailboxClient(mailbox)
            chaosMailbox.failPostProbability = 0.05
            chaosMailbox.failPollProbability = 0.05
            chaosMailbox.dropProbability = 0.05
            
            val chaosAliceStorage = ChaosStorage(aliceStorage)
            val chaosBobStorage = ChaosStorage(bobStorage)

            val aliceManagerLive = E2eeManager(chaosAliceStorage)
            val bobManagerLive = E2eeManager(chaosBobStorage)
            val aliceClientLive = LocationClient("http://localhost", aliceManagerLive, chaosMailbox)
            val bobClientLive = LocationClient("http://localhost", bobManagerLive, chaosMailbox)

            val aliceLocations = mutableListOf<UserLocation>()
            val bobLocations = mutableListOf<UserLocation>()

            val totalMessages = 2
            
            // Use backgroundScope for tasks that run until the end of the test
            backgroundScope.launch {
                while (isActive) {
                    try { aliceClientLive.processOutboxes() } catch (e: Exception) {}
                    try { bobClientLive.processOutboxes() } catch (e: Exception) {}
                    delay(200)
                }
            }

            // Main test flow: these jobs are launched in the TestScope and we join them
            val aliceSendJob = launch {
                repeat(totalMessages) { i ->
                    while (true) {
                        try {
                            aliceClientLive.sendLocation(1.0 + i, 2.0 + i)
                            break
                        } catch (e: Exception) {
                            delay(10)
                        }
                    }
                }
            }

            val bobSendJob = launch {
                repeat(totalMessages) { i ->
                    while (true) {
                        try {
                            bobClientLive.sendLocation(10.0 + i, 20.0 + i)
                            break
                        } catch (e: Exception) {
                            delay(10)
                        }
                    }
                }
            }

            // Waiting for convergence in a loop within the main coroutine
            val start = currentTimeMillis()
            while (aliceLocations.size < totalMessages || bobLocations.size < totalMessages) {
                if (currentTimeMillis() - start > 60000) {
                    println("DEBUG: Robustness test convergence timeout reached. Alice got ${aliceLocations.size}, Bob got ${bobLocations.size}")
                    break 
                }
                
                try {
                    val aLocs = aliceClientLive.poll()
                    aliceLocations.addAll(aLocs)
                } catch (e: Exception) {}

                try {
                    val bLocs = bobClientLive.poll()
                    bobLocations.addAll(bLocs)
                } catch (e: Exception) {}

                delay(200)
            }
            
            aliceSendJob.join()
            bobSendJob.join()

            assertTrue(aliceLocations.size >= totalMessages, "Alice missed messages")
            assertTrue(bobLocations.size >= totalMessages, "Bob missed messages")
        }
    }

    @Test
    fun testMultiFriendReliable() = runTest(timeout = kotlin.time.Duration.parse("2m")) {
        testMutex.withLock {
            TimeSource.setProvider(object : TimeProvider {
                override fun currentTimeMillis() = testScheduler.currentTime
                override fun currentTimeSeconds() = testScheduler.currentTime / 1000
            })
            runMultiFriendChaos(
                numFriends = 3,
                messagesPerFriend = 5,
                failProb = 0.0,
                dropProb = 0.0,
            )
        }
    }

    @Test
    fun testMultiFriendChaosRealistic() = runTest(timeout = kotlin.time.Duration.parse("10m")) {
        testMutex.withLock {
            TimeSource.setProvider(object : TimeProvider {
                override fun currentTimeMillis() = testScheduler.currentTime
                override fun currentTimeSeconds() = testScheduler.currentTime / 1000
            })
            runMultiFriendChaos(
                numFriends = 2,
                messagesPerFriend = 10,
                failProb = 0.08, // 8% failure rate
                dropProb = 0.02, // 2% drop rate
            )
        }
    }

    private suspend fun TestScope.runMultiFriendChaos(
        numFriends: Int,
        messagesPerFriend: Int,
        failProb: Double,
        dropProb: Double,
    ) {
        val mailbox = MemoryMailboxClient()
        val chaosMailbox = ChaosMailboxClient(mailbox).apply {
            failPostProbability = failProb
            failPollProbability = failProb
            dropProbability = dropProb
        }

        val managers = (0 until numFriends).map { i ->
            val storage = ChaosStorage(MemoryStorage()).apply { failWriteProbability = failProb / 2 }
            E2eeManager(storage)
        }

        val clients = (0 until numFriends).map { i ->
            LocationClient("http://localhost", managers[i], chaosMailbox)
        }

        // 1. Start background polling and outbox processing in backgroundScope
        // We need this BEFORE handshakes because handshake messages use the outbox!
        clients.forEach { client ->
            backgroundScope.launch {
                while (isActive) {
                    try { client.poll() } catch (e: Exception) {}
                    try { client.processOutboxes() } catch (e: Exception) {}
                    delay(Random.nextLong(50, 150))
                }
            }
        }

        // 2. Establish friendships (fully connected graph)
        for (i in 0 until numFriends) {
            for (j in i + 1 until numFriends) {
                val alice = managers[i]
                val bob = managers[j]
                val qr = alice.createInvite("User-$i")
                val (init, _) = bob.processScannedQr(qr, "User-$j")

                // Retry POST until successful (or at least attempted once without exception)
                while (true) {
                    try {
                        clients[j].postKeyExchangeInit(qr, init)
                        break
                    } catch (e: Exception) {
                        delay(20)
                    }
                }

                // Retry POLL until successful and handshake complete
                var attempts = 0
                while (true) {
                    attempts++
                    try {
                        val results = clients[i].pollPendingInvites()
                        val matching = results.find { it.aliceEkPub.contentEquals(qr.ekPub) }
                        if (matching != null) {
                            alice.processKeyExchangeInit(matching.payload, "User-$j", qr.ekPub)
                            println("DEBUG: Handshake complete between User-$i and User-$j after $attempts attempts")
                            break
                        }
                    } catch (e: Exception) {}
                    delay(100)
                }
            }
        }

        // 3. Send messages
        val sendJobs = (0 until numFriends).map { i ->
            launch {
                repeat(messagesPerFriend) { m ->
                    try {
                        clients[i].sendLocation(i.toDouble(), m.toDouble())
                    } catch (e: Exception) {
                        // WAL outbox will handle retransmission if encryptAndAdvance succeeded.
                        // We don't retry at the app layer to avoid flooding the outbox with duplicates.
                    }
                    delay(Random.nextLong(100, 300)) // Throttle sends
                }
            }
        }

        // Wait for all senders to finish
        sendJobs.forEach { it.join() }

        // 4. Wait for convergence
        val start = currentTimeMillis()
        val convergenceTimeout = 600_000L // 10 minutes for convergence in stress test
        while (currentTimeMillis() - start < convergenceTimeout) {
            var allDone = true
            for (i in 0 until numFriends) {
                val friends = managers[i].listFriends()
                if (friends.size < numFriends - 1) {
                    allDone = false
                    break
                }
                
                for (friend in friends) {
                    if (friend.lastLng?.toInt() != messagesPerFriend - 1) {
                        allDone = false
                        break
                    }
                }
                if (!allDone) break
            }
            if (allDone) {
                println("DEBUG: Chaos test converged successfully after ${currentTimeMillis() - start}ms")
                break
            }
            delay(2000)
        }
        
        // 5. Final verification with debug info
        for (i in 0 until numFriends) {
            val friends = managers[i].listFriends()
            if (friends.size < numFriends - 1) {
                println("ERROR: User $i has only ${friends.size} friends, expected ${numFriends - 1}")
            }
            assertEquals(numFriends - 1, friends.size, "User $i should have ${numFriends - 1} friends")
            for (friend in friends) {
                if (friend.lastLng?.toInt() != messagesPerFriend - 1) {
                    println("ERROR: User $i last message from ${friend.name} was ${friend.lastLng}, expected ${messagesPerFriend - 1}")
                }
                assertEquals(messagesPerFriend - 1, friend.lastLng?.toInt(), 
                    "User $i did not receive final message from ${friend.name}")
            }
        }
    }
}
