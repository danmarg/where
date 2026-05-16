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
import redis.clients.jedis.JedisPooled
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private val json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

/** TTL for mailbox messages: 7 days, matching the session staleness threshold. */
private const val MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L

/** Maximum messages retained per token. Prevents unbounded memory growth from floods. */
private const val MAX_QUEUE_DEPTH = 10000

/** Maximum POST requests per token within the rate-limit window.
 * Increased 10x to accommodate WAL retry bursts during reconnects (e.g. 20 friends x 50 retries).
 */
internal const val RATE_LIMIT_MAX_POSTS = 1000

/** Maximum GET (poll) requests per token within the rate-limit window.
 * Increased 10x to prevent polling lockouts during rapid state recovery.
 */
internal const val RATE_LIMIT_MAX_GETS = 2000

/** Rate-limit window duration in milliseconds (1 minute). */
private const val RATE_LIMIT_WINDOW_MS = 60_000L

/** Baseline latency for mailbox poll requests to mitigate timing side-channel (§10.2). */
private const val POLL_BASELINE_LATENCY_MS = 50L

/** How often the background eviction job sweeps stale map entries (in-memory only). */
private const val EVICTION_INTERVAL_MS = 5 * 60_000L

// ---------------------------------------------------------------------------
// MailboxStore interface
// ---------------------------------------------------------------------------

/**
 * Backing store for the anonymous mailbox routing table.
 */
interface MailboxStore {
    /**
     * Store [payload] under [token]. Returns false if the token is rate-limited
     * or has reached [MAX_QUEUE_DEPTH].
     * If [msgId] is provided, implementation should ensure idempotency.
     */
    fun post(
        token: String,
        payload: JsonElement,
        msgId: String? = null,
    ): Boolean

    /**
     * Non-destructively read all non-expired messages for [token].
     */
    fun drain(token: String): List<JsonElement>?

    /**
     * Remove a specific message by [msgId]. Idempotent.
     */
    fun deleteById(
        token: String,
        msgId: String,
    ): Boolean

    /**
     * Remove multiple specific messages by [msgIds]. Idempotent.
     */
    fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int

    /** Reclaim stale entries. No-op for implementations where the store handles expiry. */
    fun evict() {}

    /** Release any external resources (connections, pools). */
    fun close() {}

    /** Check if the source IP is rate-limited. */
    fun checkIpRateLimit(ip: String): Boolean = true
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

                // Limit size of receivedIds to prevent unbounded growth
                val order = receivedIdsOrder.getOrPut(token) { ConcurrentLinkedQueue() }
                if (ids.size >= MAX_QUEUE_DEPTH * 2) {
                    val oldest = order.poll()
                    if (oldest != null) ids.remove(oldest)
                }
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
            .take(50)
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

    override fun evict() = evictWithParams(rateLimitWindowMs = RATE_LIMIT_WINDOW_MS)

    internal fun evictForTest(rateLimitWindowMs: Long) = evictWithParams(rateLimitWindowMs)

    private fun evictWithParams(rateLimitWindowMs: Long) {
        val now = System.currentTimeMillis()
        postTimes.forEach { (token, _) ->
            postTimes.computeIfPresent(token) { _, q ->
                q.removeIf { it <= now - rateLimitWindowMs }
                if (q.isEmpty()) null else q
            }
        }
        drainTimes.forEach { (token, _) ->
            drainTimes.computeIfPresent(token) { _, q ->
                q.removeIf { it <= now - rateLimitWindowMs }
                if (q.isEmpty()) null else q
            }
        }
        receivedIds.forEach { (token, _) ->
            receivedIds.computeIfPresent(token) { _, set ->
                if (mailboxes[token] == null && postTimes[token] == null) {
                    receivedIdsOrder.remove(token)
                    null
                } else {
                    set
                }
            }
        }
        mailboxes.forEach { (token, _) ->
            mailboxes.computeIfPresent(token) { _, q ->
                q.removeIf { it.expiresAt <= now }
                if (q.isEmpty()) null else q
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Redis implementation (production)
// ---------------------------------------------------------------------------

class RedisMailboxState(redisUrl: String) : MailboxStore {
    private val jedis = JedisPooled(URI(redisUrl))

    private val postScript =
        """
        local rlKey     = KEYS[1]
        local inboxKey  = KEYS[2] -- ZSET (order)
        local idsKey    = KEYS[3] -- SET (idempotency)
        local dataKey   = KEYS[4] -- HASH (payload)
        local maxPosts  = tonumber(ARGV[1])
        local maxDepth  = tonumber(ARGV[2])
        local payload   = ARGV[3]
        local ttlSec    = tonumber(ARGV[4])
        local rlTtlSec  = tonumber(ARGV[5])
        local msgId     = ARGV[6]
        local nowMs     = tonumber(ARGV[7])

        if msgId ~= "" then
            if redis.call('SISMEMBER', idsKey, msgId) == 1 then return 1 end
        else
            msgId = "auto:" .. redis.call('INCR', 'msgid_gen')
        end

        local count = redis.call('INCR', rlKey)
        if count == 1 then redis.call('EXPIRE', rlKey, rlTtlSec) end
        if count > maxPosts then return 0 end
        if redis.call('ZCARD', inboxKey) >= maxDepth then return 0 end

        redis.call('ZADD', inboxKey, nowMs, msgId)
        redis.call('HSET', dataKey, msgId, payload)
        redis.call('EXPIRE', inboxKey, ttlSec)
        redis.call('EXPIRE', dataKey, ttlSec)

        if ARGV[6] ~= "" then
            redis.call('SADD', idsKey, msgId)
            redis.call('EXPIRE', idsKey, ttlSec)
        end
        return 1
        """.trimIndent()

    private val drainScript =
        """
        local rlKey    = KEYS[1]
        local inboxKey = KEYS[2] -- ZSET
        local dataKey  = KEYS[3] -- HASH
        local maxGets  = tonumber(ARGV[1])
        local rlTtlSec = tonumber(ARGV[2])

        local count = redis.call('INCR', rlKey)
        if count == 1 then redis.call('EXPIRE', rlKey, rlTtlSec) end
        if count > maxGets then return nil end

        local ids = redis.call('ZRANGE', inboxKey, 0, 49)
        if #ids == 0 then return {} end

        -- Fetch payloads from Hash. Note: some might be nil if deleted between ZRANGE and HMGET
        return redis.call('HMGET', dataKey, unpack(ids))
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
        val deleteByIdScript =
            """
            local inboxKey = KEYS[1]
            local dataKey  = KEYS[2]
            local msgId    = ARGV[1]
            local r1 = redis.call('ZREM', inboxKey, msgId)
            local r2 = redis.call('HDEL', dataKey, msgId)
            if r1 == 1 or r2 == 1 then return 1 end
            return 0
            """.trimIndent()
        val result = jedis.eval(deleteByIdScript, listOf("inbox:$token", "inbox-data:$token"), listOf(msgId))
        return result == 1L
    }

    override fun deleteByIds(
        token: String,
        msgIds: List<String>,
    ): Int {
        if (msgIds.isEmpty()) return 0
        val deleteByIdsScript =
            """
            local inboxKey = KEYS[1]
            local dataKey  = KEYS[2]
            local count = 0
            for _, id in ipairs(ARGV) do
                local r1 = redis.call('ZREM', inboxKey, id)
                local r2 = redis.call('HDEL', dataKey, id)
                if r1 == 1 or r2 == 1 then count = count + 1 end
            end
            return count
            """.trimIndent()
        val result = jedis.eval(deleteByIdsScript, listOf("inbox:$token", "inbox-data:$token"), msgIds)
        return (result as Long).toInt()
    }

    override fun checkIpRateLimit(ip: String): Boolean {        val rlKey = "ratelimit-ip:$ip"
        val count = jedis.incr(rlKey)
        if (count == 1L) jedis.expire(rlKey, RATE_LIMIT_WINDOW_MS / 1000)
        return count <= 100 // 100 posts per min per IP
    }

    override fun close() = jedis.close()
}

// ---------------------------------------------------------------------------
// ServerState
// ---------------------------------------------------------------------------

class ServerState(
    val debug: Boolean = false,
    val mailbox: MailboxStore = InMemoryMailboxState(),
)

fun main(args: Array<String>) {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val debug = args.contains("--debug") || System.getenv("WHERE_DEBUG") == "true"
    val redisUrl = System.getenv("REDIS_URL")
    val mailbox: MailboxStore =
        if (redisUrl != null) RedisMailboxState(redisUrl) else InMemoryMailboxState()
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(ServerState(debug, mailbox))
    }.start(wait = true)
    mailbox.close()
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    monitor.subscribe(ApplicationStopped) { state.mailbox.close() }

    launch(Dispatchers.Default) {
        while (isActive) {
            delay(EVICTION_INTERVAL_MS)
            state.mailbox.evict()
        }
    }

    if (state.debug) {
        intercept(ApplicationCallPipeline.Monitoring) {
            application.log.info(
                "DEBUG: [Connect] ${call.request.local.remoteHost} -> ${call.request.httpMethod.value} ${call.request.uri}",
            )
            try {
                proceed()
            } finally {
                application.log.info(
                    "DEBUG: [Disconnect] ${call.request.local.remoteHost} -> ${call.request.httpMethod.value} ${call.request.uri}",
                )
            }
        }
    }

    routing {
        get("/health") { call.respondText("ok") }

        put("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val ip = call.request.local.remoteHost
            if (!state.mailbox.checkIpRateLimit(ip)) return@put call.respond(HttpStatusCode.TooManyRequests)

            val body = call.receiveText()
            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload == null || payload == JsonNull) return@put call.respond(HttpStatusCode.BadRequest)
            if (!state.mailbox.post(token, payload, msgId)) return@put call.respond(HttpStatusCode.TooManyRequests)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val startTime = System.currentTimeMillis()
            val messages = state.mailbox.drain(token) ?: return@get call.respond(HttpStatusCode.TooManyRequests)
            val responseString = json.encodeToString(messages)
            val elapsed = System.currentTimeMillis() - startTime
            if (!state.debug && elapsed < POLL_BASELINE_LATENCY_MS) delay(POLL_BASELINE_LATENCY_MS - elapsed)
            call.respondText(responseString, ContentType.Application.Json)
        }

        delete("/inbox/{token}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val ids = call.request.queryParameters["ids"]?.split(",")?.filter { it.isNotEmpty() }
            if (ids == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@delete
            }
            state.mailbox.deleteByIds(token, ids)
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/inbox/{token}/{msgId}") {
            val token = call.parameters["token"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val msgId = call.parameters["msgId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            state.mailbox.deleteById(token, msgId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
