package net.af0.where

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.delete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

// ---------------------------------------------------------------------------
// Server module
// ---------------------------------------------------------------------------

private val json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

private const val RATE_LIMIT_WINDOW_MS = 60 * 1000L
private const val POLL_BASELINE_LATENCY_MS = 50L
private const val MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * Extra headroom added to the TTL when refreshing mailbox keys in Redis. Keys are only
 * re-EXPIREd once their remaining TTL drops below MAILBOX_TTL_MS, so a chatty mailbox doesn't
 * issue an EXPIRE on every single write. This means data can live up to this long past the
 * 7-day floor (acceptable: it's encrypted at rest) but never expires earlier than 7 days.
 */
private const val TTL_REFRESH_PADDING_SEC = 2 * 24 * 60 * 60L

/** Maximum messages retained per token. Prevents unbounded memory growth from floods. */
private const val MAX_QUEUE_DEPTH = 10000

/** Maximum messages returned in a single poll request. */
private const val MAX_MESSAGES_PER_POLL = 50

/** Maximum POST requests per token within the rate-limit window.
 * Increased 10x to accommodate WAL retry bursts during reconnects (e.g. 20 friends x 50 retries).
 */
internal const val RATE_LIMIT_MAX_POSTS = 1000

/** Maximum GET requests per token within the rate-limit window. */
internal const val RATE_LIMIT_MAX_GETS = 2000

// ---------------------------------------------------------------------------
// In-process rate limiter (shared by both store implementations)
// ---------------------------------------------------------------------------

/**
 * Tracks per-token POST/GET counts and per-IP POST counts entirely in the JVM
 * process. This avoids storing short-lived rate-limit keys in Redis, which
 * caused ~50 % of all observed Redis commands (INCR + EXPIRE per request,
 * plus constant TTL-expiry EVICTs for the 60-second windows).
 *
 * Thread-safe via ConcurrentHashMap + ConcurrentLinkedQueue; no locking needed
 * because we only need approximate counts (a few extra requests past the limit
 * are harmless, and missing a concurrent removal is safe).
 */
class InProcessRateLimiter {
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val getTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val ipTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun checkPost(token: String): Boolean = check(postTimes, token, RATE_LIMIT_MAX_POSTS)

    fun checkGet(token: String): Boolean = check(getTimes, token, RATE_LIMIT_MAX_GETS)

    fun checkIp(ip: String): Boolean = check(ipTimes, ip, IP_RATE_LIMIT_MAX)

    fun evict(windowMs: Long = RATE_LIMIT_WINDOW_MS) {
        val cutoff = System.currentTimeMillis() - windowMs
        for (q in postTimes.values) q.removeIf { it < cutoff }
        for (q in getTimes.values) q.removeIf { it < cutoff }
        for (q in ipTimes.values) q.removeIf { it < cutoff }
    }

    private fun check(
        map: ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>,
        key: String,
        limit: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val q = map.getOrPut(key) { ConcurrentLinkedQueue() }
        q.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (q.size >= limit) return false
        q.add(now)
        return true
    }
}

private const val IP_RATE_LIMIT_MAX = 2000

interface MailboxStore : AutoCloseable {
    fun checkIpRateLimit(ip: String): Boolean

    /**
     * Posts a [payload] to the inbox for [token].
     * Returns true if successful, false if rate-limited or mailbox full.
     */
    fun post(
        token: String,
        payload: JsonElement,
        msgId: String? = null,
    ): Boolean

    /**
     * Drains up to 50 messages from the inbox for [token].
     * Returns null if rate-limited.
     */
    fun drain(token: String): List<JsonElement>?

    /**
     * Deletes a specific message by [msgId]. Idempotent.
     */
    fun deleteById(
        token: String,
        msgId: String,
    ): Boolean

    /**
     * Deletes multiple messages by [msgIds]. Idempotent.
     */
    fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int

    /** Reclaim stale entries. No-op for implementations where the store handles expiry. */
    fun evict() {}

    override fun close() {}
}

/** Utility to help with testing eviction logic. */
fun MailboxStore.evictForTest(rateLimitWindowMs: Long) {
    if (this is InMemoryMailboxState) {
        evictWithParams(rateLimitWindowMs)
    }
}

// ---------------------------------------------------------------------------
// In-memory implementation (tests / no Redis)
// ---------------------------------------------------------------------------

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long, val msgId: String? = null)

class InMemoryMailboxState(
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()
    private val receivedIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedIdsOrder = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    private val locks = ConcurrentHashMap<String, Any>()

    private fun getLock(token: String) = locks.getOrPut(token) { Any() }

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean =
        synchronized(getLock(token)) {
            val now = System.currentTimeMillis()

            if (msgId != null) {
                val ids = receivedIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }
                if (ids.contains(msgId)) return true
            }

            if (!limiter.checkPost(token)) return false

            val queue = mailboxes.getOrPut(token) { ConcurrentLinkedQueue() }
            queue.removeIf { it.expiresAt <= now }
            if (queue.size >= MAX_QUEUE_DEPTH) return false
            queue.add(MailboxEntry(payload, now + MAILBOX_TTL_MS, msgId))
            if (msgId != null) {
                receivedIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }.add(msgId)
                receivedIdsOrder.getOrPut(token) { ConcurrentLinkedQueue() }.add(msgId)
            }
            return true
        }

    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        val now = System.currentTimeMillis()
        val queue = mailboxes[token] ?: dummyQueue
        return queue.asSequence()
            .filter { it.expiresAt > now }
            .map { it.payload }
            .take(MAX_MESSAGES_PER_POLL)
            .toList()
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        val queue = mailboxes[token] ?: return false
        return queue.removeIf { it.msgId == msgId }
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        val queue = mailboxes[token] ?: return 0
        val initialSize = queue.size
        queue.removeIf { it.msgId in msgIds }
        return initialSize - queue.size
    }

    override fun evict() = evictWithParams(RATE_LIMIT_WINDOW_MS)

    // Kept for tests that need to drive eviction with a custom window.
    internal fun evictWithParams(rateLimitWindowMs: Long) {
        limiter.evict(rateLimitWindowMs)

        val now = System.currentTimeMillis()
        mailboxes.forEach { (token, _) ->
            mailboxes.computeIfPresent(token) { _, q ->
                q.removeIf { it.expiresAt <= now }
                if (q.isEmpty()) null else q
            }
        }

        receivedIds.forEach { (token, set) ->
            if (set.size > MAX_QUEUE_DEPTH) {
                val order = receivedIdsOrder[token]
                while (set.size > MAX_QUEUE_DEPTH && order != null) {
                    val oldest = order.poll() ?: break
                    set.remove(oldest)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Redis implementation
// ---------------------------------------------------------------------------

class RedisMailboxState(
    val jedis: JedisPooled,
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    // Rate limiting is handled in-process by InProcessRateLimiter; these scripts
    // are pure mailbox operations with no INCR/EXPIRE rate-limit keys. This
    // eliminates the two biggest Redis cost drivers: the constant short-TTL key
    // churn (one INCR + one EXPIRE per request) and the resulting EVICT spam from
    // 60-second windows expiring dozens of times per minute.
    private val postScript =
        """
        local inboxKey = KEYS[1]
        local receivedIdsKey = KEYS[2]
        local dataKey = KEYS[3]

        local maxQueueDepth = tonumber(ARGV[1])
        local payload = ARGV[2]
        local ttlSec = tonumber(ARGV[3])
        local msgId = ARGV[4]
        local score = tonumber(ARGV[5])
        local paddedTtlSec = tonumber(ARGV[6])

        -- Idempotency check: drop retransmits we have already stored.
        if msgId ~= "" then
            if redis.call('SISMEMBER', receivedIdsKey, msgId) == 1 then
                return 1
            end
        end

        -- Queue depth guard.
        if redis.call('ZCARD', inboxKey) >= maxQueueDepth then
            return 0
        end

        -- Store payload.
        redis.call('HSET', dataKey, msgId, payload)
        redis.call('ZADD', inboxKey, score, msgId)
        if msgId ~= "" then
            redis.call('SADD', receivedIdsKey, msgId)
        end

        -- Only re-EXPIRE once the TTL has decayed below the floor, padding back up above it.
        -- Avoids an EXPIRE (x2-3) on every write while guaranteeing keys never expire before
        -- ttlSec of remaining life. inboxKey/dataKey/receivedIdsKey are always refreshed
        -- together, so a single TTL check on dataKey is enough to gate all three. SADD above
        -- must run first so receivedIdsKey already exists by the time EXPIRE targets it.
        local ttl = redis.call('TTL', dataKey)
        if ttl < ttlSec then
            redis.call('EXPIRE', inboxKey, paddedTtlSec)
            redis.call('EXPIRE', dataKey, paddedTtlSec)
            if msgId ~= "" then
                redis.call('EXPIRE', receivedIdsKey, paddedTtlSec)
            end
        end

        return 1
        """.trimIndent()

    private val drainScript =
        """
        local inboxKey = KEYS[1]
        local dataKey = KEYS[2]

        local ids = redis.call('ZRANGE', inboxKey, 0, tonumber(ARGV[1]) - 1)
        if #ids == 0 then return {} end

        local payloads = redis.call('HMGET', dataKey, unpack(ids))
        for i, payload in ipairs(payloads) do
            if not payload then
                redis.call('ZREM', inboxKey, ids[i])
            end
        end
        return payloads
        """.trimIndent()

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        if (!limiter.checkPost(token)) return false
        val result =
            jedis.eval(
                postScript,
                listOf("inbox:$token", "receivedIds:$token", "inbox-data:$token"),
                listOf(
                    MAX_QUEUE_DEPTH.toString(),
                    payload.toString(),
                    (MAILBOX_TTL_MS / 1000).toString(),
                    msgId ?: "",
                    System.currentTimeMillis().toString(),
                    (MAILBOX_TTL_MS / 1000 + TTL_REFRESH_PADDING_SEC).toString(),
                ),
            )
        return result == 1L
    }

    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        @Suppress("UNCHECKED_CAST")
        val result =
            jedis.eval(
                drainScript,
                listOf("inbox:$token", "inbox-data:$token"),
                listOf(MAX_MESSAGES_PER_POLL.toString()),
            ) as? List<*> ?: return emptyList()
        return result.filterNotNull().map { item ->
            val str = if (item is ByteArray) item.decodeToString() else item.toString()
            json.parseToJsonElement(str)
        }
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        jedis.zrem("inbox:$token", msgId)
        jedis.hdel("inbox-data:$token", msgId)
        return true
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        if (msgIds.isEmpty()) return 0
        jedis.zrem("inbox:$token", *msgIds.toTypedArray())
        jedis.hdel("inbox-data:$token", *msgIds.toTypedArray())
        return msgIds.size
    }

    override fun evict() = limiter.evict()

    override fun close() {
        jedis.close()
    }
}

// ---------------------------------------------------------------------------
// Postgres implementation
// ---------------------------------------------------------------------------

private const val MAILBOX_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS mailbox_messages (
    token       TEXT NOT NULL,
    msg_id      TEXT NOT NULL,
    payload     TEXT NOT NULL,
    posted_at   BIGINT NOT NULL,
    expires_at  BIGINT NOT NULL,
    PRIMARY KEY (token, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_mailbox_messages_token_posted ON mailbox_messages (token, posted_at);
CREATE INDEX IF NOT EXISTS idx_mailbox_messages_expires ON mailbox_messages (expires_at);

CREATE TABLE IF NOT EXISTS mailbox_received_ids (
    token       TEXT NOT NULL,
    msg_id      TEXT NOT NULL,
    expires_at  BIGINT NOT NULL,
    PRIMARY KEY (token, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_mailbox_received_expires ON mailbox_received_ids (expires_at);
"""

/**
 * Accepts a plain "postgresql://user:pass@host/db?params" connection string (what Neon and most
 * providers hand out) and adapts it for pgjdbc, which - unlike libpq - doesn't accept
 * "user:pass@" userinfo in the URL itself (credentials must be separate JDBC properties) and
 * doesn't recognize libpq-only params like "channel_binding".
 */
fun createHikariDataSource(connectionString: String): HikariDataSource {
    val uri = URI(connectionString.removePrefix("jdbc:").replaceFirst(Regex("^postgres(ql)?://"), "postgresql://"))
    val dbUser = uri.userInfo?.substringBefore(":")
    val dbPassword = uri.userInfo?.substringAfter(":", "")
    val query =
        uri.query
            ?.split("&")
            ?.filterNot { it.startsWith("channel_binding=") }
            ?.joinToString("&")
    val hostPart = if (uri.port != -1) "${uri.host}:${uri.port}" else uri.host
    val jdbcUrl = "jdbc:postgresql://$hostPart${uri.path}" + if (query.isNullOrEmpty()) "" else "?$query"

    return HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            driverClassName = "org.postgresql.Driver"
            username = dbUser
            password = dbPassword
            maximumPoolSize = 5
            // Let the pool drain to zero connections when idle (default minimumIdle equals
            // maximumPoolSize, which holds connections open indefinitely) - this app's traffic is
            // low/bursty and Neon's serverless compute autosuspends based on connection/query
            // activity, so idle connections sitting open defeat scale-to-zero billing.
            minimumIdle = 0
            idleTimeout = 60_000
        },
    )
}

/**
 * Postgres-backed mailbox store (Neon). Unlike Redis, each row carries its own [expiresAt], so
 * there's no need for the EXPIRE-churn-avoidance padding RedisMailboxState uses (Server.kt above) -
 * that hack existed only to avoid billed EXPIRE commands on shared per-mailbox Redis keys.
 *
 * Atomicity for post() is provided by an in-process per-token lock (same pattern as
 * InMemoryMailboxState.getLock), not SQL isolation level: Fly runs exactly one machine/JVM for
 * this app, so there's never more than one writer.
 */
class PostgresMailboxState(
    private val dataSource: DataSource,
    private val limiter: InProcessRateLimiter = InProcessRateLimiter(),
) : MailboxStore {
    private val locks = ConcurrentHashMap<String, Any>()

    private fun getLock(token: String) = locks.getOrPut(token) { Any() }

    init {
        dataSource.connection.use { conn ->
            conn.createStatement().use { it.execute(MAILBOX_SCHEMA_SQL) }
        }
    }

    override fun checkIpRateLimit(ip: String) = limiter.checkIp(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        if (!limiter.checkPost(token)) return false
        synchronized(getLock(token)) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    val now = System.currentTimeMillis()
                    val expiresAt = now + MAILBOX_TTL_MS

                    if (msgId != null) {
                        val inserted =
                            conn.prepareStatement(
                                "INSERT INTO mailbox_received_ids (token, msg_id, expires_at) VALUES (?, ?, ?) " +
                                    "ON CONFLICT (token, msg_id) DO NOTHING",
                            ).use { ps ->
                                ps.setString(1, token)
                                ps.setString(2, msgId)
                                ps.setLong(3, expiresAt)
                                ps.executeUpdate() > 0
                            }
                        if (!inserted) {
                            // Already seen: idempotent no-op, matches RedisMailboxState's SISMEMBER check.
                            conn.commit()
                            return true
                        }
                    }

                    val depth =
                        conn.prepareStatement(
                            "SELECT COUNT(*) FROM mailbox_messages WHERE token = ? AND expires_at > ?",
                        ).use { ps ->
                            ps.setString(1, token)
                            ps.setLong(2, now)
                            ps.executeQuery().use { rs ->
                                rs.next()
                                rs.getLong(1)
                            }
                        }
                    if (depth >= MAX_QUEUE_DEPTH) {
                        conn.rollback()
                        return false
                    }

                    conn.prepareStatement(
                        "INSERT INTO mailbox_messages (token, msg_id, payload, posted_at, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?) ON CONFLICT (token, msg_id) DO NOTHING",
                    ).use { ps ->
                        ps.setString(1, token)
                        // A null msgId means "no idempotency requested" (never happens over the real HTTP
                        // API, which requires msgId in the path) - give it a unique key so it behaves like
                        // InMemoryMailboxState's queue (a fresh entry per post), not a dedup target.
                        ps.setString(2, msgId ?: UUID.randomUUID().toString())
                        ps.setString(3, payload.toString())
                        ps.setLong(4, now)
                        ps.setLong(5, expiresAt)
                        ps.executeUpdate()
                    }
                    conn.commit()
                    return true
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        }
    }

    override fun drain(token: String): List<JsonElement>? {
        if (!limiter.checkGet(token)) return null
        return dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT payload FROM mailbox_messages WHERE token = ? AND expires_at > ? ORDER BY posted_at LIMIT ?",
            ).use { ps ->
                ps.setString(1, token)
                ps.setLong(2, System.currentTimeMillis())
                ps.setInt(3, MAX_MESSAGES_PER_POLL)
                ps.executeQuery().use { rs ->
                    val results = mutableListOf<JsonElement>()
                    while (rs.next()) {
                        results.add(json.parseToJsonElement(rs.getString(1)))
                    }
                    results
                }
            }
        }
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM mailbox_messages WHERE token = ? AND msg_id = ?").use { ps ->
                ps.setString(1, token)
                ps.setString(2, msgId)
                ps.executeUpdate()
            }
        }
        return true
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        if (msgIds.isEmpty()) return 0
        return dataSource.connection.use { conn ->
            val placeholders = msgIds.joinToString(",") { "?" }
            conn.prepareStatement(
                "DELETE FROM mailbox_messages WHERE token = ? AND msg_id IN ($placeholders)",
            ).use { ps ->
                ps.setString(1, token)
                msgIds.forEachIndexed { i, id -> ps.setString(i + 2, id) }
                ps.executeUpdate()
            }
        }
    }

    override fun evict() {
        limiter.evict()
        val now = System.currentTimeMillis()
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM mailbox_messages WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, now)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM mailbox_received_ids WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, now)
                ps.executeUpdate()
            }
        }
    }

    override fun close() {
        (dataSource as? HikariDataSource)?.close()
    }
}

// ---------------------------------------------------------------------------
// Dual-write wrapper - Redis -> Postgres migration aid, removed once cutover completes
// ---------------------------------------------------------------------------

private val migrationLog = LoggerFactory.getLogger("MailboxMigration")

private fun tokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)

/**
 * Mirrors every mutation from [primary] to [secondary] best-effort (never fails the caller's
 * request if the mirror write fails), and diffs every drain() against a shadow read of
 * [secondary]. Used during the Redis -> Postgres migration: deploy with Redis as primary and
 * Postgres as secondary first, then flip once confident. "Zero WARN logs from this class" is the
 * proof of parity.
 */
class DualWriteMailboxState(
    private val primary: MailboxStore,
    private val secondary: MailboxStore,
    // Capped so a slow secondary (e.g. Postgres under load, bounded by its own small Hikari pool)
    // can't cause unbounded coroutine fan-out under sustained request volume - each mirrored
    // operation still runs off the request path, just with a ceiling on how many run at once.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4)),
    // evict() is called every RATE_LIMIT_WINDOW_MS (60s) by the app's housekeeping loop. Running
    // secondary.evict() on that cadence pings a serverless Postgres (Neon) far more often than its
    // autosuspend idle window, keeping its compute billed as always-on instead of scale-to-zero.
    // drain()/post() already filter on expires_at directly, so delaying the physical sweep is
    // correctness-neutral - only the row cleanup itself needs a much coarser cadence.
    private val secondaryEvictIntervalMs: Long = 30 * 60 * 1000L,
) : MailboxStore {
    private val lastSecondaryEvictAt = java.util.concurrent.atomic.AtomicLong(0)

    override fun checkIpRateLimit(ip: String) = primary.checkIpRateLimit(ip)

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        val result = primary.post(token, payload, msgId)
        if (result) {
            scope.launch {
                runCatching { secondary.post(token, payload, msgId) }
                    .onFailure { migrationLog.warn("secondary post failed token={}", tokenHash(token), it) }
            }
        }
        return result
    }

    override fun drain(token: String): List<JsonElement>? {
        val result = primary.drain(token) ?: return null
        scope.launch {
            runCatching {
                val secondaryResult = secondary.drain(token) ?: emptyList()
                comparePayloads(token, result, secondaryResult)
            }.onFailure { migrationLog.warn("secondary drain failed token={}", tokenHash(token), it) }
        }
        return result
    }

    override fun deleteById(
        token: String,
        msgId: String,
    ): Boolean {
        val result = primary.deleteById(token, msgId)
        scope.launch {
            runCatching { secondary.deleteById(token, msgId) }
                .onFailure { migrationLog.warn("secondary deleteById failed token={}", tokenHash(token), it) }
        }
        return result
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        val result = primary.deleteByIds(token, msgIds)
        if (msgIds.isNotEmpty()) {
            scope.launch {
                runCatching { secondary.deleteByIds(token, msgIds) }
                    .onFailure { migrationLog.warn("secondary deleteByIds failed token={}", tokenHash(token), it) }
            }
        }
        return result
    }

    override fun evict() {
        primary.evict()
        val now = System.currentTimeMillis()
        val last = lastSecondaryEvictAt.get()
        if (now - last >= secondaryEvictIntervalMs && lastSecondaryEvictAt.compareAndSet(last, now)) {
            runCatching { secondary.evict() }
                .onFailure { migrationLog.warn("secondary evict failed", it) }
        }
    }

    override fun close() {
        primary.close()
        secondary.close()
    }

    private fun comparePayloads(
        token: String,
        primaryPayloads: List<JsonElement>,
        secondaryPayloads: List<JsonElement>,
    ) {
        val primarySorted = primaryPayloads.map { it.toString() }.sorted()
        val secondarySorted = secondaryPayloads.map { it.toString() }.sorted()
        if (primarySorted != secondarySorted) {
            val onlyInPrimary = primarySorted.toSet() - secondarySorted.toSet()
            val onlyInSecondary = secondarySorted.toSet() - primarySorted.toSet()
            migrationLog.warn(
                "drain mismatch token={} primaryCount={} secondaryCount={} onlyInPrimary={} onlyInSecondary={}",
                tokenHash(token),
                primarySorted.size,
                secondarySorted.size,
                onlyInPrimary.size,
                onlyInSecondary.size,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

data class ServerState(
    val mailbox: MailboxStore = InMemoryMailboxState(),
    val trustProxy: Boolean = System.getenv("TRUST_PROXY")?.toBoolean() ?: false,
    val debug: Boolean = false,
)

/**
 * "redis" / "postgres": that store alone.
 * "dual-write-redis-primary" / "dual-write-postgres-primary": both stores, mirroring every
 * mutation from the primary to the secondary, used during the Redis -> Postgres migration.
 * Requires both REDIS_URL and DATABASE_URL.
 */
private fun buildMailboxStore(
    redisUrl: String?,
    databaseUrl: String?,
    storeMode: String,
): MailboxStore {
    val redisStore = redisUrl?.let { RedisMailboxState(JedisPooled(it)) }
    val postgresStore = databaseUrl?.let { PostgresMailboxState(createHikariDataSource(it)) }

    return when (storeMode) {
        "redis" ->
            redisStore?.also { println("Using Redis at ${URI(redisUrl).host}") }
                ?: InMemoryMailboxState().also { println("Using in-memory store") }
        "postgres" -> {
            requireNotNull(postgresStore) { "DATABASE_URL is required for MAILBOX_STORE_MODE=postgres" }
            println("Using Postgres store")
            postgresStore
        }
        "dual-write-redis-primary" -> {
            requireNotNull(redisStore) { "REDIS_URL is required for dual-write-redis-primary" }
            requireNotNull(postgresStore) { "DATABASE_URL is required for dual-write-redis-primary" }
            println("Using dual-write store (Redis primary, Postgres shadow)")
            DualWriteMailboxState(primary = redisStore, secondary = postgresStore)
        }
        "dual-write-postgres-primary" -> {
            requireNotNull(redisStore) { "REDIS_URL is required for dual-write-postgres-primary" }
            requireNotNull(postgresStore) { "DATABASE_URL is required for dual-write-postgres-primary" }
            println("Using dual-write store (Postgres primary, Redis shadow)")
            DualWriteMailboxState(primary = postgresStore, secondary = redisStore)
        }
        else -> error("Unknown MAILBOX_STORE_MODE: $storeMode")
    }
}

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val storeMode = System.getenv("MAILBOX_STORE_MODE") ?: "redis"
    val mailbox = buildMailboxStore(System.getenv("REDIS_URL"), System.getenv("DATABASE_URL"), storeMode)
    val state = ServerState(mailbox = mailbox)

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(state)
    }.start(wait = true)
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    monitor.subscribe(ApplicationStopped) { state.mailbox.close() }

    launch(Dispatchers.Default) {
        while (isActive) {
            delay(RATE_LIMIT_WINDOW_MS)
            state.mailbox.evict()
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        put("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@put call.respond(HttpStatusCode.BadRequest)

            if (token.length > 64 || msgId.length > 64) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }

            val ip = call.clientIp(state.trustProxy)
            if (!state.mailbox.checkIpRateLimit(ip)) return@put call.respond(HttpStatusCode.TooManyRequests)

            // Manual size check to prevent OOM from large bodies
            val contentLength = call.request.contentLength()
            if (contentLength != null && contentLength > 4 * 1024) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }

            val body = call.receiveText()
            if (body.length > 4 * 1024) {
                return@put call.respond(HttpStatusCode.PayloadTooLarge)
            }

            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload !is JsonObject || !payload.containsKey("type")) {
                return@put call.respond(HttpStatusCode.BadRequest)
            }
            if (!state.mailbox.post(token, payload, msgId)) return@put call.respond(HttpStatusCode.TooManyRequests)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64) return@get call.respond(HttpStatusCode.BadRequest)
            val startTime = System.currentTimeMillis()
            val messages = state.mailbox.drain(token) ?: return@get call.respond(HttpStatusCode.TooManyRequests)
            val responseString = json.encodeToString(messages)
            val elapsed = System.currentTimeMillis() - startTime
            if (!state.debug && elapsed < POLL_BASELINE_LATENCY_MS) delay(POLL_BASELINE_LATENCY_MS - elapsed)
            call.respondText(responseString, ContentType.Application.Json)
        }

        delete("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64) return@delete call.respond(HttpStatusCode.BadRequest)
            val ids = call.request.queryParameters["ids"]?.split(",")?.filter { it.isNotEmpty() }
            if (ids == null || ids.size > MAX_MESSAGES_PER_POLL) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            state.mailbox.deleteByIds(token, ids)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            if (token.length > 64 || msgId.length > 64) {
                return@delete call.respond(HttpStatusCode.BadRequest)
            }
            state.mailbox.deleteById(token, msgId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.clientIp(trustProxy: Boolean): String =
    if (trustProxy) {
        request.header("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.local.remoteHost
    } else {
        request.local.remoteHost
    }
