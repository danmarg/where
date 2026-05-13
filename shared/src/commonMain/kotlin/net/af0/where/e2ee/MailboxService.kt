package net.af0.where.e2ee

/**
 * High-level service for mailbox network operations.
 * Centralizes all network interactions with the server.
 */
class MailboxService(
    private val baseUrl: String,
    private val client: MailboxClient = KtorMailboxClient,
) {
    /**
     * Posts a message to a specific mailbox token.
     */
    suspend fun post(token: String, payload: MailboxPayload) {
        client.post(baseUrl, token, payload)
    }

    /**
     * Polls a specific mailbox token for messages.
     */
    suspend fun poll(token: String): List<MailboxPayload> {
        return client.poll(baseUrl, token)
    }

    /**
     * Acknowledges receipt of messages from a mailbox token.
     */
    suspend fun ack(token: String, count: Int) {
        client.ack(baseUrl, token, count)
    }
}
