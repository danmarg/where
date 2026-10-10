package net.af0.where

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
    @Test
    fun `redactPath replaces inbox token and msgId`() {
        assertEquals("/inbox/:token", redactPath("/inbox/abc123"))
        assertEquals("/inbox/:token/:msgId", redactPath("/inbox/abc123/msg-1"))
        assertEquals("/inbox/:token/:msgId/...", redactPath("/inbox/abc123/msg-1/extra"))
        assertEquals("/inbox", redactPath("/inbox"))
    }

    @Test
    fun `redactPath matches percent-encoded inbox segment like routing does`() {
        assertEquals("/inbox/:token/:msgId", redactPath("/%69nbox/abc123/msg-1"))
        assertEquals("/inbox/:token", redactPath("//inbox/abc123"))
    }

    @Test
    fun `redactPath keeps only allowlisted non-inbox paths`() {
        assertEquals("/health", redactPath("/health"))
        assertEquals("/<other>", redactPath("/"))
        assertEquals("/<other>", redactPath("/inboxes/abc"))
        assertEquals("/<other>", redactPath("/%zz/abc"))
    }

    @Test
    fun `request logs never contain routing tokens or msgIds`() {
        val messages =
            captureLogs {
                testApplication {
                    application { module(ServerState()) }
                    client.put("/inbox/$TOKEN/$MSG_ID") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"type":"test"}""")
                    }
                    client.get("/inbox/$TOKEN")
                    client.delete("/inbox/$TOKEN?ids=$MSG_ID")
                    client.delete("/inbox/$TOKEN/$MSG_ID")
                }
            }
        assertTrue(messages.any { it.contains("/inbox/:token") }, "expected redacted request log lines, got: $messages")
        assertNoSecrets(messages)
    }

    @Test
    fun `unhandled store exceptions return 500 without logging the token`() {
        val failing =
            object : MailboxStore by InMemoryMailboxState() {
                override fun drain(token: String): List<JsonElement>? = throw IllegalStateException("store failure for $token")
            }
        var status: HttpStatusCode? = null
        val messages =
            captureLogs {
                testApplication {
                    application { module(ServerState(mailbox = failing, debug = true)) }
                    status = client.get("/inbox/$TOKEN").status
                }
            }
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertTrue(messages.any { it.contains("500 GET /inbox/:token") }, "expected redacted error log, got: $messages")
        assertNoSecrets(messages)
    }

    /** Runs [block] and returns every logged message, including any attached stack traces. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { event ->
            event.formattedMessage + (event.throwableProxy?.let { " | ${it.className}: ${it.message}" } ?: "")
        }
    }

    private fun assertNoSecrets(messages: List<String>) {
        messages.forEach { line ->
            assertFalse(line.contains(TOKEN), "log line leaked token: $line")
            assertFalse(line.contains(MSG_ID), "log line leaked msgId: $line")
        }
    }

    private companion object {
        const val TOKEN = "secret-token-0123456789abcdef"
        const val MSG_ID = "secret-msgid-fedcba9876543210"
    }
}
