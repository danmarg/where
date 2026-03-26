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
 * Friend entry containing their identity keys and the active session state.
 *
 * @param isInitiator  true if the local user was "Alice" (created the QR); false if "Bob" (scanned QR).
 * @param myOpkPrivs   Bob's OPK private keys keyed by OPK ID — kept to process Alice's epoch rotations.
 * @param theirOpkPubs Alice's cache of Bob's OPK public keys keyed by OPK ID — consumed on rotation.
 * @param nextOpkId    Next OPK ID to use when Bob generates a new batch (Bob-only).
 */
data class FriendEntry(
    val name: String,
    val ikPub: ByteArray,
    val sigIkPub: ByteArray,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: Map<Int, ByteArray> = emptyMap(),
    val theirOpkPubs: Map<Int, ByteArray> = emptyMap(),
    val nextOpkId: Int = 1,
) {
    /** Computed friend ID: hex(SHA-256(ikPub || sigIkPub)) — full 64 hex chars. */
    val id: String get() = fingerprint(ikPub, sigIkPub).toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FriendEntry) return false
        return name == other.name &&
            ikPub.contentEquals(other.ikPub) &&
            sigIkPub.contentEquals(other.sigIkPub) &&
            session == other.session &&
            isInitiator == other.isInitiator &&
            myOpkPrivs.contentEquals(other.myOpkPrivs) &&
            theirOpkPubs.contentEquals(other.theirOpkPubs) &&
            nextOpkId == other.nextOpkId
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + sigIkPub.contentHashCode()
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
 * Alice's pending invite state. Not persisted.
 */
internal data class PendingInvite(
    val qrPayload: QrPayload,
    val aliceEkPriv: ByteArray,
)

class E2eeStore(
    private val storage: E2eeStorage,
    private val myIdentity: IdentityKeys,
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
                            ikPub = s.ikPub,
                            sigIkPub = s.sigIkPub,
                            session = s.session,
                            isInitiator = s.isInitiator,
                            myOpkPrivs = s.myOpkPrivs.associate { it.id to it.key },
                            theirOpkPubs = s.theirOpkPubs.associate { it.id to it.key },
                            nextOpkId = s.nextOpkId,
                        )
                    entry.id to entry
                }.toMutableMap()
        } catch (_: Exception) {
            // Error loading state, possibly corrupted; reset
            friends = mutableMapOf()
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
                            ikPub = f.ikPub,
                            sigIkPub = f.sigIkPub,
                            session = f.session,
                            isInitiator = f.isInitiator,
                            myOpkPrivs = f.myOpkPrivs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            theirOpkPubs = f.theirOpkPubs.map { (id, key) -> SerializedOpkEntry(id, key) },
                            nextOpkId = f.nextOpkId,
                        )
                    },
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
        val (payload, ekPriv) = KeyExchange.aliceCreateQrPayload(myIdentity, suggestedName)
        pendingInvite = PendingInvite(payload, ekPriv)
        return payload
    }

    /** Alice: Discard the current pending invite (e.g. user dismissed the QR screen). */
    fun clearInvite() {
        pendingInvite = null
    }

    /**
     * Bob: Process Alice's scanned QR code.
     * Performs the 3-term DH, creates the initial session, and saves the new friend.
     * Returns the wire payload ready to POST to [QrPayload.discoveryToken] and the new entry.
     */
    fun processScannedQr(qr: QrPayload): Pair<KeyExchangeInitPayload, FriendEntry> {
        val aliceFp = fingerprint(qr.ikPub, qr.sigPub)
        val bobFp = fingerprint(myIdentity.ik.pub, myIdentity.sigIk.pub)

        val (initMsg, session) = KeyExchange.bobProcessQr(qr, myIdentity, aliceFp, bobFp)

        val entry = FriendEntry(
            name = qr.suggestedName,
            ikPub = qr.ikPub,
            sigIkPub = qr.sigPub,
            session = session,
            isInitiator = false, // Bob scanned Alice's QR
        )
        friends[entry.id] = entry
        save()
        val payload =
            KeyExchangeInitPayload(
                token = initMsg.token.toHex(),
                ikPub = initMsg.ikPub,
                ekPub = initMsg.ekPub,
                sigPub = initMsg.sigPub,
                sig = initMsg.sig,
            )
        return payload to entry
    }

    /**
     * Alice: Process Bob's KeyExchangeInit payload received from the discovery inbox.
     * Verifies the signature, recomputes the session, and saves the new friend.
     *
     * @param payload Wire payload received from GET /inbox/{discoveryToken}.
     * @param bobName The name Alice wants to call this friend.
     * @return The new [FriendEntry], or null if no invite is active or the payload is
     *         malformed in a non-security-relevant way.
     * @throws IllegalArgumentException if Bob's signature fails verification — this is a
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
                ikPub = payload.ikPub,
                ekPub = payload.ekPub,
                sigPub = payload.sigPub,
                sig = payload.sig,
            )
        val aliceFp = fingerprint(myIdentity.ik.pub, myIdentity.sigIk.pub)
        val bobFp = fingerprint(msg.ikPub, msg.sigPub)

        return try {
            val session =
                KeyExchange.aliceProcessInit(
                    msg = msg,
                    aliceIdentity = myIdentity,
                    aliceEkPriv = pending.aliceEkPriv,
                    aliceEkPub = pending.qrPayload.ekPub,
                    aliceFp = aliceFp,
                    bobFp = bobFp,
                )
            val entry = FriendEntry(
                name = bobName,
                ikPub = msg.ikPub,
                sigIkPub = msg.sigPub,
                session = session,
                isInitiator = true, // Alice created the QR
            )
            friends[entry.id] = entry
            pendingInvite = null
            save()
            entry
        } catch (e: IllegalArgumentException) {
            throw e // signature verification failure — surface to caller
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
     * a signed [PreKeyBundlePayload] ready to POST to the routing token.
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
        val sig = PreKeyBundleOps.buildSignature(
            token = entry.session.routingToken,
            opks = opkList,
            sigIkPriv = myIdentity.sigIk.priv,
        )

        val newPrivMap = entry.myOpkPrivs + newOpks.associate { (opk, priv) -> opk.id to priv }
        friends[friendId] = entry.copy(
            myOpkPrivs = newPrivMap,
            nextOpkId = startId + count,
        )
        save()

        return PreKeyBundlePayload(
            keys = opkList.map { OPKWire(it.id, it.pub) },
            sig = sig,
        )
    }

    /**
     * Alice: Verify and cache an incoming [PreKeyBundlePayload] from Bob.
     * Returns true if the signature was valid and the keys were stored.
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
                sig = bundle.sig,
                sigIkPub = entry.sigIkPub,
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

        val aliceFp = fingerprint(myIdentity.ik.pub, myIdentity.sigIk.pub)
        val bobFp = fingerprint(entry.ikPub, entry.sigIkPub)

        val newEk = generateX25519KeyPair()
        val newSession = Session.aliceEpochRotation(
            state = entry.session,
            aliceNewEkPriv = newEk.priv,
            aliceNewEkPub = newEk.pub,
            bobOpkPub = opkPub,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )

        val blob = Session.epochRotationSignedBlob(
            epoch = newSession.epoch,
            opkId = opkId,
            newEkPub = newEk.pub,
            ts = ts,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )
        val sig = ed25519Sign(myIdentity.sigIk.priv, blob)

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
            sig = sig,
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
     * cryptographic failures (bad signature, stale timestamp).
     */
    @Throws(IllegalArgumentException::class)
    fun processEpochRotation(
        friendId: String,
        payload: EpochRotationPayload,
    ): RatchetAckPayload? {
        val entry = friends[friendId] ?: return null
        if (entry.isInitiator) return null // Alice doesn't process her own rotations

        val aliceFp = fingerprint(entry.ikPub, entry.sigIkPub) // Alice is the friend
        val bobFp = fingerprint(myIdentity.ik.pub, myIdentity.sigIk.pub)

        val blob = Session.epochRotationSignedBlob(
            epoch = payload.epoch,
            opkId = payload.opkId,
            newEkPub = payload.newEkPub,
            ts = payload.ts,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )
        require(ed25519Verify(entry.sigIkPub, blob, payload.sig)) {
            "EpochRotation signature verification failed"
        }
        require(isTimestampFresh(payload.ts)) {
            "EpochRotation timestamp outside freshness window"
        }

        val opkPriv = entry.myOpkPrivs[payload.opkId] ?: return null // unknown OPK — skip

        val newSession = Session.bobProcessEpochRotation(
            state = entry.session,
            aliceNewEkPub = payload.newEkPub,
            bobOpkPriv = opkPriv,
            newEpoch = payload.epoch,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )

        friends[friendId] = entry.copy(
            session = newSession,
            myOpkPrivs = entry.myOpkPrivs - payload.opkId, // delete consumed OPK
        )
        save()

        val ts = currentTimeSeconds()
        val ackBlob = Session.ratchetAckSignedBlob(
            epochSeen = payload.epoch,
            ts = ts,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )
        val ackSig = ed25519Sign(myIdentity.sigIk.priv, ackBlob)
        return RatchetAckPayload(epochSeen = payload.epoch, ts = ts, sig = ackSig)
    }

    /**
     * Alice: Verify Bob's [RatchetAckPayload].
     * Returns true if the signature and timestamp are valid; false otherwise.
     * No state change is made — this is informational only.
     */
    fun processRatchetAck(
        friendId: String,
        payload: RatchetAckPayload,
    ): Boolean {
        val entry = friends[friendId] ?: return false
        if (!entry.isInitiator) return false

        val aliceFp = fingerprint(myIdentity.ik.pub, myIdentity.sigIk.pub)
        val bobFp = fingerprint(entry.ikPub, entry.sigIkPub)

        if (!isTimestampFresh(payload.ts)) return false

        val blob = Session.ratchetAckSignedBlob(
            epochSeen = payload.epochSeen,
            ts = payload.ts,
            senderFp = aliceFp,
            recipientFp = bobFp,
        )
        return ed25519Verify(entry.sigIkPub, blob, payload.sig)
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
    @Serializable(with = ByteArrayBase64Serializer::class) val ikPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val sigIkPub: ByteArray,
    val session: SessionState,
    val isInitiator: Boolean = false,
    val myOpkPrivs: List<SerializedOpkEntry> = emptyList(),
    val theirOpkPubs: List<SerializedOpkEntry> = emptyList(),
    val nextOpkId: Int = 1,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    // pendingInvite is intentionally not persisted: single-session design (see PendingInvite).
)
