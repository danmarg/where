package net.af0.where

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
class LocationSyncClient(
    private val serverWsUrl: String,
    private val userId: String,
    private val onLocationsUpdate: (List<UserLocation>) -> Unit,
) {
    private val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(WebSockets)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    @Volatile internal var session: DefaultClientWebSocketSession? = null
    private var job: Job? = null

    fun connect() {
        job = scope.launch {
            while (isActive) {
                try {
                    client.webSocket("$serverWsUrl?userId=$userId") {
                        session = this
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val msg = runCatching {
                                    json.decodeFromString<WsMessage>(frame.readText())
                                }.getOrNull() ?: continue
                                if (msg is WsMessage.LocationsBroadcast) {
                                    onLocationsUpdate(msg.users)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                } finally {
                    session = null
                }
                delay(3_000) // reconnect backoff
            }
        }
    }

    fun sendLocation(lat: Double, lng: Double) {
        val msg = WsMessage.LocationUpdate(
            UserLocation(userId, lat, lng, currentTimeMillis())
        )
        val text = json.encodeToString(WsMessage.serializer(), msg)
        scope.launch {
            runCatching { session?.send(Frame.Text(text)) }
        }
    }

    fun sendLocationRemove() {
        val text = json.encodeToString(WsMessage.serializer(), WsMessage.LocationRemove)
        scope.launch {
            runCatching { session?.send(Frame.Text(text)) }
        }
    }

    fun disconnect() {
        job?.cancel()
    }
}

expect fun currentTimeMillis(): Long
