package net.af0.where

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.utils.io.ByteReadChannel
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerHardeningTest {
    /** Records every per-IP rate-limit check so tests can see which IP/bucket each route used. */
    private class RecordingStore(
        private val delegate: MailboxStore = InMemoryMailboxState(),
    ) : MailboxStore by delegate {
        val ipChecks: MutableList<Pair<String, RequestKind>> = Collections.synchronizedList(mutableListOf())

        override fun checkIpRateLimit(
            ip: String,
            kind: RequestKind,
        ): Boolean {
            ipChecks += ip to kind
            return delegate.checkIpRateLimit(ip, kind)
        }
    }

    @Test
    fun `chunked body over the limit is rejected without Content-Length`() =
        testApplication {
            application { module(ServerState(debug = true)) }
            val oversized = """{"type":"test","data":"${"x".repeat(8192)}"}""".encodeToByteArray()
            val response =
                client.put("/inbox/tok/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(ByteReadChannel(oversized)) // streamed: no Content-Length header
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `chunked body under the limit is accepted`() =
        testApplication {
            application { module(ServerState(debug = true)) }
            val response =
                client.put("/inbox/tok/msg1") {
                    contentType(ContentType.Application.Json)
                    setBody(ByteReadChannel("""{"type":"test"}""".encodeToByteArray()))
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }

    @Test
    fun `behind the proxy the rate-limit key is Fly-Client-IP, not spoofable X-Forwarded-For`() {
        val store = RecordingStore()
        testApplication {
            application { module(ServerState(mailbox = store, trustProxy = true, debug = true)) }
            client.put("/inbox/tok/msg1") {
                header("Fly-Client-IP", "203.0.113.7")
                header("X-Forwarded-For", "198.51.100.99")
                contentType(ContentType.Application.Json)
                setBody("""{"type":"test"}""")
            }
        }
        assertEquals(listOf("203.0.113.7" to RequestKind.WRITE), store.ipChecks)
    }

    @Test
    fun `without proxy trust client headers are ignored`() {
        val store = RecordingStore()
        testApplication {
            application { module(ServerState(mailbox = store, trustProxy = false, debug = true)) }
            client.get("/inbox/tok") {
                header("Fly-Client-IP", "203.0.113.7")
                header("X-Forwarded-For", "198.51.100.99")
            }
        }
        val (ip, kind) = store.ipChecks.single()
        assertTrue(ip != "203.0.113.7" && ip != "198.51.100.99", "header IP must not be trusted, got $ip")
        assertEquals(RequestKind.READ, kind)
    }

    @Test
    fun `GET and both DELETE routes are per-IP rate limited`() {
        val store = RecordingStore()
        testApplication {
            application { module(ServerState(mailbox = store, trustProxy = true, debug = true)) }
            client.get("/inbox/tok") { header("Fly-Client-IP", "203.0.113.7") }
            client.delete("/inbox/tok?ids=a,b") { header("Fly-Client-IP", "203.0.113.7") }
            client.delete("/inbox/tok/a") { header("Fly-Client-IP", "203.0.113.7") }
        }
        assertEquals(
            listOf(RequestKind.READ, RequestKind.DELETE, RequestKind.DELETE),
            store.ipChecks.map { it.second },
        )
    }

    @Test
    fun `GET returns 429 once the per-IP read budget is exhausted`() {
        val limiter = InProcessRateLimiter()
        repeat(RequestKind.READ.ipLimit) { assertTrue(limiter.checkIp("203.0.113.7", RequestKind.READ)) }
        val store = InMemoryMailboxState(limiter)
        testApplication {
            application { module(ServerState(mailbox = store, trustProxy = true, debug = true)) }
            val blocked = client.get("/inbox/tok") { header("Fly-Client-IP", "203.0.113.7") }
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            // Separate buckets: reads exhausting their budget don't block writes.
            val write =
                client.put("/inbox/tok/m") {
                    header("Fly-Client-IP", "203.0.113.7")
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test"}""")
                }
            assertEquals(HttpStatusCode.NoContent, write.status)
        }
    }

    @Test
    fun `batch DELETE tolerates duplicate ids and rejects oversized ids`() =
        testApplication {
            application { module(ServerState(debug = true)) }
            assertEquals(HttpStatusCode.NoContent, client.delete("/inbox/tok?ids=a,a,b").status)
            assertEquals(HttpStatusCode.BadRequest, client.delete("/inbox/tok?ids=${"x".repeat(65)}").status)
        }

    @Test
    fun `limiter eviction drops keys whose windows are empty`() {
        val limiter = InProcessRateLimiter()
        repeat(100) { i ->
            limiter.checkPost("tok-$i")
            limiter.checkGet("tok-$i")
            limiter.checkIp("ip-$i", RequestKind.READ)
        }
        assertEquals(300, limiter.trackedKeyCount())
        limiter.evict(windowMs = -1) // everything is older than "now + 1ms"
        assertEquals(0, limiter.trackedKeyCount())
    }

    @Test
    fun `IPv6 clients are bucketed by their 64-bit prefix`() {
        assertEquals(rateLimitKey("2001:db8:1:2::1"), rateLimitKey("2001:db8:1:2:ffff:eeee:dddd:cccc"))
        assertTrue(rateLimitKey("2001:db8:1:2::1") != rateLimitKey("2001:db8:1:3::1"))
        assertEquals("203.0.113.7", rateLimitKey("203.0.113.7"))
        assertEquals("not-an-ip:x", rateLimitKey("not-an-ip:x"))
    }

    @Test
    fun `striped locks map equal keys to the same monitor`() {
        val locks = StripedLocks(stripes = 4)
        assertTrue(locks["abc"] === locks[String(charArrayOf('a', 'b', 'c'))])
    }
}
