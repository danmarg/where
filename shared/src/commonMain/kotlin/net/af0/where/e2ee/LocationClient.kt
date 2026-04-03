package net.af0.where.e2ee

import net.af0.where.model.UserLocation

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, epoch rotation, and sending for all platforms.
 *
 * @param baseUrl Server base URL (e.g. "http://localhost:8080").
 * @param store   Persistent E2EE state storage.
 */
class LocationClient(
    private val baseUrl: String,
    private val store: E2eeStore,
) {
    /**
     * Poll all friends and the pending invite (if any).
     *
     * Processes all incoming control messages (OPKs, Ratchets, Acks) and
     * automatically posts required responses back to the server.
     *
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(): List<UserLocation> {
        val allUpdates = mutableListOf<UserLocation>()
        var lastError: Exception? = null

        // 1. Poll for pending invite responses (if we are Alice/Initiator)
        store.pendingQrPayload?.let { qr ->
            try {
                val discoveryHex = qr.discoveryToken().toHex()
                val messages = E2eeMailboxClient.poll(baseUrl, discoveryHex)
                // If there's a KeyExchangeInit, we don't process it here because the UI
                // typically needs to prompt for a name. The caller can check
                // store.pendingQrPayload and then call E2eeMailboxClient.poll themselves
                // if they want to handle the naming flow.
            } catch (e: Exception) {
                lastError = e
            }
        }

        // 2. Poll each friend's current mailbox
        for (friend in store.listFriends()) {
            try {
                val friendUpdates = pollFriend(friend.id)
                allUpdates.addAll(friendUpdates)

                // 3. Proactively replenish OPKs if the local user (Bob) is running low.
                //    Bob periodically generates fresh OPKs and publishes them so Alice can
                //    rotate epochs without needing Bob to respond to her rotation request first.
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
        return allUpdates
    }

    /**
     * Poll a specific friend's mailbox, handling all protocol messages and
     * potentially recursive polls if an epoch rotation occurs.
     */
    private suspend fun pollFriend(friendId: String): List<UserLocation> {
        val updates = mutableListOf<UserLocation>()
        var currentFriend = store.getFriend(friendId) ?: return emptyList()
        var tokenToPoll = currentFriend.session.recvToken.toHex()

        while (true) {
            val messages = E2eeMailboxClient.poll(baseUrl, tokenToPoll)
            println("[LocationClient] pollFriend($friendId): got ${messages.size} messages, token=$tokenToPoll")
            if (messages.isEmpty()) break

            val result = store.processBatch(friendId, messages)
            if (result == null) {
                println("[LocationClient] pollFriend($friendId): processBatch returned null")
                break
            }
            println("[LocationClient] pollFriend($friendId): processBatch returned ${result.decryptedLocations.size} locations")
            updates.addAll(result.decryptedLocations.map { loc ->
                UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
            })

            // Post any required protocol responses (RatchetAcks, OPK bundles)
            for (out in result.outgoing) {
                E2eeMailboxClient.post(baseUrl, out.token, out.payload)
            }

            // If an epoch rotation happened, we MUST poll the new token immediately
            // to ensure messages Alice posted to the new epoch aren't delayed.
            tokenToPoll = result.newToken ?: break
        }
        return updates
    }

    /**
     * Encrypt and send a location update to all active (non-paused) friends.
     * Handles automatic epoch rotation if Alice reaches a rotation boundary.
     */
    suspend fun sendLocation(
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
                // 1. Rotate epoch if due (Alice-only)
                if (store.shouldRotateEpoch(friend.id)) {
                    val oldToken = friend.session.sendToken.toHex()
                    store.initiateEpochRotation(friend.id)?.let { rot ->
                        E2eeMailboxClient.post(baseUrl, oldToken, rot)
                    }
                }

                // 2. Encrypt and post
                val current = store.getFriend(friend.id) ?: continue
                val (newSession, ct) = Session.encryptLocation(
                    state = current.session,
                    location = plaintext,
                    senderFp = current.session.aliceFp,
                    recipientFp = current.session.bobFp
                )
                store.updateSession(friend.id, newSession)

                val payload = EncryptedLocationPayload(
                    epoch = newSession.epoch,
                    seq = newSession.sendSeq.toString(),
                    ct = ct
                )
                E2eeMailboxClient.post(baseUrl, current.session.sendToken.toHex(), payload)
            } catch (e: Exception) {
                lastError = e
            }
        }
        
        lastError?.let { throw it }
    }

    /**
     * Explicitly post a new OPK bundle for a friend.
     *
     * Called by Bob to replenish his OPK supply so Alice can rotate epochs without
     * waiting for a RatchetAck response. Can be triggered manually (e.g., via UI)
     * or automatically by [poll] when running low.
     */
    suspend fun postOpkBundle(friendId: String) {
        if (store.shouldReplenishOpks(friendId)) {
            store.generateOpkBundle(friendId)?.let { bundle ->
                val friend = store.getFriend(friendId) ?: return
                E2eeMailboxClient.post(baseUrl, friend.session.sendToken.toHex(), bundle)
            }
        }
    }
}
