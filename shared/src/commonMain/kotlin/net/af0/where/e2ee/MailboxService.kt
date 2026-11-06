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
    suspend fun post(
        token: String,
        payload: MailboxPayload,
    ) {
        request { client.post(baseUrl, token, payload) }
    }

    /**
     * Polls a specific mailbox token for messages.
     */
    suspend fun poll(token: String): List<MailboxPayload> {
        return request { client.poll(baseUrl, token) }
    }

    /**
     * Acknowledges receipt of a specific message by ID.
     */
    suspend fun ackId(
        token: String,
        msgId: String,
    ) {
        request { client.ackId(baseUrl, token, msgId) }
    }

    /**
     * Acknowledges receipt of multiple messages by IDs.
     */
    suspend fun ackIds(
        token: String,
        msgIds: List<String>,
    ) {
        request { client.ackIds(baseUrl, token, msgIds) }
    }

    // A wall-clock timeout (withWallClockTimeout) is a CancellationException so that it can stop
    // the request, but to callers it is an ordinary failure like any other network error: one
    // escaping as a cancellation would cancel the caller's whole scope (a poll, a fan-out).
    private suspend fun <T> request(block: suspend () -> T): T =
        try {
            block()
        } catch (e: WallClockTimeoutCancellationException) {
            throw MailboxTimeoutException(e)
        }
}

/** A mailbox request that timed out (see [MailboxService]). */
class MailboxTimeoutException(cause: Throwable) : Exception("Mailbox request timed out", cause)
