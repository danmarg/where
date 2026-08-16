package net.af0.where

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A minimal in-memory MailboxStore double, with hooks to simulate failures - used to test
 * DualWriteMailboxState's mirroring/comparison behavior without a real Redis or Postgres.
 */
private class FakeMailboxStore(
    private var failNextWrite: Boolean = false,
) : MailboxStore {
    val messages = mutableMapOf<String, MutableList<Pair<String, JsonElement>>>()
    var postCount = 0
    var deleteCount = 0

    fun failNext() {
        failNextWrite = true
    }

    private fun maybeFail() {
        if (failNextWrite) {
            failNextWrite = false
            throw RuntimeException("simulated failure")
        }
    }

    override fun checkIpRateLimit(ip: String) = true

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        maybeFail()
        postCount++
        messages.getOrPut(token) { mutableListOf() }.add((msgId ?: "") to payload)
        return true
    }

    override fun drain(token: String): List<JsonElement>? {
        maybeFail()
        return messages[token]?.map { it.second } ?: emptyList()
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        maybeFail()
        deleteCount++
        messages[token]?.removeIf { it.first == msgId }
        return true
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        maybeFail()
        deleteCount++
        messages[token]?.removeIf { it.first in msgIds }
        return msgIds.size
    }
}

class DualWriteMailboxStateTest {
    // Unconfined so launched coroutines run synchronously within the triggering call, keeping
    // assertions deterministic without needing to poll for the background comparison to finish.
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var migrationLogger: Logger

    @BeforeTest
    fun setUpLogCapture() {
        migrationLogger = LoggerFactory.getLogger("MailboxMigration") as Logger
        appender = ListAppender()
        appender.start()
        migrationLogger.addAppender(appender)
    }

    @AfterTest
    fun tearDownLogCapture() {
        migrationLogger.detachAppender(appender)
    }

    private fun warnLogs() = appender.list.filter { it.level == Level.WARN }

    @Test
    fun `secondary post failure does not fail the request`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore(failNextWrite = true)
        val dual = DualWriteMailboxState(primary, secondary, testScope)

        val result = dual.post("token", JsonPrimitive("hi"), "msg-1")

        assertTrue(result, "primary succeeded, so the request must succeed regardless of secondary")
        assertEquals(1, primary.postCount)
        assertTrue(warnLogs().any { it.formattedMessage.contains("secondary post failed") })
    }

    @Test
    fun `secondary delete failure does not fail the request`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, testScope)
        dual.post("token", JsonPrimitive("hi"), "msg-1")
        secondary.failNext()

        val result = dual.deleteById("token", "msg-1")

        assertTrue(result)
        assertTrue(warnLogs().any { it.formattedMessage.contains("secondary deleteById failed") })
    }

    @Test
    fun `drain always reflects primary even when secondary disagrees`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, testScope)

        primary.post("token", JsonPrimitive("only-in-primary"), "msg-1")
        // secondary never got this write - simulates a real mirroring gap.

        val result = dual.drain("token")

        assertEquals(listOf(JsonPrimitive("only-in-primary")), result)
    }

    @Test
    fun `payload mismatch between stores logs a WARN`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, testScope)

        primary.post("token", JsonPrimitive("primary-only"), "msg-1")
        // Deliberately diverge: secondary has a different payload for this token.
        secondary.post("token", JsonPrimitive("secondary-only"), "msg-2")

        dual.drain("token")

        assertTrue(warnLogs().any { it.formattedMessage.contains("drain mismatch") })
    }

    @Test
    fun `mirrored deletes prevent false-positive mismatches`() {
        // Regression guard: if deletes were only applied to primary (not mirrored to secondary),
        // every subsequent drain() comparison would show a permanent false-positive mismatch once
        // a message is delivered and deleted, even though nothing is actually wrong.
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, testScope)

        dual.post("token", JsonPrimitive("msg"), "msg-1")
        dual.deleteById("token", "msg-1")

        appender.list.clear()
        dual.drain("token")

        assertFalse(warnLogs().any { it.formattedMessage.contains("drain mismatch") })
    }
}
