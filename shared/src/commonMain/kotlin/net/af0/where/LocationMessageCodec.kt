package net.af0.where

import kotlinx.serialization.json.Json
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.model.UserLocation
import net.af0.where.model.WsMessage

/**
 * Swift-friendly codec for the WebSocket and E2EE protocols. iOS uses URLSession for networking
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

    // ---------------------------------------------------------------------------
    // E2EE Mailbox Payloads (§9)
    // ---------------------------------------------------------------------------

    /** Encode any MailboxPayload to a JSON string for POSTing to /inbox/{token}. */
    fun encodeMailboxPayload(payload: MailboxPayload): String = json.encodeToString(MailboxPayload.serializer(), payload)

    /** Decode a list of MailboxPayloads from a GET /inbox/{token} response. */
    fun decodeMailboxPayloads(text: String): List<MailboxPayload>? =
        runCatching { json.decodeFromString<List<MailboxPayload>>(text) }.getOrNull()
}
