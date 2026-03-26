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
 */
data class FriendEntry(
    val name: String,
    val ikPub: ByteArray,
    val sigIkPub: ByteArray,
    val session: SessionState,
) {
    /** Computed friend ID: hex(SHA-256(ikPub || sigIkPub))[0:20] */
    val id: String get() = fingerprint(ikPub, sigIkPub).toHex().substring(0, 20)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FriendEntry) return false
        return name == other.name &&
            ikPub.contentEquals(other.ikPub) &&
            sigIkPub.contentEquals(other.sigIkPub) &&
            session == other.session
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + sigIkPub.contentHashCode()
        result = 31 * result + session.hashCode()
        return result
    }
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
                    val entry = FriendEntry(s.name, s.ikPub, s.sigIkPub, s.session)
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
                    friends.values.map {
                        SerializedFriendEntry(it.id, it.name, it.ikPub, it.sigIkPub, it.session)
                    },
                pendingInvite = null,
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

        val entry = FriendEntry(qr.suggestedName, qr.ikPub, qr.sigPub, session)
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
     * @return The new [FriendEntry], or null if verification fails or no invite is active.
     */
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
                    msg,
                    myIdentity,
                    pending.aliceEkPriv,
                    aliceFp,
                    bobFp,
                )
            val entry = FriendEntry(bobName, msg.ikPub, msg.sigPub, session)
            friends[entry.id] = entry
            pendingInvite = null
            save()
            entry
        } catch (_: Exception) {
            null
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

    companion object {
        private const val STORAGE_KEY = "e2ee_store"
    }
}

@Serializable
internal data class SerializedFriendEntry(
    val friendId: String,
    val name: String,
    @Serializable(with = ByteArrayBase64Serializer::class) val ikPub: ByteArray,
    @Serializable(with = ByteArrayBase64Serializer::class) val sigIkPub: ByteArray,
    val session: SessionState,
)

@Serializable
internal data class SerializedStore(
    val friends: List<SerializedFriendEntry>,
    val pendingInvite: SerializedPendingInvite? = null,
)

@Serializable
internal data class SerializedPendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
)
