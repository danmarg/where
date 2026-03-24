package net.af0.where

import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage
import java.util.concurrent.ConcurrentHashMap

private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

/**
 * Encapsulates all mutable server state so each module() invocation (including in tests)
 * gets its own isolated state rather than sharing package-level globals.
 */
class ServerState {
    val locations = ConcurrentHashMap<String, UserLocation>()
    val sessions = ConcurrentHashMap<String, DefaultWebSocketServerSession>()
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
    val broadcast = json.encodeToString(
        WsMessage.serializer(),
        WsMessage.LocationsBroadcast(locations.values.toList())
    )
    val frame = Frame.Text(broadcast)
    for ((_, session) in sessions) {
        session.launch {
            runCatching { session.send(frame.copy()) }
        }
    }
}
