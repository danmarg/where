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
private const val MAX_QUEUE_DEPTH = 1000

/** Maximum POST requests per token within the rate-limit window. */
internal const val RATE_LIMIT_MAX_POSTS = 10

/** Maximum GET (poll) requests per token within the rate-limit window. */
internal const val RATE_LIMIT_MAX_GETS = 300

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
 *
 * Two implementations:
 *   - [InMemoryMailboxState] — used in tests and when REDIS_URL is absent.
 *   - [RedisMailboxState]    — used in production; state survives restarts.
 */
interface MailboxStore {
    /**
     * Store [payload] under [token]. Returns false if the token is rate-limited
     * or has reached [MAX_QUEUE_DEPTH].
     */
    fun post(
        token: String,
        payload: JsonElement,
    ): Boolean

    /**
     * Destructively drain all non-expired messages for [token].
     * Returns null if the request is rejected due to rate limiting.
     */
    fun drain(token: String): List<JsonElement>?

    /** Reclaim stale entries. No-op for implementations where the store handles expiry. */
    fun evict() {}

    /** Release any external resources (connections, pools). */
    fun close() {}
}

// ---------------------------------------------------------------------------
// In-memory implementation (tests / no Redis)
// ---------------------------------------------------------------------------

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long)

/**
 * Anonymous mailbox routing table backed by in-process maps (§7.1, §10).
 *
 * Keys are opaque routing tokens (hex strings from the URL). Values are queues of
 * timestamped JSON payloads. The server never parses payload content.
 */
class InMemoryMailboxState : MailboxStore {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()

    /** Per-token post timestamps used for rate limiting (sliding window). */
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    /** Per-token drain (poll) timestamps used for rate limiting. */
    private val drainTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    /** Internal dummy queue to equalize timing of unknown vs empty mailboxes (§7.2). */
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    /**
     * Store [payload] under [token].
     *
     * Expired entries are pruned before adding. Returns false (and does not store the
     * payload) if the token has exceeded [MAX_QUEUE_DEPTH] live messages or the
     * [RATE_LIMIT_MAX_POSTS] per-minute rate limit.
     */
    override fun post(
        token: String,
        payload: JsonElement,
    ): Boolean {
        val now = System.currentTimeMillis()

        // Rate-limit check: drop if too many posts in the sliding window.
        val times = postTimes.getOrPut(token) { ConcurrentLinkedQueue() }
        times.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (times.size >= RATE_LIMIT_MAX_POSTS) return false
        times.add(now)

        val queue = mailboxes.getOrPut(token) { ConcurrentLinkedQueue() }
        queue.removeIf { it.expiresAt <= now }

        // Queue-depth cap: drop if already at maximum.
        if (queue.size >= MAX_QUEUE_DEPTH) return false

        queue.add(MailboxEntry(payload, now + MAILBOX_TTL_MS))
        return true
    }

    /**
     * Remove map entries whose contents have fully expired.
     *
     * Both [mailboxes] and [postTimes] grow by one entry per unique token ever seen and
     * are never shrunk by [post] or [drain].  This method reclaims those zombie entries.
     *
     * Safety: [ConcurrentHashMap.computeIfPresent] holds a bucket-level lock while the
     * lambda runs, so a concurrent [post] (which uses [getOrPut] / [computeIfAbsent] on
     * the same key) will either complete before the lock is acquired — leaving a non-empty
     * queue that this method will not remove — or block until after the lock is released,
     * at which point it will create a fresh entry via [computeIfAbsent].  Either way no
     * data is lost.
     */
    override fun evict() = evictWithParams(rateLimitWindowMs = RATE_LIMIT_WINDOW_MS)

    /** Exposed for tests to control the rate-limit window (e.g. 0 to treat all entries as stale). */
    internal fun evictForTest(rateLimitWindowMs: Long) = evictWithParams(rateLimitWindowMs)

    private fun evictWithParams(rateLimitWindowMs: Long) {
        val now = System.currentTimeMillis()
        postTimes.forEach { (token, _) ->
            postTimes.computeIfPresent(token) { _, q ->
                q.removeIf { it <= now - rateLimitWindowMs }
                if (q.isEmpty()) null else q // null return removes the entry
            }
        }
        drainTimes.forEach { (token, _) ->
            drainTimes.computeIfPresent(token) { _, q ->
                q.removeIf { it <= now - rateLimitWindowMs }
                if (q.isEmpty()) null else q
            }
        }
        mailboxes.forEach { (token, _) ->
            mailboxes.computeIfPresent(token) { _, q ->
                q.removeIf { it.expiresAt <= now }
                if (q.isEmpty()) null else q
            }
        }
    }

    /**
     * Drain all non-expired messages for [token] and return them.
     * Returns null if rate-limited (§7.2: indistinguishable responses for unknown vs empty,
     * but rate-limiting returns 429).
     */
    override fun drain(token: String): List<JsonElement>? {
        val now = System.currentTimeMillis()

        // Rate-limit check for GET (§10)
        val times = drainTimes.getOrPut(token) { ConcurrentLinkedQueue() }
        times.removeIf { it < now - RATE_LIMIT_WINDOW_MS }
        if (times.size >= RATE_LIMIT_MAX_GETS) return null
        times.add(now)

        // Constant-Time Invariant (§7.2, §10.2):
        // Ensure mailbox lookup for empty/unknown tokens is indistinguishable from active tokens.
        // By using a shared dummy queue for misses, we execute nearly identical code paths.
        val queue = mailboxes[token] ?: dummyQueue

        val result = mutableListOf<JsonElement>()
        // Drain the entire queue; re-add nothing — this is a destructive read.
        generateSequence { queue.poll() }.forEach { entry ->
            if (entry.expiresAt > now) result.add(entry.payload)
        }
        return result
    }
}

// ---------------------------------------------------------------------------
// Redis implementation (production)
// ---------------------------------------------------------------------------

/**
 * Anonymous mailbox routing table backed by Redis (§7.1, §10).
 *
 * All operations are atomic via Lua scripts. Redis key TTLs replace the background
 * eviction job — no [evict] implementation is needed.
 *
 * Key layout:
 *   inbox:{token}     — Redis List of JSON payload strings
 *   ratelimit:{token} — Redis counter (INCR) for fixed-window rate limiting
 */
class RedisMailboxState(redisUrl: String) : MailboxStore {
    private val jedis = JedisPooled(URI(redisUrl))

    /**
     * Atomically: check fixed-window rate limit, check queue depth, append payload.
     *
     * Rate limiting uses a fixed 1-minute window (INCR + EXPIRE on first increment).
     * At window boundaries a client could burst up to 2× the per-minute limit, which
     * is acceptable for location sharing at this scale.
     *
     * Returns 1 on success, 0 if rate-limited or queue full.
     */
    private val postScript =
        """
        local maxPosts = tonumber(ARGV[1])
        local maxDepth = tonumber(ARGV[2])
        local payload  = ARGV[3]
        local ttlSec   = tonumber(ARGV[4])
        local rlTtlSec = tonumber(ARGV[5])
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('EXPIRE', KEYS[1], rlTtlSec) end
        if count > maxPosts then return 0 end
        if redis.call('LLEN', KEYS[2]) >= maxDepth then return 0 end
        redis.call('RPUSH', KEYS[2], payload)
        redis.call('EXPIRE', KEYS[2], ttlSec)
        return 1
        """.trimIndent()

    /**
     * Atomically: check rate limit for GET, then drain.
     */
    private val drainScript =
        """
        local maxGets  = tonumber(ARGV[1])
        local rlTtlSec = tonumber(ARGV[2])
        local count = redis.call('INCR', KEYS[1])
        if count == 1 then redis.call('EXPIRE', KEYS[1], rlTtlSec) end
        if count > maxGets then return nil end

        local msgs = redis.call('LRANGE', KEYS[2], 0, 49)
        if #msgs > 0 then redis.call('LTRIM', KEYS[2], #msgs, -1) end
        return msgs
        """.trimIndent()

    override fun post(
        token: String,
        payload: JsonElement,
    ): Boolean {
        val result =
            jedis.eval(
                postScript,
                listOf("ratelimit:$token", "inbox:$token"),
                listOf(
                    RATE_LIMIT_MAX_POSTS.toString(),
                    MAX_QUEUE_DEPTH.toString(),
                    payload.toString(),
                    (MAILBOX_TTL_MS / 1000).toString(),
                    (RATE_LIMIT_WINDOW_MS / 1000).toString(),
                ),
            )
        return result == 1L
    }

    override fun drain(token: String): List<JsonElement>? {
        @Suppress("UNCHECKED_CAST")
        val result =
            jedis.eval(
                drainScript,
                listOf("ratelimit-get:$token", "inbox:$token"),
                listOf(
                    RATE_LIMIT_MAX_GETS.toString(),
                    (RATE_LIMIT_WINDOW_MS / 1000).toString(),
                ),
            ) ?: return null

        val msgs = result as List<*>
        return msgs.map { item: Any? ->
            val str =
                when (item) {
                    is String -> item
                    is ByteArray -> item.decodeToString()
                    else -> item.toString()
                }
            json.parseToJsonElement(str)
        }
    }

    // evict() is intentionally left as the interface no-op: Redis TTL handles expiry.

    override fun close() = jedis.close()
}

// ---------------------------------------------------------------------------
// ServerState
// ---------------------------------------------------------------------------

/**
 * Encapsulates all mutable server state so each module() invocation (including in tests)
 * gets its own isolated state rather than sharing package-level globals.
 */
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

    // Background eviction: remove zombie token entries from the in-memory mailbox and
    // rate-limit maps. This is a no-op when using RedisMailboxState (TTL handles it).
    launch(Dispatchers.Default) {
        while (isActive) {
            delay(EVICTION_INTERVAL_MS)
            state.mailbox.evict()
        }
    }

    if (state.debug) {
        intercept(ApplicationCallPipeline.Monitoring) {
            val remote = call.request.local.remoteHost
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            application.log.info("DEBUG: [Connect] $remote -> $method $uri")
            try {
                proceed()
            } finally {
                application.log.info("DEBUG: [Disconnect] $remote -> $method $uri")
            }
        }
    }

    routing {
        get("/health") {
            call.respondText("ok")
        }

        // ---------------------------------------------------------------------------
        // Mailbox API (E2EE, §7.1 / §10)
        // ---------------------------------------------------------------------------

        post("/inbox/{token}") {
            val token =
                call.parameters["token"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "token required")
                    return@post
                }

            if (state.debug) application.log.info("DEBUG: [Post] token=$token")

            val body = call.receiveText()
            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload == null || payload == JsonNull) {
                if (state.debug) application.log.info("DEBUG: [Post] token=$token - Invalid JSON")
                call.respond(HttpStatusCode.BadRequest, "invalid json payload")
                return@post
            }
            if (!state.mailbox.post(token, payload)) {
                application.log.info("Rejected [Post] token=$token - (full or rate-limited)")
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            if (state.debug) application.log.info("DEBUG: [Post] token=$token - Success")
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token =
                call.parameters["token"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "token required")
                    return@get
                }

            if (state.debug) application.log.info("DEBUG: [Poll] token=$token")

            val startTime = System.currentTimeMillis()
            val messages = state.mailbox.drain(token)
            if (messages == null) {
                application.log.info("Rejected [Poll] token=$token - (rate-limited)")
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }

            if (state.debug) application.log.info("DEBUG: [Poll] token=$token - Returning ${messages.size} messages")

            val responseString = json.encodeToString(messages)
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed < POLL_BASELINE_LATENCY_MS) {
                delay(POLL_BASELINE_LATENCY_MS - elapsed)
            }

            call.respondText(responseString, ContentType.Application.Json)
        }
    }
}
