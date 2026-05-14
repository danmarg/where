package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.request.delete
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import net.af0.where.e2ee.MailboxMessage
import net.af0.where.e2ee.MailboxPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailboxTest {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type"; encodeDefaults = true }

    private fun isLocalhost(): Boolean = System.getenv("WHERE_TEST_SERVER_URL")?.contains("localhost") != false

    // ---------------------------------------------------------------------------
    // POST /inbox/{token}
    // ---------------------------------------------------------------------------

    @Test
    fun `POST inbox returns 204`() {
        if (!isLocalhost()) return // Skip when running against production
        testApplication {
            application { module(ServerState()) }
            val response =
                client.post("/inbox/aabbccddeeff0011") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"EncryptedMessage","v":1,"envelope":"AAEC","ct":"AAEC"}""")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `POST inbox with empty body returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val response =
                client.post("/inbox/aabbccddeeff0011") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `POST inbox with non-JSON body returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val response =
                client.post("/inbox/sometoken") {
                    contentType(ContentType.Application.Json)
                    setBody("not json")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    // ---------------------------------------------------------------------------
    // GET /inbox/{token}
    // ---------------------------------------------------------------------------

    @Test
    fun `GET inbox for unknown token returns empty array`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val response = client.get("/inbox/unknowntoken")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            val body = response.bodyAsText()
            val arr = json.decodeFromString<List<MailboxMessage>>(body)
            assertTrue(arr.isEmpty(), "Expected empty array for unknown token, got: $body")
        }
    }

    @Test
    fun `GET inbox drains messages posted to that token`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "deadbeef01234567"
            val payload1 = """{"type":"EncryptedMessage","v":1,"envelope":"AA==","ct":"AA=="}"""
            val payload2 = """{"type":"EncryptedMessage","v":1,"envelope":"BB==","ct":"BB=="}"""

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(payload1)
            }
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(payload2)
            }

            val response = client.get("/inbox/$token")
            assertEquals(HttpStatusCode.OK, response.status)
            val arr = json.decodeFromString<List<MailboxMessage>>(response.bodyAsText())
            assertEquals(2, arr.size)
        }
    }

    @Test
    fun `GET inbox is non-destructive - second GET returns same messages`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "cafebabe12345678"
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedMessage","v":1,"envelope":"AA==","ct":"AA=="}""")
            }

            val first = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, first.size)

            val second = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, second.size, "Second GET should return same messages")
        }
    }

    @Test
    fun `DELETE inbox removes specified messages`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-1"
            repeat(5) { i ->
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"EncryptedMessage","v":1,"envelope":"${i}","ct":"AA=="}""")
                }
            }

            val all = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$token").bodyAsText())
            val toDelete = all.take(3).map { it.id }

            val deleteResponse = client.delete("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(toDelete))
            }
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val remaining = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$token").bodyAsText())
            assertEquals(2, remaining.size, "Should have 2 messages remaining")
        }
    }

    @Test
    fun `DELETE inbox with invalid body returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-3"
            val missingBody = client.delete("/inbox/$token")
            assertEquals(HttpStatusCode.BadRequest, missingBody.status)

            val invalidJson = client.delete("/inbox/$token") {
                 contentType(ContentType.Application.Json)
                 setBody("not a list")
            }
            assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
        }
    }

    @Test
    fun `GET inbox for different tokens are independent`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val tokenA = "aaaaaaaaaaaaaaaa"
            val tokenB = "bbbbbbbbbbbbbbbb"

            client.post("/inbox/$tokenA") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedMessage","v":1,"envelope":"AA==","ct":"AA=="}""")
            }

            val responseA = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$tokenA").bodyAsText())
            val responseB = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$tokenB").bodyAsText())

            assertEquals(1, responseA.size)
            assertTrue(responseB.isEmpty())
        }
    }

    @Test
    fun `GET inbox response is identical for unknown and empty tokens`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val neverUsed = "0000000000000000"
            val posted = "1111111111111111"

            // Post then drain 'posted' to make it an empty known token
            client.post("/inbox/$posted") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedMessage","v":1,"envelope":"AA==","ct":"AA=="}""")
            }
            val msgs = json.decodeFromString<List<MailboxMessage>>(client.get("/inbox/$posted").bodyAsText())
            client.delete("/inbox/$posted") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(msgs.map { it.id }))
            }

            val unknownResponse = client.get("/inbox/$neverUsed").bodyAsText()
            val emptyResponse = client.get("/inbox/$posted").bodyAsText()

            assertEquals(unknownResponse, emptyResponse, "Unknown and empty-inbox responses must be identical")
        }
    }

    @Test
    fun `GET inbox timing is normalized`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "timing-test-token"

            // Baseline should be ~50ms. We check it's at least 45ms to account for system precision.
            val start1 = System.currentTimeMillis()
            client.get("/inbox/nonexistent")
            val elapsed1 = System.currentTimeMillis() - start1
            assertTrue(elapsed1 >= 45, "Poll for nonexistent token took $elapsed1 ms, expected >= 50ms")

            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedMessage","v":1,"envelope":"AA==","ct":"AA=="}""")
            }
            val start2 = System.currentTimeMillis()
            client.get("/inbox/$token")
            val elapsed2 = System.currentTimeMillis() - start2
            assertTrue(elapsed2 >= 45, "Poll for existing token took $elapsed2 ms, expected >= 50ms")
        }
    }
}
