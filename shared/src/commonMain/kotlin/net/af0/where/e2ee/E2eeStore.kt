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
        val json =
            Json {
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
        database.friendsQueries.getAllFriends().executeAsList().forEach { f ->
            val entry = f.toEntry()
            friends[f.id] = entry
            lastUsedTs = maxOf(lastUsedTs, f.lastTs ?: 0L, f.lastRecvTs, f.lastSentTs, f.lastPollTs)
        }
        database.invitesQueries.getAllPendingInvites().executeAsList().forEach { p ->
            pendingInvites.add(p.toInvite())
        }
    }

    private fun reloadFromDb() {
        friends.clear()
        pendingInvites.clear()
        loadFromDb()
    }

    private fun nextTs(): Long {
        val now = currentTimeSeconds()
        lastUsedTs = if (now <= lastUsedTs) lastUsedTs + 1 else now
        return lastUsedTs
    }

    suspend fun <T> withMetadataLock(block: suspend MetadataScope.() -> T): T {
        return storeLock.withLock {
            val scope = MetadataScopeImpl()
            val result: T
            try {
                result = block(scope)
                database.transaction {
                    scope.applyToDb()
                }
                scope.applyToMemory()
            } catch (e: Exception) {
                reloadFromDb()
                throw e
            }
            result
        }
    }

    suspend fun <T> withFriendAndMetadataLock(
        friendId: String,
        block: suspend (FriendEntry?, MetadataScope) -> Pair<PersistenceAction, T>,
    ): T {
        return storeLock.withLock {
            val entry = friends[friendId]
            val scope = MetadataScopeImpl()
            val result: T

            try {
                val (action, blockResult) = block(entry, scope)
                result = blockResult
                
                var newEntry: FriendEntry? = null
                var deleted = false

                database.transaction {
                    when (action) {
                        is PersistenceAction.Update -> {
                            newEntry = saveFriendInternal(friendId, action.entry)
                        }
                        is PersistenceAction.Delete -> {
                            deleteFriendInternal(friendId)
                            deleted = true
                        }
                        is PersistenceAction.None -> {}
                    }

                    // Process queued outbox inserts
                    scope.getQueuedOutboxInserts().forEach { outbox ->
                        database.outboxQueries.insertOutbox(
                            msgId = outbox.msgId,
                            friendId = outbox.friendId,
                            token = outbox.token,
                            payloadBlob = outbox.payloadBlob,
                            createdAt = outbox.createdAt,
                        )
                    }
                    scope.applyToDb()
                }
                
                if (newEntry != null) {
                    friends[friendId] = newEntry!!
                } else if (deleted) {
                    friends.remove(friendId)
                }
                scope.applyToMemory()

            } catch (e: Exception) {
                reloadFromDb()
                throw e
            }
            result
        }
    }

    suspend fun getFriend(id: String): FriendEntry? = storeLock.withLock { friends[id] }

    suspend fun listFriends(): List<FriendEntry> = storeLock.withLock { friends.values.toList() }

    fun addDiagnosticEvent(message: String) {
        val t = currentTimeSeconds()
        val entry = "${TimeSource.formatLocalTime(t)} $message"
        _diagnosticLog.value = (listOf(entry) + _diagnosticLog.value).take(MAX_DIAGNOSTIC_EVENTS)
    }

    private fun saveFriendInternal(
        friendId: String,
        entry: FriendEntry,
    ): FriendEntry {
        val current = friends[friendId]
        if (current != null) {
            // Optimistic concurrency check: ensure we haven't modified the state
            // since it was read from the cache. This guards against race conditions
            // if someone uses an old FriendEntry snapshot to attempt an update.
            if (entry.version != current.version) {
                throw IllegalStateException(
                    "STALE UPDATE: friendId=$friendId version mismatch! current=${current.version}, updating=${entry.version}",
                )
            }

            // Double Ratchet safety: ensure receiving sequence doesn't regress (§5.5)
            // Only applicable within the same DH epoch (same remote DH key).
            if (entry.session.remoteDhPub.contentEquals(current.session.remoteDhPub) &&
                entry.session.recvSeq < current.session.recvSeq
            ) {
                throw IllegalStateException(
                    "CRITICAL: recvSeq regression! friendId=$friendId, current=${current.session.recvSeq}, new=${entry.session.recvSeq}",
                )
            }
        }

        val nextVersion = entry.version + 1
        val finalEntry = entry.copy(version = nextVersion)

        database.friendsQueries.insertFriend(
            id = friendId,
            name = finalEntry.name,
            sessionBlob = json.encodeToString(SessionState.serializer(), finalEntry.session).encodeToByteArray(),
            isInitiator = if (finalEntry.isInitiator) 1L else 0L,
            lastLat = finalEntry.lastLat,
            lastLng = finalEntry.lastLng,
            lastTs = finalEntry.lastTs,
            lastRecvTs = finalEntry.lastRecvTs,
            isConfirmed = if (finalEntry.isConfirmed) 1L else 0L,
            lastSentTs = finalEntry.lastSentTs,
            lastPollTs = finalEntry.lastPollTs,
            sharingEnabled = if (finalEntry.sharingEnabled) 1L else 0L,
            lastDecryptFailed = if (finalEntry.lastDecryptFailed) 1L else 0L,
            version = nextVersion.toLong(),
        )
        return finalEntry
    }

    private fun deleteFriendInternal(friendId: String) {
        database.friendsQueries.deleteFriend(friendId)
        database.outboxQueries.deleteOutboxByFriendId(friendId)
    }

    suspend fun insertOutbox(
        msgId: String,
        friendId: String,
        token: String,
        payloadBlob: ByteArray,
        createdAt: Long,
    ) = storeLock.withLock {
        insertOutboxInternal(msgId, friendId, token, payloadBlob, createdAt)
    }

    internal fun insertOutboxInternal(
        msgId: String,
        friendId: String,
        token: String,
        payloadBlob: ByteArray,
        createdAt: Long,
    ) {
        database.outboxQueries.insertOutbox(msgId, friendId, token, payloadBlob, createdAt)
    }

    suspend fun deleteOutboxByMsgId(
        friendId: String,
        msgId: String,
    ) = storeLock.withLock {
        deleteOutboxByMsgIdInternal(friendId, msgId)
    }

    internal fun deleteOutboxByMsgIdInternal(
        friendId: String,
        msgId: String,
    ) {
        database.outboxQueries.deleteOutboxByMsgIdAndFriendId(msgId, friendId)
    }

    suspend fun deleteOutboxByFriendId(friendId: String) =
        storeLock.withLock {
            deleteOutboxByFriendIdInternal(friendId)
        }

    internal fun deleteOutboxByFriendIdInternal(friendId: String) {
        database.outboxQueries.deleteOutboxByFriendId(friendId)
    }

    internal fun deleteOutboxByFriendIdAndTokenInternal(friendId: String, token: String) {
        database.outboxQueries.deleteOutboxByFriendIdAndToken(friendId, token)
    }

    suspend fun getOutbox(friendId: String): List<EncryptedOutboxMessage> =
        storeLock.withLock {
            getOutboxInternal(friendId)
        }

    internal fun getOutboxInternal(friendId: String): List<EncryptedOutboxMessage> {
        return database.outboxQueries.getOutboxForFriend(friendId).executeAsList().map { row ->
            EncryptedOutboxMessage(
                msgId = row.msgId,
                token = row.token,
                payload = json.decodeFromString(MailboxPayload.serializer(), row.payloadBlob.decodeToString()),
                createdAt = row.createdAt,
            )
        }
    }

    private fun net.af0.where.db.Friends.toEntry() =
        FriendEntry(
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
            version = version.toInt(),
        )

    private fun net.af0.where.db.PendingInvites.toInvite() =
        PendingInvite(
            qrPayload =
                QrPayload(
                    suggestedName = suggestedName,
                    ekPub = ekPub,
                    fingerprint = fingerprint,
                    discoverySecret = discoverySecret,
                ),
            aliceEkPriv = privKeyBlob,
            createdAt = createdAt,
            exportedAt = exportedAt,
        )

    interface MetadataScope {
        val friends: List<FriendEntry>
        var pendingInvites: List<PendingInvite>
        var diagnosticLog: List<String>

        fun addDiagnosticEvent(message: String) {
            val t = currentTimeSeconds()
            val entry = "${TimeSource.formatLocalTime(t)} $message"
            diagnosticLog = (listOf(entry) + diagnosticLog).take(MAX_DIAGNOSTIC_EVENTS)
        }

        fun insertOutbox(
            msgId: String,
            friendId: String,
            token: String,
            payloadBlob: ByteArray,
            createdAt: Long,
        )
    }

    private data class OutboxInsert(
        val msgId: String,
        val friendId: String,
        val token: String,
        val payloadBlob: ByteArray,
        val createdAt: Long,
    )

    private inner class MetadataScopeImpl : MetadataScope {
        override val friends: List<FriendEntry> get() = this@E2eeStore.friends.values.toList()

        private val queuedOutboxInserts = mutableListOf<OutboxInsert>()

        fun getQueuedOutboxInserts(): List<OutboxInsert> = queuedOutboxInserts

        override fun insertOutbox(
            msgId: String,
            friendId: String,
            token: String,
            payloadBlob: ByteArray,
            createdAt: Long,
        ) {
            queuedOutboxInserts.add(OutboxInsert(msgId, friendId, token, payloadBlob, createdAt))
        }

        private var pendingInvitesChanged = false
        private var newPendingInvites: List<PendingInvite> = this@E2eeStore.pendingInvites

        override var pendingInvites: List<PendingInvite>
            get() = if (pendingInvitesChanged) newPendingInvites else this@E2eeStore.pendingInvites
            set(value) {
                pendingInvitesChanged = true
                newPendingInvites = value
            }

        private var diagnosticLogChanged = false
        private var newDiagnosticLog: List<String> = this@E2eeStore._diagnosticLog.value

        override var diagnosticLog: List<String>
            get() = if (diagnosticLogChanged) newDiagnosticLog else this@E2eeStore._diagnosticLog.value
            set(value) {
                diagnosticLogChanged = true
                newDiagnosticLog = value
            }
            
        fun applyToDb() {
            if (pendingInvitesChanged) {
                val value = newPendingInvites
                val current = this@E2eeStore.pendingInvites
                value.forEach { invite ->
                    if (current.none { it.qrPayload.ekPub.contentEquals(invite.qrPayload.ekPub) }) {
                        database.invitesQueries.insertPendingInvite(
                            ekPub = invite.qrPayload.ekPub,
                            suggestedName = invite.qrPayload.suggestedName,
                            fingerprint = invite.qrPayload.fingerprint,
                            discoverySecret = invite.qrPayload.discoverySecret,
                            privKeyBlob = invite.aliceEkPriv,
                            createdAt = invite.createdAt,
                            exportedAt = invite.exportedAt,
                        )
                    }
                }
                current.forEach { invite ->
                    if (value.none { it.qrPayload.ekPub.contentEquals(invite.qrPayload.ekPub) }) {
                        database.invitesQueries.deletePendingInvite(invite.qrPayload.ekPub)
                    }
                }
            }
        }

        fun applyToMemory() {
            if (pendingInvitesChanged) {
                this@E2eeStore.pendingInvites.clear()
                this@E2eeStore.pendingInvites.addAll(newPendingInvites)
            }
            if (diagnosticLogChanged) {
                this@E2eeStore._diagnosticLog.value = newDiagnosticLog
            }
        }
    }
}
