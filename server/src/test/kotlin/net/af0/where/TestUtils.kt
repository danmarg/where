package net.af0.where

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import net.af0.where.e2ee.MailboxClient
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.ServerException

val GlobalTestJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class MailboxClientAdapter(private val client: HttpClient) : MailboxClient {
    override suspend fun post(baseUrl: String, token: String, payload: MailboxPayload) {
        val response = client.post("/inbox/$token") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) {
            throw ServerException(response.status.value, "Failed to post to mailbox")
        }
    }

    override suspend fun poll(baseUrl: String, token: String): List<MailboxPayload> {
        val response = client.get("/inbox/$token")
        if (!response.status.isSuccess()) {
            throw ServerException(response.status.value, "Failed to poll mailbox")
        }
        return response.body()
    }

    override suspend fun ack(baseUrl: String, token: String, count: Int) {
        if (count <= 0) return
        val response = client.delete("/inbox/$token") {
            parameter("n", count)
        }
        if (!response.status.isSuccess()) {
            throw ServerException(response.status.value, "ACK failed for token $token")
        }
    }
}

fun HttpClient.toMailboxClient(): MailboxClient = MailboxClientAdapter(this)
