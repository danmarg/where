package net.af0.where.e2ee

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.af0.where.model.UserLocation

/**
 * Orchestrates the end-to-end encrypted location sharing protocol.
 * Unifies polling, decryption, and sending for all platforms.
 */
open class LocationClient(
    private val baseUrl: String,
    private val store: E2eeStore,
) {
    private val pollMutex = Mutex()

    /**
     * Poll all friends and the pending invite (if any).
     *
     * Processes all incoming control messages (OPKs) and automatically posts
     * required responses back to the server.
     *
     * @return List of new [UserLocation] updates received since the last poll.
     */
    suspend fun poll(): List<UserLocation> =
        pollMutex.withLock {
            val allUpdates = mutableListOf<UserLocation>()
            var lastError: Exception? = null

            for (friend in store.listFriends()) {
                try {
                    val friendUpdates = pollFriend(friend.id)
                    allUpdates.addAll(friendUpdates)

                    // Proactively replenish OPKs if the local user (Bob) is running low.
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
     * Poll a specific friend's mailbox, handling per-message token rotation.
     */
    private suspend fun pollFriend(friendId: String): List<UserLocation> {
        val updates = mutableListOf<UserLocation>()
        val initialFriend = store.getFriend(friendId) ?: return emptyList()

        var currentToken = initialFriend.session.recvToken.toHex()
        while (true) {
            val messages = E2eeMailboxClient.poll(baseUrl, currentToken)
            if (messages.isEmpty()) break

            val result = store.processBatch(friendId, messages)
            if (result == null) break

            updates.addAll(
                result.decryptedLocations.map { loc ->
                    UserLocation(userId = friendId, lat = loc.lat, lng = loc.lng, timestamp = loc.ts)
                },
            )

            // If we got a message, the token rotated.
            val friend = store.getFriend(friendId) ?: break
            currentToken = friend.session.recvToken.toHex()
            if (currentToken == initialFriend.session.recvToken.toHex() && updates.isEmpty()) {
                // No progress made and token didn't change (e.g. only OPKs)
                break
            }
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
            // Skip friends from whom Alice has not received anything in 7 days.
            if (store.isFriendStale(friend.id)) continue
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

        // 1. Prepare for integrated DH if we have OPKs
        val opk = if (friend.isInitiator && friend.theirOpkPubs.isNotEmpty()) {
            friend.theirOpkPubs.minBy { it.key }
        } else null

        val aliceNewEk = if (opk != null) generateX25519KeyPair() else null
        val nextRecvToken = randomBytes(16)

        // 2. Encrypt and post
        val current = store.getFriend(friend.id) ?: return
        val (newSession, ct) =
            Session.encryptLocation(
                state = current.session,
                location = plaintext,
                senderFp = current.session.aliceFp,
                recipientFp = current.session.bobFp,
                nextRecvToken = nextRecvToken,
                nextOpkId = opk?.key,
                nextBobOpkPub = opk?.value,
                aliceNewEkPriv = aliceNewEk?.priv,
                aliceNewEkPub = aliceNewEk?.pub
            )

        val updatedFriend = current.copy(
            session = newSession,
            theirOpkPubs = if (opk != null) current.theirOpkPubs - opk.key else current.theirOpkPubs
        )
        store.updateFriend(updatedFriend)

        val payload =
            EncryptedLocationPayload(
                seq = newSession.sendSeq.toString(),
                ct = ct,
            )
        E2eeMailboxClient.post(baseUrl, current.session.sendToken.toHex(), payload)
    }

    /**
     * Explicitly post a new OPK bundle for a friend.
     *
     * Called by Bob to replenish his OPK supply so Alice can rotate the DH ratchet.
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
