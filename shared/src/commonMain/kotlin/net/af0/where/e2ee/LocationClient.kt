package net.af0.where.e2ee

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.af0.where.model.UserLocation

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, and sending for all platforms.
 *
 * Per-message token rotation (§8.3): each message embeds the next routing token in its
 * ciphertext, so the recipient always polls exactly one token at a time — no overlap window.
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
     * Poll all friends for new location updates.
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

            // If we have no friends, do a "health check" poll against a dummy token
            // to ensure connectivity and surface network errors even when the user is alone.
            // We only do this in the foreground to save on battery/server costs.
            if (friends.isEmpty() && isForeground) {
                try {
                    E2eeMailboxClient.poll(baseUrl, "00000000000000000000000000000000")
                } catch (e: ServerException) {
                    // 404 is expected for a dummy token, means we reached the server.
                    if (e.statusCode != 404) lastError = e
                } catch (e: Exception) {
                    lastError = e
                }
            }

            // 1. Poll each friend's current mailbox
            for (friend in friends) {
                try {
                    allUpdates.addAll(pollFriend(friend.id))
                } catch (e: Exception) {
                    lastError = e
                }
            }

            lastError?.let { throw it }
            allUpdates
        }

    /**
     * Poll a specific friend's mailbox, following per-message token rotation.
     *
     * Since each message advances the recvToken, we loop: after a successful
     * batch, the session's recvToken points to the next mailbox, which may
     * already have messages waiting (e.g. if the sender was active while we
     * were offline).
     */
    private suspend fun pollFriend(friendId: String): List<UserLocation> {
        val updates = mutableListOf<UserLocation>()

        while (true) {
            val friend = store.getFriend(friendId) ?: break
            val currentToken = friend.session.recvToken.toHex()
            val messages = E2eeMailboxClient.poll(baseUrl, currentToken)
            println("[LocationClient] pollFriend($friendId): got ${messages.size} messages on token $currentToken")
            if (messages.isEmpty()) break

            val result = store.processBatch(friendId, messages) ?: break
            if (result.decryptedLocations.isEmpty()) break

            updates.addAll(
                result.decryptedLocations.map { loc ->
                    UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                },
            )
            // Token advanced — loop to drain any messages already waiting at the new token.
        }
        return updates
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
        val plaintext = LocationPlaintext(lat = lat, lng = lng, acc = 0.0, ts = ts)
        var lastError: Exception? = null

        for (friend in store.listFriends()) {
            if (friend.id in pausedFriendIds) continue
            try {
                sendLocationToFriendInternal(friend.id, plaintext)
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
        val plaintext = LocationPlaintext(lat = lat, lng = lng, acc = 0.0, ts = ts)
        sendLocationToFriendInternal(friendId, plaintext)
    }

    private suspend fun sendLocationToFriendInternal(
        friendId: String,
        plaintext: LocationPlaintext,
    ) {
        val friend = store.getFriend(friendId) ?: return

        // Capture the current send token BEFORE encryption — encryptLocation advances
        // sendToken to the next value (embedded in the ciphertext for the recipient).
        val currentSendToken = friend.session.sendToken.toHex()

        val (newSession, ct) =
            Session.encryptLocation(
                state = friend.session,
                location = plaintext,
                senderFp = friend.session.aliceFp,
                recipientFp = friend.session.bobFp,
            )
        store.updateSession(friendId, newSession)

        val payload =
            EncryptedLocationPayload(
                seq = newSession.sendSeq.toString(),
                ct = ct,
            )
        E2eeMailboxClient.post(baseUrl, currentSendToken, payload)
    }
}
