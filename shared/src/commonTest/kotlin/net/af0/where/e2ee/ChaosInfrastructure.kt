package net.af0.where.e2ee

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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
        if (failNextWrite || Random.nextDouble() < failWriteProbability) {
            failNextWrite = false
            throw Exception("Simulated disk write failure")
        }
        storage.putString(key, value)
    }
}

class ChaosMailboxClient(private val client: MailboxClient) : MailboxClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    var failNextPost = false
    var failPostProbability = 0.0
    var failNextPoll = false
    var failPoll = false
    var failPollProbability = 0.0
    var corruptNextPayload = false
    var corruptPayloadProbability = 0.0
    var corruptNextPayloadOnly = false
    var corruptPayloadOnlyProbability = 0.0
    var reorderProbability = 0.0
    var dropProbability = 0.0
    var stealthDropProbability = 0.0
    var maxLatencyMs = 0L
    var expireMailboxProbability = 0.0
    var expireMailboxStatusCode = 404
    var killProbability = 0.0

    var stealthPost = false
    private val outboxBuffer = mutableListOf<Pair<String, MailboxPayload>>()
    private val expiredTokens = mutableSetOf<String>()
    private val mutex = Mutex()

    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) = mutex.withLock {
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
            if (outboxBuffer.isNotEmpty()) {
                outboxBuffer.forEach { (t, p) -> client.post(baseUrl, t, p) }
                outboxBuffer.clear()
            }
            if (Random.nextDouble() >= dropProbability) {
                if (Random.nextDouble() < stealthDropProbability) {
                    return@withLock
                }
                client.post(baseUrl, token, payload)
                if (stealthPost) throw NetworkException("Simulated timeout: POST delivered but response lost")
            }
        }
        checkKill()
    }

    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxMessage> = mutex.withLock {
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
        if (Random.nextDouble() < reorderProbability) {
            messages.shuffle()
        }

        val result =
            if (corruptNextPayload || Random.nextDouble() < corruptPayloadProbability) {
                corruptNextPayload = false
                messages.map { msg ->
                    val p = json.decodeFromJsonElement(MailboxPayload.serializer(), msg.payload)
                    if (p is EncryptedMessagePayload) {
                        val newPayload = p.copy(ct = p.ct.map { (it.toInt() xor 0xFF).toByte() }.toByteArray())
                        msg.copy(payload = json.encodeToJsonElement(MailboxPayload.serializer(), newPayload))
                    } else {
                        msg
                    }
                }
            } else if (corruptNextPayloadOnly || Random.nextDouble() < corruptPayloadOnlyProbability) {
                corruptNextPayloadOnly = false
                messages.map { msg ->
                    val p = json.decodeFromJsonElement(MailboxPayload.serializer(), msg.payload)
                    if (p is EncryptedMessagePayload) {
                        val newPayload = p.copy(ct = p.ct.map { (it.toInt() xor 0xAA).toByte() }.toByteArray())
                        msg.copy(payload = json.encodeToJsonElement(MailboxPayload.serializer(), newPayload))
                    } else {
                        msg
                    }
                }
            } else {
                messages
            }
        checkKill()
        return@withLock result
    }

    override suspend fun ack(
        baseUrl: String,
        token: String,
        ids: List<String>,
    ) = mutex.withLock {
        applyLatency()
        checkKill()
        if (expiredTokens.contains(token)) {
            throw ServerException(expireMailboxStatusCode, "Simulated mailbox expiration")
        }
        client.ack(baseUrl, token, ids)
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

class MemoryStorage : RawKeyValueStorage {
    private val data = mutableMapOf<String, String>()
    override fun getString(key: String): String? = data[key]
    override fun putString(key: String, value: String) {
        data[key] = value
    }
}

data class MailboxEntry(val id: String, val payload: kotlinx.serialization.json.JsonElement, val expiresAt: Long)

class MockMailbox {
    private val mailboxes = mutableMapOf<String, MutableList<MailboxEntry>>()

    fun post(token: String, payload: kotlinx.serialization.json.JsonElement): Boolean {
        val digest = sha256(payload.toString().encodeToByteArray())
        val id = digest.toHex()
        val queue = mailboxes.getOrPut(token) { mutableListOf() }
        if (queue.any { it.id == id }) return true
        queue.add(MailboxEntry(id, payload, currentTimeSeconds() * 1000 + 3600000))
        return true
    }

    fun drain(token: String): List<MailboxMessage> {
        val queue = mailboxes[token] ?: return emptyList()
        return queue.map { MailboxMessage(it.id, it.payload) }
    }

    fun delete(token: String, ids: List<String>): Int {
        val queue = mailboxes[token] ?: return 0
        val before = queue.size
        queue.removeAll { it.id in ids }
        return before - queue.size
    }
}
