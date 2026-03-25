package net.af0.where.model

import kotlinx.serialization.Serializable

@Serializable
data class UserLocation(
    val userId: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
)
