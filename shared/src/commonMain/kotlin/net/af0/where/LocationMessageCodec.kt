package net.af0.where

import kotlinx.serialization.json.Json
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage

/**
 * Swift-friendly codec for the WebSocket protocol. iOS uses URLSession for networking
 * but delegates all JSON encoding/decoding to this object so the protocol stays in KMP.
 */
object LocationMessageCodec {
    private val json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
        }

    fun encodeLocationUpdate(
        userId: String,
        lat: Double,
        lng: Double,
        timestamp: Long,
    ): String {
        val msg = WsMessage.LocationUpdate(UserLocation(userId, lat, lng, timestamp))
        return json.encodeToString(WsMessage.serializer(), msg)
    }

    fun encodeLocationRemove(): String = json.encodeToString(WsMessage.serializer(), WsMessage.LocationRemove)

    /** Returns the list of users from a locations-broadcast message, or null if not applicable. */
    fun decodeUsers(text: String): List<UserLocation>? {
        val msg = runCatching { json.decodeFromString<WsMessage>(text) }.getOrNull() ?: return null
        return (msg as? WsMessage.LocationsBroadcast)?.users
    }
}
