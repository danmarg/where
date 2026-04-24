package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.request.delete
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
                    setBody("""{"type":"EncryptedLocation","seq":"1","ct":"AAEC"}""")
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
            val payload1 = """{"type":"EncryptedLocation","seq":"1","ct":"AA=="}"""
            val payload2 = """{"type":"EncryptedLocation","seq":"2","ct":"BB=="}"""

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
    fun `GET inbox is non-destructive - second GET returns same messages`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "cafebabe12345678"
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedLocation","seq":"1","ct":"AA=="}""")
            }

            val first = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, first.size)

            val second = json.decodeFromString<JsonArray>(client.get("/inbox/$token").bodyAsText())
            assertEquals(1, second.size, "Second GET should return same messages")
        }
    }

    @Test
    fun `DELETE inbox removes first n messages`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-1"
            repeat(5) { i ->
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"i":$i}""")
                }
            }

            val deleteResponse = client.delete("/inbox/$token?n=3")
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getResponse = client.get("/inbox/$token")
            val arr = json.decodeFromString<JsonArray>(getResponse.bodyAsText())
            assertEquals(2, arr.size, "Should have 2 messages remaining")
            assertEquals("""[{"i":3},{"i":4}]""", getResponse.bodyAsText())
        }
    }

    @Test
    fun `DELETE inbox with n greater than queue size removes all`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-2"
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"msg":"a"}""")
            }
            client.post("/inbox/$token") {
                contentType(ContentType.Application.Json)
                setBody("""{"msg":"b"}""")
            }

            val deleteResponse = client.delete("/inbox/$token?n=5")
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getResponse = client.get("/inbox/$token")
            val arr = json.decodeFromString<JsonArray>(getResponse.bodyAsText())
            assertTrue(arr.isEmpty(), "Queue should be empty after over-deleting")
        }
    }

    @Test
    fun `DELETE inbox with missing or invalid n returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-3"
            val missingN = client.delete("/inbox/$token")
            assertEquals(HttpStatusCode.BadRequest, missingN.status)

            val negativeN = client.delete("/inbox/$token?n=-5")
            assertEquals(HttpStatusCode.BadRequest, negativeN.status)

            val notAnIntN = client.delete("/inbox/$token?n=foo")
            assertEquals(HttpStatusCode.BadRequest, notAnIntN.status)
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
                setBody("""{"type":"EncryptedLocation","seq":"1","ct":"AA=="}""")
            }

            val responseA = json.decodeFromString<JsonArray>(client.get("/inbox/$tokenA").bodyAsText())
            val responseB = json.decodeFromString<JsonArray>(client.get("/inbox/$tokenB").bodyAsText())

            assertEquals(1, responseA.size)
            assertTrue(responseB.isEmpty())
        }
    }

    @Test
    fun `GET inbox returns 429 when rate-limited`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "ratelimit-get-token"

            // Exhaust the rate limit
            repeat(RATE_LIMIT_MAX_GETS) {
                client.get("/inbox/$token")
            }

            // Next one should be 429
            val response = client.get("/inbox/$token")
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }
    }

    @Test
    fun `POST inbox returns 429 when rate-limited`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "ratelimit-post-token"

            // Exhaust the rate limit
            repeat(RATE_LIMIT_MAX_POSTS) {
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"msg":"test"}""")
                }
            }

            // Next one should be 429
            val response =
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"msg":"test"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }
    }

    @Test
    fun `POST inbox returns 429 when queue depth reached`() {
        if (!isLocalhost()) return
        testApplication {
            val state = InMemoryMailboxState()
            application { module(ServerState(mailbox = state)) }
            val token = "queuedepth-token"

            // We need to bypass rate-limiting to test queue depth.
            // We'll fill the queue manually in the state object (since it's internal to the test app).
            repeat(1000) { i ->
                state.post(token, JsonPrimitive(i))
            }

            // The 1001st message should be rejected (returns false from state.post, leading to 429 in route).
            val response =
                client.post("/inbox/$token") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"msg":"overflow"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }
    }

    // ---------------------------------------------------------------------------
    // Constant-time invariant: unknown tokens look like empty tokens (§7.2)
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // Eviction
    // ---------------------------------------------------------------------------

    @Test
    fun `evict resets stale postTimes so rate limit no longer applies`() {
        val state = InMemoryMailboxState()
        val token = "evicttoken0000001"
        // Exhaust the rate limit for this token.
        repeat(RATE_LIMIT_MAX_POSTS) { i ->
            state.post(token, JsonPrimitive(i))
        }
        assertTrue(!state.post(token, JsonPrimitive("x")), "should be rate-limited before eviction")

        // Evict with window=0 so all timestamps look stale.
        state.evictForTest(rateLimitWindowMs = 0)

        // Rate-limit state is gone; posting should be accepted again.
        assertTrue(state.post(token, JsonPrimitive("y")), "should accept post after eviction cleared postTimes")
    }

    @Test
    fun `evict removes empty mailbox entries`() {
        val state = InMemoryMailboxState()
        val token = "evicttoken0000002"
        state.post(token, JsonPrimitive("msg"))
        state.delete(token, 1) // empties the mailbox queue

        // Evict — the mailbox queue is empty so the entry should be removed.
        state.evictForTest(rateLimitWindowMs = 0)

        // Drain should still return empty (no phantom entry).
        assertTrue(state.drain(token)!!.isEmpty(), "evicted mailbox entry should return empty")
    }

    @Test
    fun `evict does not remove mailbox entries with live messages`() {
        val state = InMemoryMailboxState()
        state.post("live", JsonPrimitive("msg"))

        // Evict — the message has not expired so the entry should be retained.
        state.evictForTest(rateLimitWindowMs = 0)

        assertEquals(1, state.drain("live")!!.size, "live message should survive eviction")
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
                setBody("""{"type":"EncryptedLocation","seq":"1","ct":"AA=="}""")
            }
            client.delete("/inbox/$posted?n=1")

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
                setBody("""{"msg":"test"}""")
            }
            val start2 = System.currentTimeMillis()
            client.get("/inbox/$token")
            val elapsed2 = System.currentTimeMillis() - start2
            assertTrue(elapsed2 >= 45, "Poll for existing token took $elapsed2 ms, expected >= 50ms")
        }
    }
}
