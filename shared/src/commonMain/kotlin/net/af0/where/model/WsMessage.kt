package net.af0.where.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
@kotlin.native.HiddenFromObjC
@Serializable
sealed class WsMessage {
    @Serializable
    @SerialName("location")
    data class LocationUpdate(val location: UserLocation) : WsMessage()

    @Serializable
    @SerialName("locations")
    data class LocationsBroadcast(val users: List<UserLocation>) : WsMessage()

    @Serializable
    @SerialName("location_remove")
    object LocationRemove : WsMessage()
}
