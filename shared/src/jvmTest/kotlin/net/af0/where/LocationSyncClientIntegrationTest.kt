package net.af0.where

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for LocationSyncClient against a real in-process Ktor/Netty server.
 * The server is defined inline here to avoid a circular dependency on the :server module.
 *
 * Uses runBlocking (not runTest) because the client runs on Dispatchers.Default and
 * requires real wall-clock time for network I/O.
 */
class LocationSyncClientIntegrationTest {

    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port: Int = 0

    @BeforeTest
    fun startServer() {
        server = embeddedServer(Netty, configure = {
            connector { port = 0 } // OS picks a free port
        }) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    val userId = call.parameters["userId"] ?: run {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "userId required"))
                        return@webSocket
                    }
                    // Minimal echo-broadcaster: LocationUpdate → broadcast with that user only;
                    // LocationRemove → broadcast empty list.
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val msg = runCatching {
                            json.decodeFromString<WsMessage>(frame.readText())
                        }.getOrNull() ?: continue

                        val broadcast = when (msg) {
                            is WsMessage.LocationUpdate -> WsMessage.LocationsBroadcast(listOf(msg.location))
                            is WsMessage.LocationRemove -> WsMessage.LocationsBroadcast(emptyList())
                            else -> continue
                        }
                        send(Frame.Text(json.encodeToString(WsMessage.serializer(), broadcast)))
                    }
                }
            }
        }
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }

    @Test
    fun connectAndReceivesBroadcastOnLocationUpdate() = runBlocking {
        val received = Channel<List<UserLocation>>(capacity = Channel.UNLIMITED)
        val client = LocationSyncClient(
            serverWsUrl = "ws://127.0.0.1:$port/ws",
            userId = "alice",
            onLocationsUpdate = { received.trySend(it) },
        )

        client.connect()
        awaitSession(client)

        client.sendLocation(37.7749, -122.4194)

        val update = withTimeout(5_000) { received.receive() }
        assertEquals(1, update.size)
        assertEquals("alice", update[0].userId)
        assertEquals(37.7749, update[0].lat)
        assertEquals(-122.4194, update[0].lng)

        client.disconnect()
    }

    @Test
    fun sendLocationRemoveResultsInEmptyBroadcast() = runBlocking {
        val received = Channel<List<UserLocation>>(capacity = Channel.UNLIMITED)
        val client = LocationSyncClient(
            serverWsUrl = "ws://127.0.0.1:$port/ws",
            userId = "bob",
            onLocationsUpdate = { received.trySend(it) },
        )

        client.connect()
        awaitSession(client)

        client.sendLocation(51.5, -0.1)
        withTimeout(5_000) { received.receive() } // drain location broadcast

        client.sendLocationRemove()
        val afterRemove = withTimeout(5_000) { received.receive() }
        assertTrue(afterRemove.isEmpty(), "Expected empty broadcast after location_remove")

        client.disconnect()
    }

    @Test
    fun disconnectStopsReceivingUpdates() = runBlocking {
        val received = Channel<List<UserLocation>>(capacity = Channel.UNLIMITED)
        val client = LocationSyncClient(
            serverWsUrl = "ws://127.0.0.1:$port/ws",
            userId = "carol",
            onLocationsUpdate = { received.trySend(it) },
        )

        client.connect()
        awaitSession(client)

        client.sendLocation(48.8566, 2.3522)
        withTimeout(5_000) { received.receive() } // confirm connected and broadcasting

        client.disconnect()

        // Allow a brief window for any in-flight messages to drain, then verify silence
        delay(300)
        assertNull(received.tryReceive().getOrNull(), "No messages expected after disconnect")
    }

    /** Polls until the client has an active WebSocket session (up to 5s). */
    private suspend fun awaitSession(client: LocationSyncClient) {
        withTimeout(5_000) {
            while (client.session == null) {
                delay(50)
            }
        }
    }
}
