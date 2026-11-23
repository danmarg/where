package net.af0.where.model

import kotlinx.serialization.Serializable

@Serializable
data class UserLocation(
    val userId: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
)

/**
 * Reduces a poll's updates to the newest one per user (highest timestamp; the later one in
 * delivery order wins ties), keeping the order in which each user's winner was delivered, so a
 * platform applying them in sequence never ends on an older fix.
 */
fun newestPerUser(updates: List<UserLocation>): List<UserLocation> {
    if (updates.size < 2) return updates
    val best = HashMap<String, Int>()
    updates.forEachIndexed { i, u ->
        val j = best[u.userId]
        if (j == null || u.timestamp >= updates[j].timestamp) best[u.userId] = i
    }
    return updates.filterIndexed { i, u -> best[u.userId] == i }
}
