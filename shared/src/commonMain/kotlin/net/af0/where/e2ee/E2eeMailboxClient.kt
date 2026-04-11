package net.af0.where.e2ee

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object E2eeMailboxClient {
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val client =
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }

    /**
     * POST a message to a mailbox address.
     * @param baseUrl Server base URL (e.g. "http://10.0.2.2:8080")
     * @param token Hex-encoded mailbox address (routing token or discovery token).
     * @param payload The encrypted or handshake payload to send.
     */
    suspend fun post(
        baseUrl: String,
        token: String,
        payload: MailboxPayload,
    ) {
        try {
            val response =
                client.post("$baseUrl/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.OK) {
                throw ServerException(response.status.value, "Failed to post to mailbox")
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
    suspend fun poll(
        baseUrl: String,
        token: String,
    ): List<MailboxPayload> {
        try {
            val response = client.get("$baseUrl/inbox/$token")
            if (response.status != HttpStatusCode.OK) {
                throw ServerException(response.status.value, "Failed to poll mailbox")
            }
            return response.body()
        } catch (e: Exception) {
            throw mapException(e)
        }
    }

    private fun mapException(e: Exception): Exception {
        return when (e) {
            is ConnectTimeoutException, is HttpRequestTimeoutException, is SocketTimeoutException ->
                TimeoutException("Network timeout", e)
            is io.ktor.utils.io.errors.IOException -> {
                val msg = e.message ?: "Connection failed"
                if (msg.contains("resolve", ignoreCase = true) || msg.contains("connect", ignoreCase = true)) {
                    ConnectException(msg, e)
                } else {
                    NetworkException(msg, e)
                }
            }
            is WhereException -> e
            else -> NetworkException(e.message ?: "Unknown network error", e)
        }
    }
}
