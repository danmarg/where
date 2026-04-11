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
     * Processes all incoming control messages (OPKs, RatchetAcks, EpochRotations) and
     * automatically posts required responses back to the server.
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
                    val friendUpdates = pollFriend(friend.id)
                    allUpdates.addAll(friendUpdates)

                    // 2. Proactively replenish OPKs if the local user (Bob) is running low.
                    //    Bob periodically generates fresh OPKs and publishes them so Alice can
                    //    rotate whenever she sends without waiting for Bob to respond.
                    if (store.shouldReplenishOpks(friend.id)) {
                        store.generateOpkBundle(friend.id)?.let { bundle ->
                            E2eeMailboxClient.post(baseUrl, friend.session.sendToken.toHex(), bundle)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            lastError?.let { throw it }
            allUpdates
        }

    /**
     * Poll a specific friend's mailbox, handling all protocol messages.
     * Bob always polls exactly one recvToken — no dual-polling window.
     */
    private suspend fun pollFriend(friendId: String): List<UserLocation> {
        val friend = store.getFriend(friendId) ?: return emptyList()

        val messages = E2eeMailboxClient.poll(baseUrl, friend.session.recvToken.toHex())
        if (messages.isEmpty()) return emptyList()

        val result = store.processBatch(friendId, messages) ?: return emptyList()

        // Post any required protocol responses (RatchetAcks, OPK bundles).
        for (out in result.outgoing) {
            E2eeMailboxClient.post(baseUrl, out.token, out.payload)
        }

        return result.decryptedLocations.map { loc ->
            UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
        }
    }

    /**
     * Encrypt and send a location update to all active (non-paused) friends.
     *
     * If Alice has a pending rotation in flight, the [EpochRotationPayload] is resent
     * alongside every location update until Bob acks.
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
        // Initiate a DH ratchet rotation if possible (Alice only, no rotation in flight,
        // and Bob has published OPKs). The new session is held as a PendingRotation and
        // committed only when Bob acks.
        if (store.shouldInitiateRotation(friendId)) {
            store.initiateRotation(friendId)
        }

        val friend = store.getFriend(friendId) ?: return
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
        E2eeMailboxClient.post(baseUrl, friend.session.sendToken.toHex(), payload)

        // If a rotation is pending, include the EpochRotation after every location send
        // until Bob acks. Bob processes them in the same batch (location first, then rotation).
        store.pendingEpochRotation(friendId)?.let { rot ->
            E2eeMailboxClient.post(baseUrl, friend.session.sendToken.toHex(), rot)
        }
    }

    /**
     * Explicitly post a new OPK bundle for a friend.
     *
     * Called by Bob to replenish his OPK supply so Alice can rotate without waiting
     * for the next maintenance poll. Can be triggered manually (e.g., via UI) or
     * automatically by [poll] when running low.
     */
    open suspend fun postOpkBundle(friendId: String) {
        if (store.shouldReplenishOpks(friendId)) {
            store.generateOpkBundle(friendId)?.let { bundle ->
                val friend = store.getFriend(friendId) ?: return
                E2eeMailboxClient.post(baseUrl, friend.session.sendToken.toHex(), bundle)
            }
        }
    }
}
