package net.af0.where.e2ee

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.af0.where.model.UserLocation

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, ratchet rotation, and sending for all platforms.
 *
 * @param baseUrl Server base URL (e.g. "http://localhost:8080").
 * @param store   Persistent E2EE state storage.
 */
open class LocationClient(
    private val baseUrl: String,
    private val store: E2eeStore,
) {
    private val pollMutex = Mutex()

    /**
     * Poll all friends and the pending invite (if any).
     *
     * Poll calls are serialized to prevent concurrent ratchet state mutations.
     *
     * @param isForeground Whether the app is currently in the foreground.
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(isForeground: Boolean = true): List<UserLocation> =
        pollMutex.withLock {
            val allUpdates = mutableListOf<UserLocation>()
            var lastError: Exception? = null

            val friends = store.listFriends()

            if (friends.isEmpty() && isForeground) {
                try {
                    E2eeMailboxClient.poll(baseUrl, "00000000000000000000000000000000")
                } catch (e: ServerException) {
                    if (e.statusCode != 404) lastError = e
                } catch (e: Exception) {
                    lastError = e
                }
            }

            for (friend in friends) {
                try {
                    val friendUpdates = pollFriend(friend.id)
                    allUpdates.addAll(friendUpdates)
                } catch (e: Exception) {
                    lastError = e
                }
            }

            lastError?.let { throw it }
            allUpdates
        }

    /**
     * Poll a specific friend's mailbox.
     */
    private suspend fun pollFriend(friendId: String): List<UserLocation> {
        val resultLocations = mutableListOf<UserLocation>()
        val friendBefore = store.getFriend(friendId) ?: return emptyList()
        var currentTokenToPoll = friendBefore.session.recvToken.toHex()
        var maxFollows = 5 // Prevent infinite loops just in case

        while (maxFollows-- > 0) {
            val messages = E2eeMailboxClient.poll(baseUrl, currentTokenToPoll)
            if (messages.isEmpty()) break

            val result = store.processBatch(friendId, currentTokenToPoll, messages) ?: break

            for (out in result.outgoing) {
                E2eeMailboxClient.post(baseUrl, out.token, out.payload)
            }

            resultLocations.addAll(result.decryptedLocations.map { loc ->
                UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
            })

            // Did the recvToken change during processing? If so, follow it immediately.
            val updatedFriend = store.getFriend(friendId) ?: break
            val newToken = updatedFriend.session.recvToken.toHex()
            if (newToken != currentTokenToPoll) {
                currentTokenToPoll = newToken
            } else {
                break // No token rotation, we've caught up
            }
        }

        // Automated keepalive (§5.3): send a keepalive message if we received a new DH key
        // but are not currently sharing location.
        val friendAfter = store.getFriend(friendId)
        if (friendAfter != null && friendBefore.session.remoteDhPub != friendAfter.session.remoteDhPub) {
            try {
                sendKeepalive(friendId)
            } catch (_: Exception) {
            }
        }

        return resultLocations
    }

    /**
     * Encrypt and send a location update to all active (non-paused) friends.
     */
    open suspend fun sendLocation(
        lat: Double,
        lng: Double,
        pausedFriendIds: Set<String> = emptySet(),
    ) {
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        var lastError: Exception? = null

        for (friend in store.listFriends()) {
            if (friend.id in pausedFriendIds) continue
            // Skip friends who have not yet confirmed the handshake, UNLESS we are the
            // initiator (Alice), in which case we must send the first message to Bob to
            // trigger confirmation on his side.
            if (!friend.isConfirmed && !friend.isInitiator) continue
            // Skip friends from whom Alice has not received a message in 7 days.
            if (friend.isStale) continue
            try {
                sendMessageToFriendInternal(friend.id, payload)
            } catch (e: Exception) {
                lastError = e
            }
        }

        lastError?.let { throw it }
    }

    /**
     * Encrypt and send a location update to a single specific friend.
     */
    suspend fun sendLocationToFriend(
        friendId: String,
        lat: Double,
        lng: Double,
    ) {
        val ts = currentTimeSeconds()
        val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
        sendMessageToFriendInternal(friendId, payload)
    }

    /**
     * Send a keepalive message to a friend.
     */
    suspend fun sendKeepalive(friendId: String) {
        sendMessageToFriendInternal(friendId, MessagePlaintext.Keepalive)
    }

    private suspend fun sendMessageToFriendInternal(
        friendId: String,
        payload: MessagePlaintext,
    ) {
        val friend = store.getFriend(friendId) ?: return
        val (newSession, message) = Session.encryptMessage(friend.session, payload)

        // sendMessageToFriendInternal always posts to the current sendToken.
        // Session.encryptMessage handles switching to the new token AFTER the first message of a new epoch.
        val tokenToUse = if (newSession.isSendTokenPending) newSession.prevSendToken else newSession.sendToken
        
        // Post first, update store only on success to ensure reliability (§5.4).
        E2eeMailboxClient.post(baseUrl, tokenToUse.toHex(), message)

        // Mark the token as no longer pending once successfully posted.
        val finalSession = if (newSession.isSendTokenPending) newSession.copy(isSendTokenPending = false) else newSession
        store.updateSession(friendId, finalSession)
    }
}
