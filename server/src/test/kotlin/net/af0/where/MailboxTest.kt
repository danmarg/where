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
    // PUT /inbox/{token}/{msgId}
    // ---------------------------------------------------------------------------

    @Test
    fun `PUT inbox returns 204`() {
        if (!isLocalhost()) return // Skip when running against production
        testApplication {
            application { module(ServerState()) }
            val response =
                client.put("/inbox/aabbccddeeff0011/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"EncryptedLocation","seq":"1","ct":"AAEC"}""")
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `PUT inbox with empty body returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val response =
                client.put("/inbox/aabbccddeeff0011/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `PUT inbox with non-JSON body returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val response =
                client.put("/inbox/sometoken/msg1") {
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

            client.put("/inbox/$token/msg1") {
                contentType(ContentType.Application.Json)
                setBody(payload1)
            }
            client.put("/inbox/$token/msg2") {
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
            client.put("/inbox/$token/msg1") {
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
    fun `DELETE inbox removes specific messages by IDs`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-ids"
            // Use PUT to specify msgId
            repeat(5) { i ->
                client.put("/inbox/$token/msg-$i") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"i":$i}""")
                }
            }

            val deleteResponse = client.delete("/inbox/$token?ids=msg-0,msg-1,msg-2")
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getResponse = client.get("/inbox/$token")
            val arr = json.decodeFromString<JsonArray>(getResponse.bodyAsText())
            assertEquals(2, arr.size, "Should have 2 messages remaining")
        }
    }

    @Test
    fun `DELETE inbox with non-existent IDs is a no-op`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-nonexistent"
            client.put("/inbox/$token/msg-a") {
                contentType(ContentType.Application.Json)
                setBody("""{"msg":"a"}""")
            }

            val deleteResponse = client.delete("/inbox/$token?ids=msg-unknown")
            assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

            val getResponse = client.get("/inbox/$token")
            val arr = json.decodeFromString<JsonArray>(getResponse.bodyAsText())
            assertEquals(1, arr.size)
        }
    }

    @Test
    fun `mixed batch and single DELETE routes work together`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "mixed-delete-test"
            repeat(5) { i ->
                client.put("/inbox/$token/msg-$i") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"i":$i}""")
                }
            }

            // 1. Delete msg-0 via single-message route
            val deleteSingleResponse = client.delete("/inbox/$token/msg-0")
            assertEquals(HttpStatusCode.NoContent, deleteSingleResponse.status)

            // 2. Delete msg-1 and msg-2 via batch route
            val deleteBatchResponse = client.delete("/inbox/$token?ids=msg-1,msg-2")
            assertEquals(HttpStatusCode.NoContent, deleteBatchResponse.status)

            // 3. Verify only msg-3 and msg-4 remain
            val getResponse = client.get("/inbox/$token")
            val arr = json.decodeFromString<JsonArray>(getResponse.bodyAsText())
            assertEquals(2, arr.size)

            // Verify expected content
            val content = getResponse.bodyAsText()
            assertTrue(content.contains("""{"i":3}"""))
            assertTrue(content.contains("""{"i":4}"""))
            assertTrue(!content.contains("""{"i":0}"""))
            assertTrue(!content.contains("""{"i":1}"""))
            assertTrue(!content.contains("""{"i":2}"""))
        }
    }

    @Test
    fun `DELETE inbox with missing ids returns 400`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val token = "delete-test-3"
            val missingIds = client.delete("/inbox/$token")
            assertEquals(HttpStatusCode.BadRequest, missingIds.status)
        }
    }

    @Test
    fun `GET inbox for different tokens are independent`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }
            val tokenA = "aaaaaaaaaaaaaaaa"
            val tokenB = "bbbbbbbbbbbbbbbb"

            client.put("/inbox/$tokenA/msg1") {
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
            application { module(ServerState(debug = true)) }
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
    fun `PUT inbox returns 429 when rate-limited`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState(debug = true)) }
            val token = "ratelimit-put-token"

            // Exhaust the rate limit
            repeat(RATE_LIMIT_MAX_POSTS) { i ->
                client.put("/inbox/$token/msg-$i") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"msg":"test"}""")
                }
            }

            // Next one should be 429
            val response =
                client.put("/inbox/$token/msg-overflow") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"msg":"test"}""")
                }
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
        }
    }

    @Test
    fun `PUT inbox returns 429 when queue depth reached`() {
        if (!isLocalhost()) return
        testApplication {
            val state = InMemoryMailboxState()
            application { module(ServerState(mailbox = state)) }
            val token = "queuedepth-token"

            // We need to bypass rate-limiting to test queue depth.
            // We'll fill the queue manually in the state object (since it's internal to the test app).
            repeat(1000) { i ->
                state.post(token, JsonPrimitive(i), "msg-$i")
            }

            // The 1001st message should be rejected (returns false from state.post, leading to 429 in route).
            val response =
                client.put("/inbox/$token/overflow") {
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
        state.post(token, JsonPrimitive("msg"), "msg-1")
        state.deleteById(token, "msg-1") // empties the mailbox queue

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
            client.put("/inbox/$posted/msg-1") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"EncryptedMessage","v":1,"msgId":"msg-1","envelope":"AA==","ct":"BB=="}""")
            }
            client.delete("/inbox/$posted/msg-1")

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

            client.put("/inbox/$token/msg1") {
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
