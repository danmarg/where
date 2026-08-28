package net.af0.where

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MismatchAuditLog always pushes over a real (non-test) dispatcher - MockEngine.execute() hops
 * off whatever scope it's launched from internally, so every test here waits on a latch signaled
 * from inside the mock handler rather than asserting synchronously after record()/construction.
 */
class MismatchAuditLogTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var auditLogger: Logger

    @BeforeTest
    fun setUpLogCapture() {
        auditLogger = LoggerFactory.getLogger("MismatchAuditLog") as Logger
        appender = ListAppender()
        appender.start()
        auditLogger.addAppender(appender)
    }

    @AfterTest
    fun tearDownLogCapture() {
        auditLogger.detachAppender(appender)
    }

    private fun awaitWarnLogContaining(
        text: String,
        timeoutSeconds: Long = 5,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (appender.list.any { it.level == Level.WARN && it.formattedMessage.contains(text) }) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun sampleEvent() =
        MismatchEvent(tokenHash = "abc123", primaryCount = 2, secondaryCount = 1, onlyInPrimary = 1, onlyInSecondary = 0)

    private fun tokenHashOf(request: HttpRequestData): String {
        val bodyText = (request.body as TextContent).text
        val stream = Json.parseToJsonElement(bodyText).jsonObject["streams"]!!.jsonArray.single().jsonObject
        val line = Json.parseToJsonElement(stream["values"]!!.jsonArray.single().jsonArray[1].jsonPrimitive.content)
        return line.jsonObject["tokenHash"]!!.jsonPrimitive.content
    }

    @Test
    fun `successfully pushes a mismatch event to Loki with the correct URL, auth, and body`() {
        val latch = CountDownLatch(1)
        var captured: HttpRequestData? = null
        val engine =
            MockEngine { request ->
                captured = request
                latch.countDown()
                respond("", HttpStatusCode.NoContent)
            }
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "key-1", engine = engine, pushStartupHeartbeat = false)

        log.record(sampleEvent())

        assertTrue(latch.await(5, TimeUnit.SECONDS), "push should complete")
        val request = captured!!
        assertEquals("https://logs.example.com/loki/api/v1/push", request.url.toString())
        assertTrue(request.headers[HttpHeaders.Authorization]?.startsWith("Basic ") == true)

        val bodyText = (request.body as TextContent).text
        val stream = Json.parseToJsonElement(bodyText).jsonObject["streams"]!!.jsonArray.single().jsonObject
        assertEquals("shadow_mismatch", stream["stream"]!!.jsonObject["event"]!!.jsonPrimitive.content)
        assertEquals("abc123", tokenHashOf(request))
        log.close()
    }

    @Test
    fun `logs a warning and does not throw when Loki returns a non-2xx status`() {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "bad-key", engine = engine, pushStartupHeartbeat = false)

        log.record(sampleEvent())

        assertTrue(awaitWarnLogContaining("Failed to push"))
        log.close()
    }

    @Test
    fun `logs a warning and does not throw when the connection fails`() {
        val engine = MockEngine { throw IOException("connection refused") }
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "key-1", engine = engine, pushStartupHeartbeat = false)

        log.record(sampleEvent())

        assertTrue(awaitWarnLogContaining("Failed to push"))
        log.close()
    }

    @Test
    fun `close cancels in-flight work and does not throw`() {
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf()) }
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "key-1", engine = engine, pushStartupHeartbeat = false)

        log.record(sampleEvent())
        log.close()
    }

    @Test
    fun `normalizes a trailing slash in the configured Loki URL`() {
        val latch = CountDownLatch(1)
        var capturedUrl: String? = null
        val engine =
            MockEngine { request ->
                capturedUrl = request.url.toString()
                latch.countDown()
                respond("", HttpStatusCode.NoContent)
            }
        val log = MismatchAuditLog("https://logs.example.com/", "user-1", "key-1", engine = engine, pushStartupHeartbeat = false)

        log.record(sampleEvent())

        assertTrue(latch.await(5, TimeUnit.SECONDS), "push should complete")
        assertEquals("https://logs.example.com/loki/api/v1/push", capturedUrl)
        log.close()
    }

    @Test
    fun `startup heartbeat pushes once when enabled`() {
        val latch = CountDownLatch(1)
        val requests = ConcurrentLinkedQueue<HttpRequestData>()
        val engine =
            MockEngine { request ->
                requests.add(request)
                latch.countDown()
                respond("", HttpStatusCode.NoContent)
            }
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "key-1", engine = engine)

        assertTrue(latch.await(5, TimeUnit.SECONDS), "startup push should complete")
        assertEquals(1, requests.size)
        log.close()
    }

    @Test
    fun `a burst of mismatches produces one push per event with none dropped`() {
        val n = 20
        val latch = CountDownLatch(n)
        val received = ConcurrentLinkedQueue<String>()
        val engine =
            MockEngine { request ->
                received.add(tokenHashOf(request))
                latch.countDown()
                respond("", HttpStatusCode.NoContent)
            }
        // Bounded scope (limitedParallelism(4) default), so this exercises queueing under
        // concurrency rather than a purely sequential dispatch.
        val log = MismatchAuditLog("https://logs.example.com", "user-1", "key-1", engine = engine, pushStartupHeartbeat = false)

        repeat(n) { i -> log.record(sampleEvent().copy(tokenHash = "token-$i")) }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "all $n pushes should complete, got ${received.size}")
        assertEquals(n, received.toSet().size, "no event should be dropped or duplicated under concurrent load")
        log.close()
    }
}
