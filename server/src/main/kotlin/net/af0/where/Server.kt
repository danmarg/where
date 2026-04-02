package net.af0.where

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private val json =
    Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

/** TTL for mailbox messages: 60 minutes. */
private const val MAILBOX_TTL_MS = 60 * 60 * 1000L

/** Maximum messages retained per token. Prevents unbounded memory growth from floods. */
private const val MAX_QUEUE_DEPTH = 100

/** Maximum POST requests per token within the rate-limit window. */
private const val RATE_LIMIT_MAX_POSTS = 60

/** Rate-limit window duration in milliseconds (1 minute). */
private const val RATE_LIMIT_WINDOW_MS = 60_000L

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long)

/**
 * Anonymous mailbox routing table (§7.1, §10).
 *
 * Keys are opaque routing tokens (hex strings from the URL). Values are queues of
 * timestamped JSON payloads. The server never parses payload content.
 */
class MailboxState {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()

    /** Per-token post timestamps used for rate limiting (sliding window). */
    private val postTimes = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    /** Internal dummy queue to equalize timing of unknown vs empty mailboxes (§7.2). */
    private val dummyQueue = ConcurrentLinkedQueue<MailboxEntry>()

    /**
     * Store [payload] under [token].
     *
     * Expired entries are pruned before adding. Returns false (and does not store the
     * payload) if the token has exceeded [MAX_QUEUE_DEPTH] live messages or the
     * [RATE_LIMIT_MAX_POSTS] per-minute rate limit.
     */
    fun post(
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
     * Drain all non-expired messages for [token] and return them.
     * Returns an empty list for unknown or empty tokens (§7.2: indistinguishable responses).
     */
    fun drain(token: String): List<JsonElement> {
        val now = System.currentTimeMillis()

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

/**
 * Encapsulates all mutable server state so each module() invocation (including in tests)
 * gets its own isolated state rather than sharing package-level globals.
 */
class ServerState {
    val mailbox = MailboxState()
}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)

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
            val body = call.receiveText()
            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload == null || payload == JsonNull) {
                call.respond(HttpStatusCode.BadRequest, "invalid json payload")
                return@post
            }
            if (!state.mailbox.post(token, payload)) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token =
                call.parameters["token"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "token required")
                    return@get
                }
            val messages = state.mailbox.drain(token)
            call.respond(JsonArray(messages))
        }
    }
}
