package net.af0.where.e2ee

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PendingInvite(
    val qrPayload: QrPayload,
    @Serializable(with = ByteArrayBase64Serializer::class) val aliceEkPriv: ByteArray,
    val createdAt: Long = currentTimeSeconds(),
    val exportedAt: Long? = null,
)

internal sealed class PersistenceAction {
    data class Update(val entry: FriendEntry) : PersistenceAction()
    object Delete : PersistenceAction()
    object None : PersistenceAction()
}

/**
 * Handles all persistent storage and concurrency control for the E2EE module.
 * Backed entirely by SQLite.
 */
internal class E2eeStore(
    private val database: net.af0.where.db.WhereDatabase,
) {
    internal companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private const val MAX_DIAGNOSTIC_EVENTS = 100
    }

    // In-memory cache for fast access and reactivity
    private val friends = mutableMapOf<String, FriendEntry>()
    private val pendingInvites = mutableListOf<PendingInvite>()
    private val _diagnosticLog = MutableStateFlow<List<String>>(emptyList())
    val diagnosticLog: StateFlow<List<String>> = _diagnosticLog.asStateFlow()

    private var lastUsedTs: Long = 0L

    // Single lock for all store operations.
    private val storeLock = Mutex()

    init {
        loadFromDb()
    }

    private fun loadFromDb() {
        database.outboxQueries.getAllFriends().executeAsList().forEach { f ->
            val entry = f.toEntry()
            friends[f.id] = entry
            lastUsedTs = maxOf(lastUsedTs, f.lastTs ?: 0L, f.lastRecvTs, f.lastSentTs, f.lastPollTs)
        }
        database.outboxQueries.getAllPendingInvites().executeAsList().forEach { p ->
            pendingInvites.add(p.toInvite())
        }
    }

    private fun nextTs(): Long {
        val now = currentTimeSeconds()
        lastUsedTs = if (now <= lastUsedTs) lastUsedTs + 1 else now
        return lastUsedTs
    }

    suspend fun <T> withMetadataLock(block: suspend MetadataScope.() -> T): T {
        return storeLock.withLock {
            val scope = MetadataScopeImpl()
            val result = block(scope)
            if (scope.dirty) {
                // Persistent metadata (invites) updated via scope methods which call DB
            }
            result
        }
    }

    suspend fun <T> withFriendAndMetadataLock(
        friendId: String,
        block: suspend (FriendEntry?, MetadataScope) -> Pair<PersistenceAction, T>
    ): T {
        return storeLock.withLock {
            val entry = friends[friendId]
            val scope = MetadataScopeImpl()
            val (action, result) = block(entry, scope)
            
            when (action) {
                is PersistenceAction.Update -> {
                    saveFriendInternal(friendId, action.entry)
                }
                is PersistenceAction.Delete -> {
                    deleteFriendInternal(friendId)
                }
                is PersistenceAction.None -> {}
            }
            result
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = storeLock.withLock { friends[id] }
    suspend fun listFriends(): List<FriendEntry> = storeLock.withLock { friends.values.toList() }

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val s = (t % 86400).toInt()
        val entry = "${(s / 3600).toString().padStart(2, '0')}:${((s % 3600) / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')} $message"
        _diagnosticLog.value = (listOf(entry) + _diagnosticLog.value).take(MAX_DIAGNOSTIC_EVENTS)
    }

    private fun saveFriendInternal(friendId: String, entry: FriendEntry) {
        database.outboxQueries.insertFriend(
            id = friendId,
            name = entry.name,
            sessionBlob = json.encodeToString(SessionState.serializer(), entry.session).encodeToByteArray(),
            isInitiator = if (entry.isInitiator) 1L else 0L,
            lastLat = entry.lastLat,
            lastLng = entry.lastLng,
            lastTs = entry.lastTs,
            lastRecvTs = entry.lastRecvTs,
            isConfirmed = if (entry.isConfirmed) 1L else 0L,
            lastSentTs = entry.lastSentTs,
            lastPollTs = entry.lastPollTs,
            sharingEnabled = if (entry.sharingEnabled) 1L else 0L,
            lastDecryptFailed = if (entry.lastDecryptFailed) 1L else 0L
        )
        friends[friendId] = entry
    }

    private fun deleteFriendInternal(friendId: String) {
        database.outboxQueries.deleteFriend(friendId)
        database.outboxQueries.deleteOutboxByFriendId(friendId)
        friends.remove(friendId)
    }

    private fun net.af0.where.db.Friends.toEntry() = FriendEntry(
        name = name,
        session = json.decodeFromString(SessionState.serializer(), sessionBlob.decodeToString()),
        isInitiator = isInitiator == 1L,
        lastLat = lastLat,
        lastLng = lastLng,
        lastTs = lastTs,
        lastRecvTs = lastRecvTs,
        isConfirmed = isConfirmed == 1L,
        lastSentTs = lastSentTs,
        lastPollTs = lastPollTs,
        sharingEnabled = sharingEnabled == 1L,
        lastDecryptFailed = lastDecryptFailed == 1L,
    )

    private fun net.af0.where.db.PendingInvites.toInvite() = PendingInvite(
        qrPayload = QrPayload(
            suggestedName = suggestedName,
            ekPub = ekPub,
            fingerprint = fingerprint,
            discoverySecret = discoverySecret
        ),
        aliceEkPriv = privKeyBlob,
        createdAt = createdAt,
        exportedAt = exportedAt
    )

    interface MetadataScope {
        val friends: List<FriendEntry>
        var pendingInvites: List<PendingInvite>
        var diagnosticLog: List<String>
    }

    private inner class MetadataScopeImpl : MetadataScope {
        var dirty = false
        override val friends: List<FriendEntry> get() = this@E2eeStore.friends.values.toList()
        
        override var pendingInvites: List<PendingInvite> = this@E2eeStore.pendingInvites
            set(value) {
                // Detect additions/deletions and update DB
                val current = this@E2eeStore.pendingInvites
                value.forEach { invite ->
                    if (current.none { it.qrPayload.ekPub.contentEquals(invite.qrPayload.ekPub) }) {
                        database.outboxQueries.insertPendingInvite(
                            ekPub = invite.qrPayload.ekPub,
                            suggestedName = invite.qrPayload.suggestedName,
                            fingerprint = invite.qrPayload.fingerprint,
                            discoverySecret = invite.qrPayload.discoverySecret,
                            privKeyBlob = invite.aliceEkPriv,
                            createdAt = invite.createdAt,
                            exportedAt = invite.exportedAt
                        )
                    }
                }
                current.forEach { invite ->
                    if (value.none { it.qrPayload.ekPub.contentEquals(invite.qrPayload.ekPub) }) {
                        database.outboxQueries.deletePendingInvite(invite.qrPayload.ekPub)
                    }
                }
                this@E2eeStore.pendingInvites.clear()
                this@E2eeStore.pendingInvites.addAll(value)
                field = value
                dirty = true
            }
            
        override var diagnosticLog: List<String> = this@E2eeStore._diagnosticLog.value
            set(value) {
                this@E2eeStore._diagnosticLog.value = value
                field = value
                dirty = true
            }
    }
}
