package net.af0.where.e2ee

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/**
 * interface for the mailbox service transport (§9).
 */
interface MailboxClient {
    suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    )

    suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload>

    /**
     * Confirm receipt of a specific message by [msgId].
     */
    suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {}

    /**
     * Confirm receipt of specific messages by [msgIds].
     */
    suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {}
}

object KtorMailboxClient : MailboxClient {
    /**
     * Coarse suspension-recovery backstop for mailbox requests. Must stay comfortably above
     * the underlying Ktor HttpClient's requestTimeoutMillis/socketTimeoutMillis (30s, see
     * HttpClientFactory.kt) so that a genuine slow network trips the client's own, more specific
     * timeout exception first. If this value is too close to the Ktor timeout, ordinary jitter
     * races both timers to the same deadline and this generic wall-clock timeout wins instead,
     * masking the real cause.
     */
    private const val MAILBOX_WALL_CLOCK_TIMEOUT_MS = 60_000L

    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val client = createHttpClient(json)

    /**
     * POST a message to a mailbox address.
     * @param baseUrl Server base URL (e.g. "http://10.0.2.2:8080")
     * @param token Hex-encoded mailbox address (routing token or discovery token).
     * @param payload The encrypted or handshake payload to send.
     */
    override suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        try {
            withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                val url = "$baseUrl/inbox/$token/${payload.msgId}"
                val response =
                    client.put(url) {
                        contentType(ContentType.Application.Json)
                        setBody(payload)
                    }
                if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.OK) {
                    throw ServerException(response.status.value, "Failed to post to mailbox")
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    /**
     * GET all pending messages for a mailbox address.
     * @param baseUrl Server base URL.
     * @param token Hex-encoded mailbox address.
     * @return List of payloads, or empty if none.
     */
    override suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> {
        try {
            return withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                val response = client.get("$baseUrl/inbox/$token")
                if (response.status != HttpStatusCode.OK) {
                    throw ServerException(response.status.value, "Failed to poll mailbox")
                }
                response.body()
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    override suspend fun ackId(
        baseUrl: String,
        token: String,
        msgId: String,
    ) {
        try {
            withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                val response = client.delete("$baseUrl/inbox/$token/$msgId")
                if (!response.status.isSuccess()) {
                    throw ServerException(response.status.value, "ACK failed for msgId $msgId")
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    override suspend fun ackIds(
        baseUrl: String,
        token: String,
        msgIds: List<String>,
    ) {
        if (msgIds.isEmpty()) return
        try {
            withWallClockTimeout(MAILBOX_WALL_CLOCK_TIMEOUT_MS) {
                val response =
                    client.delete("$baseUrl/inbox/$token") {
                        parameter("ids", msgIds.joinToString(","))
                    }
                if (!response.status.isSuccess()) {
                    throw ServerException(response.status.value, "Batch ACK failed for token $token")
                }
            }
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    private fun mapException(e: Exception): Exception {
        return when (e) {
            is CancellationException -> throw e
            is ConnectTimeoutException, is HttpRequestTimeoutException, is SocketTimeoutException ->
                TimeoutException("Network timeout", e)
            is IOException -> ConnectException(e.message ?: "Connection failed", e)
            is WhereException -> e
            else -> NetworkException(e.message ?: "Unknown network error", e)
        }
    }
}

internal expect fun createHttpClient(json: Json): HttpClient
