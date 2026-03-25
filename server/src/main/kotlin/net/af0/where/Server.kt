package net.af0.where

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

/** TTL for mailbox messages: 60 minutes. */
private const val MAILBOX_TTL_MS = 60 * 60 * 1000L

private data class MailboxEntry(val payload: JsonElement, val expiresAt: Long)

/**
 * Anonymous mailbox routing table (§7.1, §10).
 *
 * Keys are opaque routing tokens (hex strings from the URL). Values are queues of
 * timestamped JSON payloads. The server never parses payload content.
 */
class MailboxState {
    private val mailboxes = ConcurrentHashMap<String, ConcurrentLinkedQueue<MailboxEntry>>()

    /** Store [payload] under [token]. Expired entries in the same queue are pruned first. */
    fun post(token: String, payload: JsonElement) {
        val queue = mailboxes.getOrPut(token) { ConcurrentLinkedQueue() }
        val now = System.currentTimeMillis()
        queue.removeIf { it.expiresAt <= now }
        queue.add(MailboxEntry(payload, now + MAILBOX_TTL_MS))
    }

    /**
     * Drain all non-expired messages for [token] and return them.
     * Returns an empty list for unknown or empty tokens (§7.2: indistinguishable responses).
     */
    fun drain(token: String): List<JsonElement> {
        val queue = mailboxes[token] ?: return emptyList()
        val now = System.currentTimeMillis()
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
    val locations = ConcurrentHashMap<String, UserLocation>()
    val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
    val mailbox = MailboxState()
}

fun main() {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module(state: ServerState = ServerState()) {
    install(ContentNegotiation) { json(json) }
    install(CallLogging)
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(json)
    }

    routing {
        get("/health") {
            call.respondText("ok")
        }

        get("/locations") {
            call.respond(state.locations.values.toList())
        }

        // ---------------------------------------------------------------------------
        // Mailbox API (E2EE, §7.1 / §10)
        // ---------------------------------------------------------------------------

        post("/inbox/{token}") {
            val token = call.parameters["token"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "token required")
                return@post
            }
            val body = call.receiveText()
            val payload = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (payload == null || payload == JsonNull) {
                call.respond(HttpStatusCode.BadRequest, "invalid json payload")
                return@post
            }
            state.mailbox.post(token, payload)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/inbox/{token}") {
            val token = call.parameters["token"] ?: run {
                call.respond(HttpStatusCode.BadRequest, "token required")
                return@get
            }
            val messages = state.mailbox.drain(token)
            call.respond(JsonArray(messages))
        }

        webSocket("/ws") {
            val userId = call.parameters["userId"]
            if (userId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "userId required"))
                return@webSocket
            }

            // Close any existing session for this userId before registering the new one.
            state.sessions.put(userId, this)?.close(CloseReason(CloseReason.Codes.NORMAL, "replaced by new session"))
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val msg = runCatching {
                            json.decodeFromString<WsMessage>(frame.readText())
                        }.getOrNull() ?: continue

                        if (msg is WsMessage.LocationUpdate && msg.location.userId == userId) {
                            state.locations[userId] = msg.location
                            state.broadcastLocations()
                        }
                        if (msg is WsMessage.LocationRemove) {
                            state.locations.remove(userId)
                            state.broadcastLocations()
                        }
                    }
                }
            } finally {
                // Only remove state if this session is still the active one for this userId.
                // A newer session may have already replaced us in the map.
                if (state.sessions.remove(userId, this)) {
                    state.locations.remove(userId)
                    state.broadcastLocations()
                }
            }
        }
    }
}

private suspend fun ServerState.broadcastLocations() {
    // Encode once, but wrap in a fresh Frame.Text per send to avoid races on the
    // ByteReadPacket cursor that is internal to Frame.
    val broadcast = json.encodeToString(
        WsMessage.serializer(),
        WsMessage.LocationsBroadcast(locations.values.toList())
    )
    for ((_, session) in sessions) {
        session.launch {
            runCatching { session.send(Frame.Text(broadcast)) }
        }
    }
}
