package net.af0.where

import kotlinx.serialization.json.JsonPrimitive
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises PostgresMailboxState directly against a real (containerized) Postgres, covering the
 * same behaviors MailboxTest verifies over HTTP for the in-memory store, plus the
 * concurrency/idempotency/TTL logic that has never had test coverage for either backend before
 * this migration.
 */
class PostgresMailboxStateTest {
    companion object {
        // One container shared across all tests in this class; each test uses a fresh random
        // token so rows never collide, so there's no need for per-test containers.
        private val container =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine")).apply { start() }

        // Mirrors Server.kt's private MAX_QUEUE_DEPTH constant (not visible outside that file).
        private const val TEST_MAX_QUEUE_DEPTH = 10000

        private fun jdbcUrlWithCredentials(): String {
            val base = container.jdbcUrl
            val sep = if (base.contains("?")) "&" else "?"
            return "$base${sep}user=${container.username}&password=${container.password}"
        }
    }

    private val stores = mutableListOf<PostgresMailboxState>()

    private fun store(): PostgresMailboxState =
        PostgresMailboxState(createHikariDataSource(jdbcUrlWithCredentials())).also { stores.add(it) }

    private fun freshToken() = "test-" + java.util.UUID.randomUUID().toString().take(12)

    @AfterTest
    fun closeStores() {
        stores.forEach { it.close() }
        stores.clear()
    }

    @Test
    fun `post then drain round trip`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("hello"), "msg-1"))
        assertEquals(listOf(JsonPrimitive("hello")), state.drain(token))
    }

    @Test
    fun `drain is non-destructive`() {
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("hello"), "msg-1")
        assertEquals(1, state.drain(token)?.size)
        assertEquals(1, state.drain(token)?.size)
    }

    @Test
    fun `deleteById removes only that message`() {
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("a"), "msg-a")
        state.post(token, JsonPrimitive("b"), "msg-b")
        assertTrue(state.deleteById(token, "msg-a"))
        assertEquals(listOf(JsonPrimitive("b")), state.drain(token))
    }

    @Test
    fun `deleteByIds removes a batch`() {
        val state = store()
        val token = freshToken()
        repeat(5) { i -> state.post(token, JsonPrimitive(i), "msg-$i") }
        val removed = state.deleteByIds(token, listOf("msg-0", "msg-1", "msg-2"))
        assertEquals(3, removed)
        assertEquals(2, state.drain(token)?.size)
    }

    @Test
    fun `tokens are isolated`() {
        val state = store()
        val tokenA = freshToken()
        val tokenB = freshToken()
        state.post(tokenA, JsonPrimitive("a"), "msg-1")
        assertEquals(1, state.drain(tokenA)?.size)
        assertTrue(state.drain(tokenB)?.isEmpty() == true)
    }

    @Test
    fun `duplicate post with same msgId is a no-op`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("first"), "msg-1"))
        assertTrue(state.post(token, JsonPrimitive("retry"), "msg-1"))
        // Only the original payload should be stored - the retry must not overwrite or duplicate.
        assertEquals(listOf(JsonPrimitive("first")), state.drain(token))
    }

    @Test
    fun `retry after delete is still recognized as a duplicate`() {
        // This is the case RedisMailboxState's receivedIds set exists for: idempotency must
        // outlive delivery, otherwise a retried PUT after the original was drained+deleted would
        // re-insert a message the recipient's ratchet has already advanced past.
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("first"), "msg-1"))
        state.deleteById(token, "msg-1")
        assertTrue(state.drain(token)?.isEmpty() == true)

        assertTrue(state.post(token, JsonPrimitive("retry"), "msg-1"))
        assertTrue(state.drain(token)?.isEmpty() == true, "retried duplicate must not resurrect the deleted message")
    }

    @Test
    fun `queue depth guard rejects posts past the limit`() {
        val state = store()
        val token = freshToken()

        // Seed the queue directly via SQL (bypassing the in-process rate limiter, which caps far
        // below MAX_QUEUE_DEPTH) so this test isolates the depth guard specifically.
        val dataSource = createHikariDataSource(jdbcUrlWithCredentials())
        dataSource.connection.use { conn ->
            val now = System.currentTimeMillis()
            val farFuture = now + 7L * 24 * 60 * 60 * 1000
            conn.prepareStatement(
                "INSERT INTO mailbox_messages (token, msg_id, payload, posted_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                repeat(TEST_MAX_QUEUE_DEPTH) { i ->
                    ps.setString(1, token)
                    ps.setString(2, "seed-$i")
                    ps.setString(3, "\"seed\"")
                    ps.setLong(4, now + i)
                    ps.setLong(5, farFuture)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        dataSource.close()

        assertFalse(state.post(token, JsonPrimitive("overflow"), "overflow-msg"))
    }

    @Test
    fun `drain does not return expired rows before the sweep runs`() {
        // Regression test: an expired-but-not-yet-evicted row must not be returned - otherwise a
        // dual-write shadow-read comparison would see it in Postgres but not in Redis (whose keys
        // vanish atomically via native TTL) and log a false-positive parity mismatch.
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("soon-expired"), "msg-1")

        val dataSource = createHikariDataSource(jdbcUrlWithCredentials())
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE mailbox_messages SET expires_at = ? WHERE token = ?").use { ps ->
                ps.setLong(1, System.currentTimeMillis() - 1000)
                ps.setString(2, token)
                ps.executeUpdate()
            }
        }
        dataSource.close()

        assertTrue(state.drain(token)?.isEmpty() == true, "expired row must not be returned even before evict() runs")
    }

    @Test
    fun `queue depth guard ignores expired rows`() {
        // Regression test: expired-but-not-yet-evicted rows must not count toward the depth
        // guard, otherwise a legitimate post can be spuriously rejected up to a full evict()
        // sweep interval after the queue's actual live depth has dropped.
        val state = store()
        val token = freshToken()

        val dataSource = createHikariDataSource(jdbcUrlWithCredentials())
        dataSource.connection.use { conn ->
            val past = System.currentTimeMillis() - 1000
            conn.prepareStatement(
                "INSERT INTO mailbox_messages (token, msg_id, payload, posted_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                repeat(TEST_MAX_QUEUE_DEPTH) { i ->
                    ps.setString(1, token)
                    ps.setString(2, "expired-$i")
                    ps.setString(3, "\"expired\"")
                    ps.setLong(4, past - i)
                    ps.setLong(5, past)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        dataSource.close()

        assertTrue(state.post(token, JsonPrimitive("fresh"), "fresh-msg"), "expired rows must not count toward depth")
    }

    @Test
    fun `evict removes expired rows`() {
        val state = store()
        val token = freshToken()
        state.post(token, JsonPrimitive("soon-expired"), "msg-1")

        // Force-expire by writing directly with a past expiry, bypassing post()'s TTL calc.
        val dataSource = createHikariDataSource(jdbcUrlWithCredentials())
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE mailbox_messages SET expires_at = ? WHERE token = ?").use { ps ->
                ps.setLong(1, System.currentTimeMillis() - 1000)
                ps.setString(2, token)
                ps.executeUpdate()
            }
        }
        dataSource.close()

        state.evict()
        assertTrue(state.drain(token)?.isEmpty() == true, "expired row should have been evicted")
    }

    @Test
    fun `concurrent posts to the same token are not lost`() {
        val state = store()
        val token = freshToken()
        val n = 50
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(n)
        repeat(n) { i ->
            pool.submit {
                try {
                    state.post(token, JsonPrimitive(i), "msg-$i")
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(n, state.drain(token)?.size)
    }

    @Test
    fun `post without msgId does not dedupe across calls`() {
        val state = store()
        val token = freshToken()
        assertTrue(state.post(token, JsonPrimitive("one")))
        assertTrue(state.post(token, JsonPrimitive("two")))
        assertEquals(2, state.drain(token)?.size)
    }
}
