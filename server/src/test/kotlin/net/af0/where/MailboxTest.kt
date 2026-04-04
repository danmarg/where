package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailboxTest {
    private val json = Json { ignoreUnknownKeys = true }

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
                    setBody("""{"type":"EncryptedLocation","epoch":1,"seq":"1","ct":"AAEC"}""")
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
            assertEquals(ContentType.Application.Json.withCharset(Charsets.UTF_8), response.contentType())
            val body = response.bodyAsText()
            val arr = json.decodeFromString<JsonArray>(body)
            assertTrue(arr.isEmpty(), "Expected empty array for unknown token, got: $body")
        }
    }

    @Test
    fun `GET inbox drains messages posted to that token`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "deadbeef01234567"
            val payload1 = """{"type":"EncryptedLocation","epoch":1,"seq":"1","ct":"AA=="}"""
            val payload2 = """{"type":"EncryptedLocation","epoch":1,"seq":"2","ct":"BB=="}"""

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
            val arr = json.decodeFromString<JsonArray>(response.bodyAsText())
            assertEquals(2, arr.size)
        }
    }

    @Test
    fun `GET inbox is destructive - second GET returns empty`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "cafebabe12345678"
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedLocation","epoch":1,"seq":"1","ct":"AA=="}""")
            }

            val first = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, first.size)

            val second = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertTrue(second.isEmpty(), "Second GET should return empty array")
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
                setBody("""{"type":"EncryptedLocation","epoch":1,"seq":"1","ct":"AA=="}""")
            }

            val responseA = json.decodeFromString<JsonArray>(client.get("/inbox/$tokenA").bodyAsText())
            val responseB = json.decodeFromString<JsonArray>(client.get("/inbox/$tokenB").bodyAsText())

            assertEquals(1, responseA.size)
            assertTrue(responseB.isEmpty())
        }
    }

    // ---------------------------------------------------------------------------
    // Constant-time invariant: unknown tokens look like empty tokens (§7.2)
    // ---------------------------------------------------------------------------

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
                setBody("""{"type":"EncryptedLocation","epoch":1,"seq":"1","ct":"AA=="}""")
            }
            client.get("/inbox/$posted") // drain

            val unknownResponse = client.get("/inbox/$neverUsed").bodyAsText()
            val emptyResponse = client.get("/inbox/$posted").bodyAsText()

            assertEquals(unknownResponse, emptyResponse, "Unknown and empty-inbox responses must be identical")
        }
    }
}
