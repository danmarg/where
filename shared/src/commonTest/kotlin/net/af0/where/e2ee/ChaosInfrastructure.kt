package net.af0.where.e2ee

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

class ProcessKilledException : Exception("Simulated process kill mid-operation")

class ChaosTimeProvider(private var offsetMillis: Long = 0) : TimeProvider {
    private val mutex = Mutex()

    suspend fun addOffset(millis: Long) = mutex.withLock {
        offsetMillis += millis
    }

    override fun currentTimeMillis(): Long = platformCurrentTimeMillis() + offsetMillis

    override fun currentTimeSeconds(): Long = (platformCurrentTimeMillis() + offsetMillis) / 1000
}

class ChaosStorage(private val storage: RawKeyValueStorage) : RawKeyValueStorage {
    var failNextWrite = false
    var failWriteProbability = 0.0

    override fun getString(key: String): String? = storage.getString(key)

    override fun putString(
        key: String,
        value: String,
    ) {
        // storage.putString in RawKeyValueStorage is NOT suspend, but MemoryStorage is synchronous.
        // Thread safety is handled by underlying implementation or plain map access if single-threaded.
        // For chaos flags, we accept slight races as they are benign in this context.
        if (failNextWrite || Random.nextDouble() < failWriteProbability) {
            failNextWrite = false
            throw Exception("Simulated disk write failure")
        }
        storage.putString(key, value)
    }
}

class ChaosMailboxClient(private val client: MailboxClient) : MailboxClient {
    var failNextPost = false
    var failPostProbability = 0.0
    var failNextPoll = false
    var failPoll = false
    var failPollProbability = 0.0
    var corruptNextPayload = false
    var corruptPayloadProbability = 0.0
    var corruptNextPayloadOnly = false // Corrupt CT only, leave envelope intact
    var corruptPayloadOnlyProbability = 0.0
    var reorderProbability = 0.0
    var dropProbability = 0.0
    var maxLatencyMs = 0L
    var expireMailboxProbability = 0.0
    var expireMailboxStatusCode = 404 // 404 or 410
    var killProbability = 0.0

    private val outboxBuffer = mutableListOf<Pair<String, MailboxPayload>>()
    private val expiredTokens = mutableSetOf<String>()
    private val mutex = Mutex()

    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        applyLatency()
        mutex.withLock {
            checkKill()
            if (expiredTokens.contains(token) || Random.nextDouble() < expireMailboxProbability) {
                expiredTokens.add(token)
                throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
            }
            if (failNextPost || Random.nextDouble() < failPostProbability) {
                failNextPost = false
                throw NetworkException("Simulated network failure on POST")
            }
            if (Random.nextDouble() < reorderProbability) {
                outboxBuffer.add(token to payload)
            } else {
                if (outboxBuffer.isNotEmpty()) {
                    outboxBuffer.forEach { (t, p) -> client.post(baseUrl, t, p) }
                    outboxBuffer.clear()
                }
                if (Random.nextDouble() < dropProbability) {
                    throw NetworkException("Simulated silent network drop (timeout)")
                }
                client.post(baseUrl, token, payload)
            }
            checkKill()
        }
    }

    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> {
        applyLatency()
        return mutex.withLock {
            checkKill()
            if (expiredTokens.contains(token) || Random.nextDouble() < expireMailboxProbability) {
                expiredTokens.add(token)
                throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
            }
            if (failNextPoll || failPoll || Random.nextDouble() < failPollProbability) {
                failNextPoll = false
                throw NetworkException("Simulated network failure on POLL")
            }
            val messages = client.poll(baseUrl, token).toMutableList()
            if (Random.nextDouble() < reorderProbability) {
                messages.shuffle()
            }

            val result =
                if (corruptNextPayload || Random.nextDouble() < corruptPayloadProbability) {
                    corruptNextPayload = false
                    messages.map { msg ->
                        if (msg is EncryptedMessagePayload) {
                            msg.copy(ct = msg.ct.map { (it.toInt() xor 0xFF).toByte() }.toByteArray())
                        } else {
                            msg
                        }
                    }
                } else if (corruptNextPayloadOnly || Random.nextDouble() < corruptPayloadOnlyProbability) {
                    corruptNextPayloadOnly = false
                    messages.map { msg ->
                        if (msg is EncryptedMessagePayload) {
                            msg.copy(ct = msg.ct.map { (it.toInt() xor 0xAA).toByte() }.toByteArray())
                        } else {
                            msg
                        }
                    }
                } else {
                    messages
                }
            checkKill()
            result
        }
    }

    override suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {
        applyLatency()
        mutex.withLock {
            checkKill()
            if (expiredTokens.contains(token)) {
                throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
            }
            client.ackId(baseUrl, token, msgId)
            checkKill()
        }
    }

    override suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {
        applyLatency()
        mutex.withLock {
            checkKill()
            if (expiredTokens.contains(token)) {
                throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
            }
            client.ackIds(baseUrl, token, msgIds)
            checkKill()
        }
    }

    private suspend fun applyLatency() {
        if (maxLatencyMs > 0) {
            delay(Random.nextLong(maxLatencyMs))
        }
    }

    private fun checkKill() {
        if (Random.nextDouble() < killProbability) {
            throw ProcessKilledException()
        }
    }

    fun resetExpirations() {
        // Not using mutex here as it's typically called during recovery/setup
        expiredTokens.clear()
    }
}
