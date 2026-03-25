package net.af0.where

import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage
import kotlin.test.Test
import kotlin.test.assertIs

class ServerTest {

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    @Test
    fun locationUpdateIsBroadcastBackToSender() = testApplication {
        application { module(ServerState()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ws?userId=alice") {
            // userId in the location must match the connection's userId or the server drops it
            val location = UserLocation("alice", 37.7749, -122.4194, 0L)
            send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.LocationUpdate(location))))
            val frame = incoming.receive()
            val broadcast = json.decodeFromString<WsMessage>((frame as Frame.Text).readText())
            assertIs<WsMessage.LocationsBroadcast>(broadcast)
            val users = (broadcast as WsMessage.LocationsBroadcast).users
            assert(users.any { it.userId == "alice" }) { "Expected alice in broadcast" }
        }
    }

    @Test
    fun locationRemovedOnDisconnect() = testApplication {
        application { module(ServerState()) }
        val client = createClient { install(WebSockets) }
        // Connect and send a location, then disconnect — server should remove it
        client.webSocket("/ws?userId=carol") {
            val location = UserLocation("carol", 1.0, 2.0, 0L)
            send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.LocationUpdate(location))))
            // Receive the broadcast back
            incoming.receive()
            // Close the session
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        // After disconnect, /locations should be empty for carol
        val httpClient = createClient {}
        val response = httpClient.get("/locations")
        // The response body should not contain carol
        assert(!response.bodyAsText().contains("carol"))
    }

    @Test
    fun locationRemoveMessageClearsPin() = testApplication {
        application { module(ServerState()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ws?userId=dave") {
            val location = UserLocation("dave", 5.0, 6.0, 0L)
            send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.LocationUpdate(location))))
            incoming.receive() // consume broadcast
            send(Frame.Text(json.encodeToString(WsMessage.serializer(), WsMessage.LocationRemove)))
            val frame = incoming.receive()
            val broadcast = json.decodeFromString<WsMessage>((frame as Frame.Text).readText())
            assertIs<WsMessage.LocationsBroadcast>(broadcast)
            val users = (broadcast as WsMessage.LocationsBroadcast).users
            assert(users.none { it.userId == "dave" }) { "dave's location should be removed" }
        }
    }
}
