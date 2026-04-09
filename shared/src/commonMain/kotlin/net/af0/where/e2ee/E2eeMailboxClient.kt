package net.af0.where.e2ee

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
        val response =
            client.post("$baseUrl/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.OK) {
            throw Exception("Failed to post to mailbox: ${response.status}")
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
        val response = client.get("$baseUrl/inbox/$token")
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to poll mailbox: ${response.status}")
        }
        return response.body()
    }
}
