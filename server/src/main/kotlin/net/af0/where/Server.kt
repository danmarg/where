package net.af0.where

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import redis.clients.jedis.JedisPooled
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
    private val getTimes  = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val ipTimes   = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun checkPost(token: String): Boolean = check(postTimes, token, RATE_LIMIT_MAX_POSTS)
    fun checkGet(token: String): Boolean  = check(getTimes,  token, RATE_LIMIT_MAX_GETS)
    fun checkIp(ip: String): Boolean      = check(ipTimes,   ip,    IP_RATE_LIMIT_MAX)

    fun evict(windowMs: Long = RATE_LIMIT_WINDOW_MS) {
        val cutoff = System.currentTimeMillis() - windowMs
        for (q in postTimes.values) q.removeIf { it < cutoff }
        for (q in getTimes.values)  q.removeIf { it < cutoff }
        for (q in ipTimes.values)   q.removeIf { it < cutoff }
    }

    private fun check(map: ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>, key: String, limit: Int): Boolean {
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
        redis.call('EXPIRE', inboxKey, ttlSec)
        redis.call('EXPIRE', dataKey, ttlSec)

        if msgId ~= "" then
            redis.call('SADD', receivedIdsKey, msgId)
            redis.call('EXPIRE', receivedIdsKey, ttlSec)
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
// Main
// ---------------------------------------------------------------------------

data class ServerState(
    val mailbox: MailboxStore = InMemoryMailboxState(),
    val trustProxy: Boolean = System.getenv("TRUST_PROXY")?.toBoolean() ?: false,
    val debug: Boolean = false,
)

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val redisUrl = System.getenv("REDIS_URL")
    val state =
        if (redisUrl != null) {
            println("Using Redis at ${URI(redisUrl).host}")
            ServerState(mailbox = RedisMailboxState(JedisPooled(redisUrl)))
        } else {
            println("Using in-memory store")
            ServerState()
        }

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
