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
import kotlinx.serialization.json.JsonNull
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

interface MailboxStore : AutoCloseable {
    fun checkIpRateLimit(ip: String): Boolean = true

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

class InMemoryMailboxState : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val drainTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val receivedIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val receivedIdsOrder = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val ipPostTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    private val locks = ConcurrentHashMap<String, Any>()

    private fun getLock(token: String) = locks.getOrPut(token) { Any() }

    override fun checkIpRateLimit(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val times = ipPostTimes.getOrPut(ip) { ConcurrentLinkedQueue() }
        times.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (times.size >= 2000) return false
        times.add(now)
        return true
    }

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

            val times = postTimes.getOrPut(token) { ConcurrentLinkedQueue() }
            times.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
            if (times.size >= RATE_LIMIT_MAX_POSTS) return false
            times.add(now)

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
        val now = System.currentTimeMillis()
        val times = drainTimes.getOrPut(token) { ConcurrentLinkedQueue() }
        times.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (times.size >= RATE_LIMIT_MAX_GETS) return null
        times.add(now)

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

    internal fun evictWithParams(rateLimitWindowMs: Long) {
        val now = System.currentTimeMillis()
        postTimes.forEach { (_, times) -> times.removeIf { it < now - rateLimitWindowMs } }
        drainTimes.forEach { (_, times) -> times.removeIf { it < now - rateLimitWindowMs } }
        ipPostTimes.forEach { (_, times) -> times.removeIf { it < now - rateLimitWindowMs } }

        mailboxes.forEach { (token, _) ->
            mailboxes.computeIfPresent(token) { _, q ->
                q.removeIf { it.expiresAt <= now }
                if (q.isEmpty()) null else q
            }
        }

        // receivedIds can grow quite large, so we also need to cap them per token.
        // For simplicity in this in-memory implementation, we just clear the oldest if it exceeds MAX_QUEUE_DEPTH.
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

class RedisMailboxState(val jedis: JedisPooled) : MailboxStore {
    private val postScript =
        """
        local rlKey = KEYS[1]
        local inboxKey = KEYS[2]
        local receivedIdsKey = KEYS[3]
        local dataKey = KEYS[4]

        local maxPosts = tonumber(ARGV[1])
        local maxQueueDepth = tonumber(ARGV[2])
        local payload = ARGV[3]
        local ttlSec = tonumber(ARGV[4])
        local rlTtlSec = tonumber(ARGV[5])
        local msgId = ARGV[6]
        local score = tonumber(ARGV[7])

        -- Check msgId for idempotency
        if msgId ~= "" then
            if redis.call('SISMEMBER', receivedIdsKey, msgId) == 1 then
                return 1
            end
        end

        -- Rate limit check
        local count = redis.call('INCR', rlKey)
        if count == 1 then redis.call('EXPIRE', rlKey, rlTtlSec) end
        if count > maxPosts then return 0 end

        -- Queue depth check (optional but recommended)
        if redis.call('ZCARD', inboxKey) >= maxQueueDepth then
            return 0
        end

        -- Store payload
        if msgId == "" then
            msgId = redis.call('INCR', 'msgid-seq')
        end
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
        local rlKey = KEYS[1]
        local inboxKey = KEYS[2]
        local dataKey = KEYS[3]

        local maxGets = tonumber(ARGV[1])
        local rlTtlSec = tonumber(ARGV[2])

        -- Rate limit check
        local count = redis.call('INCR', rlKey)
        if count == 1 then redis.call('EXPIRE', rlKey, rlTtlSec) end
        if count > maxGets then return nil end

        local ids = redis.call('ZRANGE', inboxKey, 0, tonumber(ARGV[3]) - 1)
        if #ids == 0 then return {} end

        -- Fetch payloads from Hash. Note: some might be nil if deleted between ZRANGE and HMGET
        local payloads = redis.call('HMGET', dataKey, unpack(ids))
        for i, payload in ipairs(payloads) do
            if not payload then
                -- Cleanup: remove orphaned ID from the ZSET if the data hash field is missing
                redis.call('ZREM', inboxKey, ids[i])
            end
        end
        return payloads
        """.trimIndent()

    override fun post(
        token: String,
        payload: JsonElement,
        msgId: String?,
    ): Boolean {
        val result =
            jedis.eval(
                postScript,
                listOf("ratelimit:$token", "inbox:$token", "receivedIds:$token", "inbox-data:$token"),
                listOf(
                    RATE_LIMIT_MAX_POSTS.toString(),
                    MAX_QUEUE_DEPTH.toString(),
                    payload.toString(),
                    (MAILBOX_TTL_MS / 1000).toString(),
                    (RATE_LIMIT_WINDOW_MS / 1000).toString(),
                    msgId ?: "",
                    System.currentTimeMillis().toString(),
                ),
            )
        return result == 1L
    }

    override fun drain(token: String): List<JsonElement>? {
        @Suppress("UNCHECKED_CAST")
        val result =
            jedis.eval(
                drainScript,
                listOf("ratelimit-get:$token", "inbox:$token", "inbox-data:$token"),
                listOf(
                    RATE_LIMIT_MAX_GETS.toString(),
                    (RATE_LIMIT_WINDOW_MS / 1000).toString(),
                    MAX_MESSAGES_PER_POLL.toString(),
                ),
            ) ?: return null
        return (result as List<*>).filterNotNull().map { item ->
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
