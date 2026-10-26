package net.af0.where

import net.af0.where.e2ee.LocationClient

/**
 * Sends for tests that read no sharing or pause state first, so the stamp is taken at the call.
 * Shared's commonTest has the same pair; its test sources aren't on this classpath.
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
