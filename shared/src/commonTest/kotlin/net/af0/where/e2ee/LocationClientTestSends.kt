package net.af0.where.e2ee

/**
 * Sends for tests that read no sharing or pause state first, so the stamp is taken at the call.
 * App code passes a [LocationClient.sharingStamp] taken before its state reads; SharingOrderTest
 * covers what the stamp is for.
 */
suspend fun LocationClient.sendLocation(
    lat: Double,
    lng: Double,
    pausedFriendIds: Set<String> = emptySet(),
    stationary: Boolean = false,
) = sendLocation(lat, lng, pausedFriendIds, stationary, stamp = sharingStamp())

suspend fun LocationClient.sendLocationToFriend(
    friendId: String,
    lat: Double,
    lng: Double,
    stationary: Boolean = false,
) = sendLocationToFriend(friendId, lat, lng, stationary, stamp = sharingStamp())
