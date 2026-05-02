package net.af0.where.e2ee

import kotlin.random.Random

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
    var failPollProbability = 0.0
    var corruptNextPayload = false
    var corruptPayloadProbability = 0.0
    var reorderProbability = 0.0
    private val outboxBuffer = mutableListOf<MailboxPayload>()

    override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
        if (failNextPost || Random.nextDouble() < failPostProbability) {
            failNextPost = false
            throw NetworkException("Simulated network failure on POST")
        }
        if (Random.nextDouble() < reorderProbability) {
            outboxBuffer.add(payload)
        } else {
            // Send buffer if available to simulate late delivery
            if (outboxBuffer.isNotEmpty()) {
                outboxBuffer.forEach { client.post(baseUrl, token, it) }
                outboxBuffer.clear()
            }
            client.post(baseUrl, token, payload)
        }
    }

    override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
        if (failNextPoll || Random.nextDouble() < failPollProbability) {
            failNextPoll = false
            throw NetworkException("Simulated network failure on POLL")
        }
        val messages = client.poll(baseUrl, token).toMutableList()
        // Simulate reordering by shuffling retrieved messages
        if (Random.nextDouble() < reorderProbability) {
            messages.shuffle()
        }
        
        return if (corruptNextPayload || Random.nextDouble() < corruptPayloadProbability) {
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
    }

    override suspend fun ack(baseUrl: String, token: String, count: Int) {
        client.ack(baseUrl, token, count)
    }
}
