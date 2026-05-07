package net.af0.where.e2ee

import kotlinx.coroutines.delay
import kotlin.random.Random

class ProcessKilledException : Exception("Simulated process kill mid-operation")

class ChaosTimeProvider(private var offsetMillis: Long = 0) : TimeProvider {
    fun addOffset(millis: Long) {
        offsetMillis += millis
    }

    override fun currentTimeMillis(): Long = platformCurrentTimeMillis() + offsetMillis
    override fun currentTimeSeconds(): Long = (platformCurrentTimeMillis() + offsetMillis) / 1000
}

class ChaosStorage(private val storage: E2eeStorage) : E2eeStorage {
    var failNextWrite = false
    var failWriteProbability = 0.0

    override fun getString(key: String): String? = storage.getString(key)

    override fun putString(key: String, value: String) {
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
    var reorderProbability = 0.0
    var dropProbability = 0.0
    var stealthDropProbability = 0.0
    var maxLatencyMs = 0L
    var expireMailboxProbability = 0.0
    var expireMailboxStatusCode = 404 // 404 or 410
    var killProbability = 0.0

    // When true: the message IS delivered to the server (relay) but the HTTP response is
    // "lost" and a NetworkException is thrown to the caller. This simulates the production
    // failure where a POST reaches the server (message stored) but the client never receives
    // the 204, treats it as a failure, and retries — depositing duplicate messages.
    var stealthPost = false
    private val outboxBuffer = mutableListOf<Pair<String, MailboxPayload>>()
    private val expiredTokens = mutableSetOf<String>()

    override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
        applyLatency()
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
            // Flush buffer first to simulate late delivery to the correct original token
            if (outboxBuffer.isNotEmpty()) {
                outboxBuffer.forEach { (t, p) -> client.post(baseUrl, t, p) }
                outboxBuffer.clear()
            }
            if (Random.nextDouble() >= dropProbability) {
                if (Random.nextDouble() < stealthDropProbability) {
                    return // Simulate success but drop on server
                }
                client.post(baseUrl, token, payload)  // may throw 429 if queue full — propagates as-is
                if (stealthPost) throw NetworkException("Simulated timeout: POST delivered but response lost")
            }
        }
        checkKill()
    }

    override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
        applyLatency()
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
        // Simulate reordering by shuffling retrieved messages
        if (Random.nextDouble() < reorderProbability) {
            messages.shuffle()
        }

        val result = if (corruptNextPayload || Random.nextDouble() < corruptPayloadProbability) {
            corruptNextPayload = false
            messages.map { msg ->
                if (msg is EncryptedMessagePayload) {
                    msg.copy(ct = msg.ct.map { (it.toInt() xor 0xFF).toByte() }.toByteArray())
                } else {
                    msg
                }
            }
        } else {
            messages
        }
        checkKill()
        return result
    }

    override suspend fun ack(baseUrl: String, token: String, count: Int) {
        applyLatency()
        checkKill()
        if (expiredTokens.contains(token)) {
            throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
        }
        client.ack(baseUrl, token, count)
        checkKill()
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
        expiredTokens.clear()
    }
}
