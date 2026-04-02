package net.af0.where.e2ee

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Storage interface for persistent E2EE state.
 * Actual implementations will wrap SharedPreferences/UserDefaults.
 */
interface E2eeStorage {
    fun getString(key: String): String?

    fun putString(
        key: String,
        value: String,
    )
}

/**
 * Friend entry containing their session state.
 *
 * @param isInitiator  true if the local user was "Alice" (created the QR); false if "Bob" (scanned QR).
 * @param myOpkPrivs   Bob's OPK private keys keyed by OPK ID — kept to process Alice's epoch rotations.
 * @param theirOpkPubs Alice's cache of Bob's OPK public keys keyed by OPK ID — consumed on rotation.
 * @param nextOpkId    Next OPK ID to use when Bob generates a new batch (Bob-only).
 */
data class FriendEntry(
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: Map<Int, ByteArray> = emptyMap(),
    val theirOpkPubs: Map<Int, ByteArray> = emptyMap(),
    val nextOpkId: Int = 1,
) {
    /** Computed friend ID: hex(SHA-256(EK_A.pub)) — full 64 hex chars. */
    val id: String get() = session.aliceFp.toHex()

    /** Safety number (e.g., for display in UI). */
    val safetyNumber: String get() = formatSafetyNumber(safetyNumber(session.myEkPub, session.theirEkPub))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FriendEntry) return false
        return name == other.name &&
            session == other.session &&
            isInitiator == other.isInitiator &&
            myOpkPrivs.contentEquals(other.myOpkPrivs) &&
            theirOpkPubs.contentEquals(other.theirOpkPubs) &&
            nextOpkId == other.nextOpkId
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + session.hashCode()
        result = 31 * result + isInitiator.hashCode()
        result = 31 * result + nextOpkId
        return result
    }
}

private fun Map<Int, ByteArray>.contentEquals(other: Map<Int, ByteArray>): Boolean {
    if (size != other.size) return false
    return all { (k, v) -> other[k]?.contentEquals(v) == true }
}

/**
 * Alice's pending invite state.
 */
@Serializable
internal data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
)

class E2eeStore(
    private val storage: E2eeStorage,
) {
    private var friends = mutableMapOf<String, FriendEntry>()
    private var pendingInvite: PendingInvite? = null

    init {
        load()
    }

    private fun load() {
        val jsonStr = storage.getString(STORAGE_KEY) ?: return
        try {
            val serialized = Json.decodeFromString<SerializedStore>(jsonStr)
            friends =
                serialized.friends.associate { s ->
                    val entry =
                        FriendEntry(
                            name = s.name,
                            session = s.session,
                            isInitiator = s.isInitiator,
                            myOpkPrivs = s.myOpkPrivs.associate { it.id to it.key },
                            theirOpkPubs = s.theirOpkPubs.associate { it.id to it.key },
                            nextOpkId = s.nextOpkId,
                        )
                    entry.id to entry
                }.toMutableMap()
            pendingInvite = serialized.pendingInvite
        } catch (_: Exception) {
            // Error loading state, possibly corrupted; reset
            friends = mutableMapOf()
            pendingInvite = null
        }
    }

    private fun save() {
        val serialized =
            SerializedStore(
                friends =
                    friends.values.map { f ->
                        SerializedFriendEntry(
                            friendId = f.id,
                            name = f.name,
                            session = f.session,
                            isInitiator = f.isInitiator,
                            myOpkPrivs = f.myOpkPrivs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            theirOpkPubs = f.theirOpkPubs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            nextOpkId = f.nextOpkId,
                        )
                    },
                pendingInvite = pendingInvite,
            )
        storage.putString(STORAGE_KEY, Json.encodeToString(serialized))
    }

    /** The QR payload currently being displayed, or null if no invite is active. */
    val pendingQrPayload: QrPayload? get() = pendingInvite?.qrPayload

    /**
     * Alice: Create a new invite QR payload and store the ephemeral private key.
     * Replaces any previously active invite.
     */
    fun createInvite(suggestedName: String): QrPayload {
        val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(suggestedName)
        pendingInvite = PendingInvite(payload, ekPriv)
        save()
        return payload
    }

    /** Alice: Discard the current pending invite (e.g. user dismissed the QR screen). */
    fun clearInvite() {
        pendingInvite = null
        save()
    }

    /**
     * Bob: Process Alice's scanned QR code.
     * Performs the single X25519 DH, creates the initial session, and saves the new friend.
     * Returns the wire payload ready to POST to [QrPayload.discoveryToken] and the new entry.
     */
    fun processScannedQr(qr: QrPayload, bobSuggestedName: String = ""): Pair<KeyExchangeInitPayload, FriendEntry> {
        val (initMsg, session) = KeyExchange.bobProcessQr(qr, bobSuggestedName)

        val entry = FriendEntry(
            name = qr.suggestedName,
            session = session,
            isInitiator = false, // Bob scanned Alice's QR
        )
        friends[entry.id] = entry
        save()
        val payload =
            KeyExchangeInitPayload(
                token = initMsg.token.toHex(),
                ekPub = initMsg.ekPub,
                keyConfirmation = initMsg.keyConfirmation,
                suggestedName = initMsg.suggestedName,
            )
        return payload to entry
    }

    /**
     * Alice: Process Bob's KeyExchangeInit payload received from the discovery inbox.
     * Verifies key_confirmation, recomputes the session, and saves the new friend.
     *
     * @param payload Wire payload received from GET /inbox/{discoveryToken}.
     * @param bobName The name Alice wants to call this friend.
     * @return The new [FriendEntry], or null if no invite is active or the payload is
     *         malformed in a non-security-relevant way.
     * @throws IllegalArgumentException if key_confirmation fails — this is a
     *         crypto failure that callers should surface (not silently discard).
     */
    @Throws(IllegalArgumentException::class)
    fun processKeyExchangeInit(
        payload: KeyExchangeInitPayload,
        bobName: String,
    ): FriendEntry? {
        val pending = pendingInvite ?: return null
        val msg =
            KeyExchangeInitMessage(
                token = payload.token.hexToByteArray(),
                ekPub = payload.ekPub,
                keyConfirmation = payload.keyConfirmation,
                suggestedName = payload.suggestedName,
            )

        return try {
            val session =
                KeyExchange.aliceProcessInit(
                    msg = msg,
                    aliceEkPriv = pending.aliceEkPriv,
                    aliceEkPub = pending.qrPayload.ekPub,
                )
            val entry = FriendEntry(
                name = bobName,
                session = session,
                isInitiator = true, // Alice created the QR
            )
            friends[entry.id] = entry
            pendingInvite = null
            save()
            entry
        } catch (e: IllegalArgumentException) {
            throw e // key_confirmation failure — surface to caller
        } catch (_: Exception) {
            null // transient parse/format error — treat as "not ready yet"
        }
    }

    fun getFriend(id: String): FriendEntry? = friends[id]

    fun listFriends(): List<FriendEntry> = friends.values.toList()

    fun deleteFriend(id: String) {
        if (friends.remove(id) != null) {
            save()
        }
    }

    fun updateSession(
        id: String,
        newSession: SessionState,
    ) {
        val entry = friends[id] ?: return
        friends[id] = entry.copy(session = newSession)
        save()
    }

    // -----------------------------------------------------------------------
    // OPK management
    // -----------------------------------------------------------------------

    /**
     * Bob: Generate [count] fresh one-time pre-keys, store their private keys, and return
     * a MAC-authenticated [PreKeyBundlePayload] ready to POST to the routing token.
     *
     * Returns null if the friend is not found or this device is not Bob (isInitiator = true).
     */
    fun generateOpkBundle(
        friendId: String,
        count: Int = OPK_BATCH_SIZE,
    ): PreKeyBundlePayload? {
        val entry = friends[friendId] ?: return null
        // Only Bob (non-initiator) maintains OPK private keys.
        if (entry.isInitiator) return null

        val startId = entry.nextOpkId
        val newOpks = (0 until count).map { i ->
            val kp = generateX25519KeyPair()
            OPK(id = startId + i, pub = kp.pub) to kp.priv
        }

        val opkList = newOpks.map { (opk, _) -> opk }
        val mac = PreKeyBundleOps.buildMac(
            token = entry.session.routingToken,
            opks = opkList,
            kBundle = entry.session.kBundle,
        )

        val newPrivMap = entry.myOpkPrivs + newOpks.associate { (opk, priv) -> opk.id to priv }
        friends[friendId] = entry.copy(
            myOpkPrivs = newPrivMap,
            nextOpkId = startId + count,
        )
        save()

        return PreKeyBundlePayload(
            keys = opkList.map { OPKWire(it.id, it.pub) },
            mac = mac,
        )
    }

    /**
     * Alice: Verify and cache an incoming [PreKeyBundlePayload] from Bob.
     * Returns true if the MAC was valid and the keys were stored.
     */
    fun storeOpkBundle(
        friendId: String,
        bundle: PreKeyBundlePayload,
    ): Boolean {
        val entry = friends[friendId] ?: return false
        // Only Alice (initiator) caches Bob's OPK public keys.
        if (!entry.isInitiator) return false

        val opks = bundle.toOPKList()
        if (!PreKeyBundleOps.verify(
                token = entry.session.routingToken,
                opks = opks,
                mac = bundle.mac,
                kBundle = entry.session.kBundle,
            )
        ) {
            return false
        }

        val newPubMap = entry.theirOpkPubs + opks.associate { it.id to it.pub }
        friends[friendId] = entry.copy(theirOpkPubs = newPubMap)
        save()
        return true
    }

    /**
     * Returns true if Bob should replenish his OPK supply for this friend.
     * Bob replenishes when his remaining OPKs fall below [OPK_REPLENISH_THRESHOLD].
     */
    fun shouldReplenishOpks(friendId: String): Boolean {
        val entry = friends[friendId] ?: return false
        return !entry.isInitiator && entry.myOpkPrivs.size < OPK_REPLENISH_THRESHOLD
    }

    // -----------------------------------------------------------------------
    // Epoch rotation
    // -----------------------------------------------------------------------

    /**
     * Returns true if Alice should initiate an epoch rotation for this friendship.
     *
     * Alice rotates every [EPOCH_ROTATION_INTERVAL] sends, as long as she has at
     * least one cached OPK from Bob.
     */
    fun shouldRotateEpoch(friendId: String): Boolean {
        val entry = friends[friendId] ?: return false
        return entry.isInitiator &&
            entry.theirOpkPubs.isNotEmpty() &&
            entry.session.sendSeq > 0 &&
            entry.session.sendSeq % EPOCH_ROTATION_INTERVAL == 0L
    }

    /**
     * Alice: Consume one cached OPK, perform a DH epoch rotation, and return the
     * [EpochRotationPayload] that should be posted to the **current** (pre-rotation)
     * routing token so Bob can process it.
     *
     * The session stored in [FriendEntry] is updated to the new epoch immediately.
     * The caller MUST post the returned payload to the OLD routing token BEFORE
     * sending any location messages with the new session.
     *
     * Returns null if the friend is not found, is not the initiator, or has no cached OPKs.
     */
    fun initiateEpochRotation(friendId: String): EpochRotationPayload? {
        val entry = friends[friendId] ?: return null
        if (!entry.isInitiator || entry.theirOpkPubs.isEmpty()) return null

        val ts = currentTimeSeconds()
        val (opkId, opkPub) = entry.theirOpkPubs.minBy { it.key }

        val newEk = generateX25519KeyPair()
        val newSession = Session.aliceEpochRotation(
            state = entry.session,
            aliceNewEkPriv = newEk.priv,
            aliceNewEkPub = newEk.pub,
            bobOpkPub = opkPub,
            senderFp = entry.session.aliceFp,
            recipientFp = entry.session.bobFp,
        )

        val oldRoutingToken = entry.session.routingToken
        val nonce = randomBytes(12)
        val ct = buildEpochRotationCt(
            rootKey = entry.session.rootKey,
            epoch = newSession.epoch,
            opkId = opkId,
            newEkPub = newEk.pub,
            ts = ts,
            nonce = nonce,
            routingToken = oldRoutingToken,
            senderFp = entry.session.aliceFp,
            recipientFp = entry.session.bobFp,
        )

        friends[friendId] = entry.copy(
            session = newSession,
            theirOpkPubs = entry.theirOpkPubs - opkId, // consume the OPK
        )
        save()

        return EpochRotationPayload(
            epoch = newSession.epoch,
            opkId = opkId,
            newEkPub = newEk.pub,
            ts = ts,
            nonce = nonce,
            ct = ct,
        )
    }

    /**
     * Bob: Verify and process an [EpochRotationPayload] from Alice.
     *
     * Updates the session to the new epoch and routing token. The caller should:
     *   1. POST the returned [RatchetAckPayload] to the **new** routing token.
     *   2. Call [generateOpkBundle] and POST the result to the new routing token.
     *
     * Returns null if the friend is not found, the OPK is unknown, or another
     * non-security parse error occurs. Throws [IllegalArgumentException] on
     * cryptographic failures (bad AEAD, stale timestamp).
     */
    @Throws(IllegalArgumentException::class)
    fun processEpochRotation(
        friendId: String,
        payload: EpochRotationPayload,
    ): RatchetAckPayload? {
        val entry = friends[friendId] ?: return null
        if (entry.isInitiator) return null // Alice doesn't process her own rotations

        val oldRoutingToken = entry.session.routingToken
        val rotData = try {
            decryptEpochRotationCt(
                rootKey = entry.session.rootKey,
                epoch = payload.epoch,
                nonce = payload.nonce,
                ct = payload.ct,
                routingToken = oldRoutingToken,
                senderFp = entry.session.aliceFp,
                recipientFp = entry.session.bobFp,
            )
        } catch (_: Exception) {
            throw IllegalArgumentException("EpochRotation AEAD verification failed")
        }
        require(rotData.epoch == payload.epoch) { "epoch mismatch in EpochRotation" }
        require(rotData.opkId == payload.opkId) { "opkId mismatch in EpochRotation" }
        require(isTimestampFresh(rotData.ts)) { "EpochRotation timestamp outside freshness window" }

        val opkPriv = entry.myOpkPrivs[payload.opkId] ?: return null // unknown OPK — skip

        // Step 1: Process Alice's rotation
        val intermediateSession = Session.bobProcessAliceRotation(
            state = entry.session,
            aliceNewEkPub = payload.newEkPub,
            bobOpkPriv = opkPriv,
            newEpoch = payload.epoch,
            senderFp = entry.session.aliceFp,
            recipientFp = entry.session.bobFp,
        )

        // Step 2: Refresh Bob's own send chain
        val bobNewEk = generateX25519KeyPair()
        val finalSession = Session.bobProcessOwnRotation(
            state = intermediateSession,
            bobNewEkPriv = bobNewEk.priv,
            bobNewEkPub = bobNewEk.pub,
        )

        // Authenticate Ack using intermediate root key (the one Alice knows)
        // and include Bob's new key in authenticated plaintext.
        val ts = currentTimeSeconds()
        val nonce = randomBytes(12)
        val ackCt = buildRatchetAckCt(
            rootKey = intermediateSession.rootKey,
            epochSeen = payload.epoch,
            ts = ts,
            newEkPub = bobNewEk.pub,
            nonce = nonce,
            routingToken = intermediateSession.routingToken,
            senderFp = entry.session.aliceFp,
            recipientFp = entry.session.bobFp,
        )

        friends[friendId] = entry.copy(
            session = finalSession,
            myOpkPrivs = entry.myOpkPrivs - payload.opkId, // delete consumed OPK
        )
        save()

        return RatchetAckPayload(epochSeen = payload.epoch, ts = ts, newEkPub = bobNewEk.pub, nonce = nonce, ct = ackCt)
    }

    /**
     * Alice: Verify Bob's [RatchetAckPayload].
     * Returns true if the AEAD and timestamp are valid; false otherwise.
     * Updates Alice's receive chain if Bob provided a new ephemeral key.
     */
    fun processRatchetAck(
        friendId: String,
        payload: RatchetAckPayload,
    ): Boolean {
        val entry = friends[friendId] ?: return false
        if (!entry.isInitiator) return false

        val ackData = try {
            decryptRatchetAckCt(
                rootKey = entry.session.rootKey,
                epochSeen = payload.epochSeen,
                nonce = payload.nonce,
                ct = payload.ct,
                routingToken = entry.session.routingToken,
                senderFp = entry.session.aliceFp,
                recipientFp = entry.session.bobFp,
            )
        } catch (_: Exception) { return false }

        if (!isTimestampFresh(ackData.ts)) return false

        // Perform the second half of the DH ratchet if Bob provided a new key.
        if (ackData.newEkPub != null) {
            val newSession = Session.aliceProcessRatchetAck(entry.session, ackData.newEkPub)
            friends[friendId] = entry.copy(session = newSession)
            save()
        }

        return true
    }

    // -----------------------------------------------------------------------
    // Batch poll processing
    // -----------------------------------------------------------------------

    /**
     * A message the caller should POST back to the server after processing a batch.
     */
    data class OutgoingMessage(val token: String, val payload: MailboxPayload)

    /**
     * Result of [processBatch].
     *
     * @property decryptedLocations Locations decrypted from this batch, in receive order.
     * @property newToken Non-null when an [EpochRotationPayload] was processed. The caller
     *   should immediately poll this token and call [processBatch] again on the result, so
     *   that messages the sender already posted to the new epoch are not delayed by a full
     *   poll interval.
     * @property outgoing Payloads the caller must POST to the server, in order.
     */
    data class PollBatchResult(
        val decryptedLocations: List<LocationPlaintext>,
        val newToken: String?,
        val outgoing: List<OutgoingMessage>,
    )

    /**
     * Process one batch of mailbox messages for [friendId] in the correct order.
     *
     * **Ordering guarantee:** [EncryptedLocationPayload]s on a given token always belong
     * to the current epoch. The function decrypts them *before* applying any
     * [EpochRotationPayload] so that the session state is still on the correct epoch during
     * decryption. Processing the rotation first would advance the chain key and break
     * decryption of messages that arrived in the same batch.
     *
     * Typical client loop:
     * ```
     * var messages = mailboxClient.poll(token)
     * while (true) {
     *     val result = store.processBatch(friendId, messages) ?: break
     *     result.decryptedLocations.forEach { /* update UI */ }
     *     result.outgoing.forEach { mailboxClient.post(it.token, it.payload) }
     *     val next = result.newToken ?: break
     *     messages = mailboxClient.poll(next)
     * }
     * ```
     *
     * @return [PollBatchResult], or null if [friendId] is not found.
     */
    fun processBatch(friendId: String, messages: List<MailboxPayload>): PollBatchResult? {
        friends[friendId] ?: return null

        val decryptedLocations = mutableListOf<LocationPlaintext>()
        val outgoing = mutableListOf<OutgoingMessage>()
        var newToken: String? = null

        // Step 1: Cache incoming OPK bundles (Alice stores Bob's prekeys).
        for (msg in messages.filterIsInstance<PreKeyBundlePayload>()) {
            storeOpkBundle(friendId, msg)
        }

        // Step 2: Decrypt location updates BEFORE processing epoch rotation.
        // All EncryptedLocationPayloads on this token are from the current epoch.
        val preRotationSession = friends[friendId]?.session ?: return PollBatchResult(emptyList(), null, emptyList())
        var currentSession = preRotationSession
        for (msg in messages.filterIsInstance<EncryptedLocationPayload>().sortedBy { it.seqAsLong() }) {
            try {
                val (newSession, loc) = Session.decryptLocation(
                    state = currentSession,
                    ct = msg.ct,
                    seq = msg.seqAsLong(),
                    senderFp = currentSession.aliceFp,
                    recipientFp = currentSession.bobFp,
                )
                currentSession = newSession
                decryptedLocations.add(loc)
            } catch (_: Exception) {
                // drop individually bad messages rather than aborting the whole batch
            }
        }
        if (currentSession !== preRotationSession) {
            updateSession(friendId, currentSession)
        }

        // Step 3: Process epoch rotation (after location decryption).
        for (msg in messages.filterIsInstance<EpochRotationPayload>()) {
            val ack = try {
                processEpochRotation(friendId, msg)
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            val rotatedToken = friends[friendId]?.session?.routingToken?.toHex() ?: continue
            outgoing.add(OutgoingMessage(rotatedToken, ack))
            generateOpkBundle(friendId)?.let { bundle ->
                outgoing.add(OutgoingMessage(rotatedToken, bundle))
            }
            newToken = rotatedToken
        }

        // Step 4: Process RatchetAcks (Alice updating her receive chain).
        for (msg in messages.filterIsInstance<RatchetAckPayload>()) {
            processRatchetAck(friendId, msg)
        }

        return PollBatchResult(decryptedLocations, newToken, outgoing)
    }

    companion object {
        private const val STORAGE_KEY = "e2ee_store"

        /** OPKs generated per batch. */
        const val OPK_BATCH_SIZE = 10

        /**
         * Bob replenishes OPKs when fewer than this many remain.
         * Should be > 0 to allow Alice to rotate while Bob is generating a new batch.
         */
        const val OPK_REPLENISH_THRESHOLD = 3

        /**
         * Alice initiates an epoch rotation every this many location sends.
         * Balances forward-secrecy granularity vs. overhead.
         */
        const val EPOCH_ROTATION_INTERVAL = 50L
    }
}

// -----------------------------------------------------------------------
// Serialization helpers
// -----------------------------------------------------------------------

@Serializable
internal data class SerializedOpkEntry(
    val id: Int,
    @Serializable(with = ByteArrayBase64Serializer::class) val key: ByteArray,
)

@Serializable
internal data class SerializedFriendEntry(
    val friendId: String,
    val name: String,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: List<SerializedOpkEntry> = emptyList(),
    val theirOpkPubs: List<SerializedOpkEntry> = emptyList(),
    val nextOpkId: Int = 1,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: PendingInvite? = null,
)
