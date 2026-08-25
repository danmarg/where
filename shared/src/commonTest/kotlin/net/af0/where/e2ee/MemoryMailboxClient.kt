package net.af0.where.e2ee

/** In-memory [MailboxClient] fake shared by tests that don't need [E2eeChaosTest]'s locking/dedup. */
class MemoryMailboxClient : MailboxClient {
    val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()

    /** Every payload ever posted, in order, unaffected by later ACKs - lets tests inspect send history/timing. */
    val allPosted = mutableListOf<MailboxPayload>()

    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        allPosted.add(payload)
        mailboxes.getOrPut(token) { mutableListOf() }.add(payload)
    }

    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> = mailboxes[token] ?: emptyList()

    override suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {
        mailboxes[token]?.removeAll { it.msgId == msgId }
    }

    override suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {
        mailboxes[token]?.removeAll { it.msgId in msgIds }
    }
}
