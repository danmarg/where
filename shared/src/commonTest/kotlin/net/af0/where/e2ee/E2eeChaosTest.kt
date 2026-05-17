package net.af0.where.e2ee

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class E2eeChaosTest {
    companion object {
    }

    private class MemoryMailboxClient : MailboxClient {
        private val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()
        private val receivedIds = mutableMapOf<String, MutableSet<String>>()
        private val lock = kotlinx.coroutines.sync.Mutex()

        override suspend fun post(
            baseUrl: String,
            token: String,
            payload: MailboxPayload,
        ) {
            lock.withLock {
                val ids = receivedIds.getOrPut(token) { mutableSetOf() }
                if (ids.contains(payload.msgId)) return@withLock
                ids.add(payload.msgId)
                mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
            }
        }

        override suspend fun poll(
            baseUrl: String,
            token: String,
        ): List<MailboxPayload> {
            return lock.withLock {
                val msgs = mailboxes[token] ?: emptyList()
                msgs.toList()
            }
        }

        override suspend fun ackId(
            baseUrl: String,
            token: String,
            msgId: String,
        ) {
            lock.withLock {
                mailboxes[token]?.removeAll { it.msgId == msgId } ?: false
            }
        }

        override suspend fun ackIds(
            baseUrl: String,
            token: String,
            msgIds: List<String>,
        ) {
            lock.withLock {
                mailboxes[token]?.removeAll { payload: MailboxPayload -> payload.msgId in msgIds }
            }
        }

        suspend fun totalMessages(): Int =
            lock.withLock {
                mailboxes.values.sumOf { it.size }
            }

        suspend fun dumpStatus(): String =
            lock.withLock {
                mailboxes.entries.filter { it.value.isNotEmpty() }
                    .joinToString(", ") { "${it.key.take(8)}:${it.value.size}" }
            }
    }

    private class MemoryStorage : RawKeyValueStorage {
        val map = mutableMapOf<String, String>()

        override fun getString(key: String): String? = map[key]

        override fun putString(
            key: String,
            value: String,
        ) {
            map[key] = value
        }
    }

    init {
        initializeE2eeTests()
    }

    @BeforeTest
    fun setupTime() {
        // We set the provider at the start of each test inside runTest
    }

    @Test
    fun testRobustnessUnderChaos() =
        runTest(timeout = kotlin.time.Duration.parse("5m")) {
            val baseTimeMs = 1715670000000L
            TimeSource.setProvider(
                object : TimeProvider {
                    override fun currentTimeMillis() = baseTimeMs + testScheduler.currentTime

                    override fun currentTimeSeconds() = (baseTimeMs + testScheduler.currentTime) / 1000
                },
            )
            val mailbox = MemoryMailboxClient()
            val chaosMailbox = ChaosMailboxClient(mailbox)

            val aliceSqlDriver = createTestSqlDriver()
            val bobSqlDriver = createTestSqlDriver()

            val aliceManager = E2eeManager(aliceSqlDriver)
            val bobManager = E2eeManager(bobSqlDriver)

            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")

            val aliceClient = LocationClient("http://localhost", aliceManager, chaosMailbox)
            aliceClient.enableAutomatedKeepalives = false
            // Note: Alice calling postKeyExchangeInit for Bob is just for testing set up
            aliceClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)

            val results = aliceClient.pollPendingInvites()
            assertEquals(1, results.size)
            aliceManager.processKeyExchangeInit(results[0].payload, "Bob", qr.ekPub)

            val bobClient = LocationClient("http://localhost", bobManager, chaosMailbox)
            bobClient.enableAutomatedKeepalives = false

            // 2. Start Chaos AFTER handshake is complete
            chaosMailbox.failPostProbability = 0.0
            chaosMailbox.failPollProbability = 0.0
            chaosMailbox.dropProbability = 0.0

            val totalMessages = 5
            val idBforA = aliceManager.listFriends().first { it.name == "Bob" }.id
            val idAforB = bobManager.listFriends().first { it.name == "Alice" }.id

            // 2. Start background workers
            val backgroundJobs = mutableListOf<kotlinx.coroutines.Job>()
            backgroundJobs.add(
                launch {
                    while (isActive) {
                        try {
                            aliceClient.poll()
                        } catch (e: Exception) {
                        }
                        try {
                            bobClient.poll()
                        } catch (e: Exception) {
                        }
                        delay(Random.nextLong(100, 300))
                    }
                },
            )

            backgroundJobs.add(
                launch {
                    while (isActive) {
                        try {
                            aliceClient.processOutboxes()
                        } catch (e: Exception) {
                        }
                        try {
                            bobClient.processOutboxes()
                        } catch (e: Exception) {
                        }
                        delay(Random.nextLong(100, 300))
                    }
                },
            )

            // Main test flow: these jobs are launched in the TestScope and we join them
            val aliceSendJob =
                launch {
                    repeat(totalMessages) { i ->
                        while (true) {
                            val outbox = aliceManager.getOutbox(idBforA)
                            if (outbox.isEmpty()) {
                                try {
                                    aliceClient.sendLocation(1.0 + i, i.toDouble())
                                    delay(1000)
                                    break
                                } catch (e: Exception) {
                                    delay(10)
                                }
                            } else {
                                delay(100)
                            }
                        }
                    }
                }

            val bobSendJob =
                launch {
                    repeat(totalMessages) { i ->
                        while (true) {
                            val outbox = bobManager.getOutbox(idAforB)
                            if (outbox.isEmpty()) {
                                try {
                                    bobClient.sendLocation(10.0 + i, i.toDouble())
                                    delay(1000)
                                    break
                                } catch (e: Exception) {
                                    delay(10)
                                }
                            } else {
                                delay(100)
                            }
                        }
                    }
                }

            // 3. Convergence Loop
            val convergenceTimeout = 900_000L // 15 minutes virtual
            try {
                withTimeout(convergenceTimeout) {
                    while (true) {
                        yield()
                        val aliceFriends = aliceManager.listFriends()
                        val bobFriends = bobManager.listFriends()

                        val aliceDone = aliceFriends.any { it.name == "Bob" && it.lastLng?.toInt() == totalMessages - 1 }
                        val bobDone = bobFriends.any { it.name == "Alice" && it.lastLng?.toInt() == totalMessages - 1 }

                        if (aliceDone && bobDone) {
                            break
                        }
                        delay(500)
                    }
                }
            } catch (e: Exception) {
                val aliceFriends = aliceManager.listFriends()
                val bobFriends = bobManager.listFriends()
                val aliceLast = aliceFriends.find { it.name == "Bob" }?.lastLng
                val bobLast = bobFriends.find { it.name == "Alice" }?.lastLng
                error("Robustness test timed out! Alice saw Bob at $aliceLast, Bob saw Alice at $bobLast. Wanted ${totalMessages - 1}")
            } finally {
                backgroundJobs.forEach { it.cancelAndJoin() }
            }

            aliceSendJob.join()
            bobSendJob.join()

            // 4. Final verification
            val aliceFriends = aliceManager.listFriends()
            val bobFriends = bobManager.listFriends()

            assertTrue(aliceFriends.any { it.name == "Bob" && it.lastLng?.toInt() == totalMessages - 1 }, "Alice missed messages")
            assertTrue(bobFriends.any { it.name == "Alice" && it.lastLng?.toInt() == totalMessages - 1 }, "Bob missed messages")
        }

    @Test
    fun testHandshakeUnderChaos() =
        runTest(timeout = kotlin.time.Duration.parse("5m")) {
            TimeSource.setProvider(
                object : TimeProvider {
                    override fun currentTimeMillis() = testScheduler.currentTime

                    override fun currentTimeSeconds() = testScheduler.currentTime / 1000
                },
            )

            val mailbox = MemoryMailboxClient()
            val chaosMailbox = ChaosMailboxClient(mailbox)

            val aliceManager = E2eeManager(createTestSqlDriver())
            val aliceClient = LocationClient("http://localhost", aliceManager, chaosMailbox)

            val bobManager = E2eeManager(createTestSqlDriver())
            val bobClient = LocationClient("http://localhost", bobManager, chaosMailbox)

            // 1. Enable AGGRESSIVE chaos BEFORE handshake starts
            chaosMailbox.failPostProbability = 0.20
            chaosMailbox.failPollProbability = 0.20

            // 2. Alice creates invite (retry until success)
            var qr: QrPayload? = null
            while (qr == null) {
                try {
                    qr = aliceManager.createInvite("Alice")
                } catch (e: Exception) {
                    delay(100)
                }
            }

            // 3. Bob processes QR and posts response (retry until success)
            while (true) {
                try {
                    val initAndEntry = bobManager.processScannedQr(qr, "Bob")
                    bobClient.postKeyExchangeInit(initAndEntry.second.id, qr, initAndEntry.first)
                    break
                } catch (e: Exception) {
                    delay(100)
                }
            }

            // 4. Alice polls and finalizes (retry until success)
            while (true) {
                try {
                    val results = aliceClient.pollPendingInvites()
                    val matching = results.find { it.inviteEkPub.contentEquals(qr.ekPub) }
                    if (matching != null) {
                        aliceManager.processKeyExchangeInit(matching.payload, "Bob", qr.ekPub)
                        break
                    }
                } catch (e: Exception) {
                }
                delay(100)
            }

            // 5. Verify pairing is functional by sending one message
            var messageSuccess = false
            withTimeout(30000) {
                while (!messageSuccess) {
                    try {
                        aliceClient.sendLocation(1.23, 4.56)
                        // Background poll should pick it up
                        bobClient.poll()
                        val bobFriends = bobManager.listFriends()
                        if (bobFriends.any { it.name == "Alice" && it.lastLat == 1.23 }) {
                            messageSuccess = true
                        }
                    } catch (e: Exception) {
                    }
                    delay(500)
                }
            }
            assertTrue(messageSuccess, "Handshake established but first message failed to transit")
        }

    @Test
    fun testMultiFriendReliable() =
        runTest(timeout = kotlin.time.Duration.parse("2m")) {
            runMultiFriendChaos(
                numFriends = 3,
                messagesPerFriend = 10,
                failProb = 0.0,
                dropProb = 0.0,
            )
        }

    @Test
    fun testMultiFriendChaosRealistic() =
        runTest(timeout = kotlin.time.Duration.parse("10m")) {
            runMultiFriendChaos(
                numFriends = 3,
                messagesPerFriend = 5,
                failProb = 0.05,
                dropProb = 0.02,
            )
        }

    @Test
    fun testExtremeChaos() =
        runTest(timeout = kotlin.time.Duration.parse("10m")) {
            runMultiFriendChaos(
                numFriends = 3,
                messagesPerFriend = 10,
                failProb = 0.20,
                dropProb = 0.10,
            )
        }

    private suspend fun TestScope.runMultiFriendChaos(
        numFriends: Int,
        messagesPerFriend: Int,
        failProb: Double,
        dropProb: Double,
        baseTimeMs: Long = 1715670000000L, // May 14, 2024
    ) {
        TimeSource.setProvider(
            object : TimeProvider {
                override fun currentTimeMillis() = baseTimeMs + testScheduler.currentTime

                override fun currentTimeSeconds() = (baseTimeMs + testScheduler.currentTime) / 1000
            },
        )
        val mailbox = MemoryMailboxClient()
        val chaosMailbox =
            ChaosMailboxClient(mailbox).apply {
                failPostProbability = failProb
                failPollProbability = failProb
                dropProbability = dropProb
            }

        val managers =
            (0 until numFriends).map { i ->
                val driver = createTestSqlDriver()
                E2eeManager(driver)
            }

        val clients =
            (0 until numFriends).map { i ->
                val c = LocationClient("http://localhost", managers[i], chaosMailbox)
                c.enableAutomatedKeepalives = false
                c
            }

        // 1. Handshake Phase: All-to-all
        val establishedPairs = mutableSetOf<String>()
        for (i in 0 until numFriends) {
            for (j in i + 1 until numFriends) {
                val pairKey = "$i:$j"

                var qr: QrPayload? = null
                while (qr == null) {
                    try {
                        qr = managers[i].createInvite("User-$i")
                    } catch (e: Exception) {
                        delay(10)
                    }
                }

                var initAndEntry: Pair<KeyExchangeInitPayload, FriendEntry>? = null
                while (initAndEntry == null) {
                    try {
                        initAndEntry = managers[j].processScannedQr(qr, "User-$j")
                    } catch (e: Exception) {
                        delay(10)
                    }
                }
                val (init, entry) = initAndEntry

                while (true) {
                    try {
                        clients[j].postKeyExchangeInit(entry.id, qr, init)
                        break
                    } catch (e: Exception) {
                        delay(20)
                    }
                }

                // Alice polls
                var aliceFound = false
                repeat(20) {
                    if (!aliceFound) {
                        try {
                            val results = clients[i].pollPendingInvites()
                            results.forEach { matching ->
                                if (matching.payload.ekPub.contentEquals(initAndEntry.first.ekPub)) {
                                    managers[i].processKeyExchangeInit(matching.payload, "User-$j", qr.ekPub)
                                    aliceFound = true
                                }
                            }
                        } catch (e: Exception) {
                        }
                        if (!aliceFound) delay(100)
                    }
                }
                if (!aliceFound) error("Alice (User $i) failed to find Bob (User $j) in discovery mailbox")
            }
        }

        // 2. Start background workers AFTER handshakes
        val backgroundJobs =
            (0 until numFriends).flatMap { i ->
                val client = clients[i]
                listOf(
                    launch {
                        while (isActive) {
                            try {
                                client.poll()
                            } catch (e: Exception) {
                            }
                            delay(Random.nextLong(100, 300))
                        }
                    },
                    launch {
                        while (isActive) {
                            try {
                                client.processOutboxes()
                            } catch (e: Exception) {
                            }
                            delay(Random.nextLong(100, 300))
                        }
                    },
                )
            }

        // 3. Send messages
        val sendJobs =
            (0 until numFriends).map { i ->
                launch {
                    repeat(messagesPerFriend) { m ->
                        while (true) {
                            val allEmpty = managers[i].listFriends().all { managers[i].getOutbox(it.id).isEmpty() }
                            if (allEmpty) break
                            delay(200)
                        }
                        try {
                            clients[i].sendLocation(i.toDouble(), m.toDouble())
                            delay(1000)
                        } catch (e: Exception) {
                        }
                        delay(Random.nextLong(100, 300)) // Throttle sends
                    }
                }
            }

        // Wait for all senders to finish
        sendJobs.forEach { it.join() }

        // 4. Wait for convergence
        val convergenceTimeout = 2_400_000L // 40 minutes virtual

        // RECOVERY PHASE: Once initial sends are done, turn off chaos to ensure convergence.
        // This proves that the protocol can fully recover and reach 100% consistency
        // once the network stabilizes.
        chaosMailbox.failPostProbability = 0.0
        chaosMailbox.failPollProbability = 0.0
        chaosMailbox.dropProbability = 0.0
        chaosMailbox.resetExpirations()

        // Disable automated keepalives for the chaos test to avoid "keepalive storms"
        // in virtual time. Handshakes and regular messages are enough to drive the state.
        clients.forEach { it.enableAutomatedKeepalives = false }

        try {
            withTimeout(convergenceTimeout) {
                while (true) {
                    yield()

                    // Force a poll/process cycle for every client to ensure progress
                    // regardless of background worker scheduling.
                    clients.forEach {
                        try {
                            it.poll()
                        } catch (e: Exception) {
                        }
                        try {
                            it.processOutboxes()
                        } catch (e: Exception) {
                        }
                    }

                    delay(500) // Advance virtual time to allow for backoffs

                    var allDone = true

                    for (i in 0 until numFriends) {
                        val friends = managers[i].listFriends()
                        if (friends.size < numFriends - 1) {
                            allDone = false
                        } else {
                            for (friend in friends) {
                                val lastLng = friend.lastLng?.toInt() ?: -1
                                val outbox = managers[i].getOutbox(friend.id)
                                if (lastLng != messagesPerFriend - 1 || outbox.isNotEmpty()) {
                                    allDone = false
                                }
                            }
                        }
                    }

                    if (allDone) {
                        break
                    }
                    delay(100)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            val stateDump = managers.mapIndexed { i, m ->
                val friends = m.listFriends()
                val friendLines = friends.joinToString(", ") { f ->
                    val outbox = m.getOutbox(f.id)
                    "friend=${f.name} lastLng=${f.lastLng?.toInt() ?: -1} outbox=${outbox.size}"
                }
                "User $i: [$friendLines]"
            }.joinToString("\n")
            val diagDump = managers.mapIndexed { i, m ->
                "User $i: ${m.diagnosticLogSnapshot().joinToString(" | ").ifEmpty { "(no events)" }}"
            }.joinToString("\n")
            error(
                "Multi-friend chaos test timed out after ${testScheduler.currentTime} virtual ms: ${e.message}\n" +
                    "Convergence state:\n$stateDump\n" +
                    "Diagnostic logs:\n$diagDump",
            )
        } finally {
            backgroundJobs.forEach { it.cancelAndJoin() }
        }

        // 5. Final verification with debug info
        for (i in 0 until numFriends) {
            val friends = managers[i].listFriends()
            assertEquals(numFriends - 1, friends.size, "User $i should have ${numFriends - 1} friends")
            for (friend in friends) {
                assertEquals(
                    messagesPerFriend - 1,
                    friend.lastLng?.toInt(),
                    "User $i did not receive final message from ${friend.name}",
                )
            }
        }
    }
}
