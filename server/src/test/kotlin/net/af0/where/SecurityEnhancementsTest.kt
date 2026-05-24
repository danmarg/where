package net.af0.where

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SecurityEnhancementsTest {
    private fun isLocalhost(): Boolean = System.getenv("WHERE_TEST_SERVER_URL")?.contains("localhost") != false

    @Test
    fun `PUT inbox rejects payloads larger than 4KB`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            // Create a payload slightly larger than 4KB
            val largePayload = """{"type":"test","data":"${"x".repeat(4096)}"}"""

            val response =
                client.put("/inbox/sometoken/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(largePayload)
                }

            // Ktor's manual check returns PayloadTooLarge (413)
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
    }

    @Test
    fun `PUT inbox accepts payloads smaller than 4KB`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val validPayload = """{"type":"test","data":"${"x".repeat(1024)}"}"""

            val response =
                client.put("/inbox/sometoken/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(validPayload)
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }

    @Test
    fun `PUT inbox rejects payloads without type field`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val invalidPayload = """{"data":"no-type"}"""

            val response =
                client.put("/inbox/sometoken/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(invalidPayload)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `PUT inbox rejects non-object payloads`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val arrayPayload = """[{"type":"test"}]"""

            val response =
                client.put("/inbox/sometoken/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(arrayPayload)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `routes reject tokens longer than 64 characters`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val longToken = "t".repeat(65)

            val putResponse =
                client.put("/inbox/$longToken/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, putResponse.status)

            val getResponse = client.get("/inbox/$longToken")
            assertEquals(HttpStatusCode.BadRequest, getResponse.status)

            val deleteBatchResponse = client.delete("/inbox/$longToken?ids=msg1")
            assertEquals(HttpStatusCode.BadRequest, deleteBatchResponse.status)

            val deleteSingleResponse = client.delete("/inbox/$longToken/msg1")
            assertEquals(HttpStatusCode.BadRequest, deleteSingleResponse.status)
        }
    }

    @Test
    fun `routes reject msgIds longer than 64 characters`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val longMsgId = "m".repeat(65)

            val putResponse =
                client.put("/inbox/sometoken/$longMsgId") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, putResponse.status)

            val deleteSingleResponse = client.delete("/inbox/sometoken/$longMsgId")
            assertEquals(HttpStatusCode.BadRequest, deleteSingleResponse.status)
        }
    }

    @Test
    fun `batch DELETE rejects more than 50 IDs`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val ids = (1..51).joinToString(",") { "msg$it" }

            val response = client.delete("/inbox/sometoken?ids=$ids")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `batch DELETE accepts exactly 50 IDs`() {
        if (!isLocalhost()) return
        testApplication {
            application { module(ServerState()) }

            val ids = (1..50).joinToString(",") { "msg$it" }

            val response = client.delete("/inbox/sometoken?ids=$ids")

            assertEquals(HttpStatusCode.NoContent, response.status)
        }
    }
}
