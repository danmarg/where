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
    var evictCount = 0

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

    override fun evict() {
        evictCount++
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
    fun `secondary evict is skipped when called again before the interval elapses`() {
        // Regression test: evict() fires every 60s from the app's housekeeping loop, but pinging
        // a serverless Postgres that often defeats its autosuspend billing - see secondaryEvictIntervalMs.
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, scope = testScope, secondaryEvictIntervalMs = 60 * 60 * 1000L)

        dual.evict()
        dual.evict()
        dual.evict()

        assertEquals(1, secondary.evictCount, "secondary.evict() should only run once per interval, not on every tick")
        assertEquals(3, primary.evictCount, "primary.evict() (cheap, in-process) should still run every tick")
    }

    @Test
    fun `secondary evict interval of zero allows every tick`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val dual = DualWriteMailboxState(primary, secondary, scope = testScope, secondaryEvictIntervalMs = 0L)

        dual.evict()
        dual.evict()

        assertEquals(2, secondary.evictCount, "an interval of 0 means every tick is eligible to run")
    }

    @Test
    fun `onMismatch fires with the correct counts on a drain mismatch`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val events = mutableListOf<MismatchEvent>()
        val dual = DualWriteMailboxState(primary, secondary, testScope, onMismatch = { events.add(it) })

        primary.post("token", JsonPrimitive("primary-only"), "msg-1")
        secondary.post("token", JsonPrimitive("secondary-only"), "msg-2")

        dual.drain("token")

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(1, event.primaryCount)
        assertEquals(1, event.secondaryCount)
        assertEquals(1, event.onlyInPrimary)
        assertEquals(1, event.onlyInSecondary)
        assertTrue(event.tokenHash.isNotBlank())
    }

    @Test
    fun `onMismatch is not invoked when primary and secondary agree`() {
        val primary = FakeMailboxStore()
        val secondary = FakeMailboxStore()
        val events = mutableListOf<MismatchEvent>()
        val dual = DualWriteMailboxState(primary, secondary, testScope, onMismatch = { events.add(it) })

        dual.post("token", JsonPrimitive("msg"), "msg-1")
        dual.drain("token")

        assertTrue(events.isEmpty())
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
