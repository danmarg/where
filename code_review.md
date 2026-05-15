🔴 Critical / Must-Fix
1. insertOutbox is called inside withFriendAndMetadataLock, but itself tries to
   acquire storeLock — deadlock risk

File: E2eeStore.kt, encryptAndAdvance in E2eeManager.kt

kotlin
// In E2eeManager.encryptAndAdvance:
return persistence.withFriendAndMetadataLock(friendId) { entry, _ ->
    ...
    persistence.insertOutbox(   // <-- acquires storeLock inside storeLock!
        msgId = outboxMsg.msgId, ...
    )
    ...
}
withFriendAndMetadataLock holds storeLock. insertOutbox then tries to call
storeLock.withLock { ... } again. Kotlin's Mutex is not reentrant — this will
deadlock on the first call to encryptAndAdvance. Same issue exists in
processScannedQr, which also calls persistence.insertOutbox inside
withFriendAndMetadataLock. You need an internal insertOutboxUnlocked variant for
callers already holding the lock.

2. Redis deleteById / deleteByIds use string.find for substring match — unsafe
   msgId collision

File: Server.kt, RedisMailboxState

lua
if string.find(v, msgId, 1, true) then
This scans the entire serialized JSON payload string for the msgId as a
substring. If a msgId value happens to appear in the ciphertext (base64),
coordinates, or any other field of another message, it will delete the wrong
entry. The msgId should be matched on a dedicated field (e.g., parse JSON and
check msgId key), or the raw string in the Redis list should be prefixed with
<msgId>:<payload> to enable exact-prefix matching. The in-memory implementation
correctly matches it.msgId == msgId, but Redis is inconsistent and fragile here.

3. receivedIds set in InMemoryMailboxState grows unboundedly for long-lived
   tokens

File: Server.kt, InMemoryMailboxState

The receivedIds eviction logic only removes the set entry when mailboxes[token]
== null && postTimes[token] == null. But postTimes entries persist until the
rate-limit window expires. For a high-traffic token, receivedIds will never be
evicted as long as any post activity exists. With 1000 messages/min per token
and a WAL retry storm on reconnect, this set could grow to tens of thousands of
entries per token per server lifetime. You need to cap the set size (e.g., LRU
or a bounded queue with a TTL per ID) or piggyback eviction on the mailbox TTL.

🟡 Significant / Should-Fix
4. E2eeManager constructor change is a breaking API change —
   MultiFriendIntegrationTest uses a two-arg constructor not reflected in the
primary API

File: E2eeManager.kt, MultiFriendIntegrationTest.kt

kotlin
val aliceManager = E2eeManager(MemoryStorage(), createTestSqlDriver())
But the new E2eeManager constructor signature is E2eeManager(sqlDriver:
SqlDriver) — a single argument. The test passes two arguments, implying there's
still a two-arg constructor somewhere (presumably with RawKeyValueStorage
retained for UserStore). The PR description says SharedPrefs is fully replaced,
but the test code contradicts this. This is at minimum confusing and could
indicate the migration is incomplete. Clarify or remove the MemoryStorage()
argument.

7. RATE_LIMIT_MAX_POSTS increased 10x (100 → 1000) and RATE_LIMIT_MAX_GETS 10x
   (1000 → 10000) without a corresponding abuse analysis

File: Server.kt

The justification ("20 friends × 50 retries") is reasonable for WAL retry
storms, but these limits are per token, not per client. A malicious actor can
now POST 1000 messages/min to any single token before being rate-limited, which
is a 10x increase in potential spam capacity against a victim's inbox. Consider
whether this limit should be applied per-sender-IP instead of per-token, or
whether the idempotency key already provides sufficient de-duplication to make
the limit less critical.

8. Network reconnect syncNow() on iOS fires on @MainActor — blocks main thread
   for potentially long WAL flush

File: ios/Sources/Where/LocationSyncService.swift

swift
Task { @MainActor in
    do {
        try await self.locationClient.syncNow()
    }
syncNow() flushes all pending outbox items, which involves multiple network
round-trips per friend. Running this on @MainActor will block the main runloop.
This should use a background actor or Task.detached with appropriate isolation.
The Android counterpart correctly uses serviceScope.launch {} which runs on a
coroutine dispatcher.

9. processOutboxes removed from the top of poll() — retry guarantee weakened

File: LocationClient.kt

kotlin
// REMOVED: processOutboxes()
val now = currentTimeSeconds()
The previous code called processOutboxes() at the start of every poll() to drain
the WAL before receiving. This guaranteed forward progress on failed sends. It's
now unclear when processOutboxes() is triggered. If it's only called from
syncNow(), WAL entries could sit indefinitely until a reconnect event fires.
Ensure there's an explicit callsite for processOutboxes() at the start of poll()
or sendLocation().

🔵 Minor / Nits
10. removeFromOutbox ignores friendId parameter

kotlin
suspend fun removeFromOutbox(friendId: String, msgId: String) {
    persistence.deleteOutboxByMsgId(msgId)  // friendId unused
}
The friendId parameter is silently ignored. Either use it (e.g.,
deleteOutboxByMsgIdAndFriendId for safety) or remove it from the signature.

11. DELETE /inbox/{token}?ids=... with empty list returns 204 — inconsistent
    with docs

If a client sends ?ids= (empty string), split(",").filter { it.isNotEmpty() }
returns an empty list, and the server calls deleteByIds(token, emptyList()) and
returns 204. The docs say ids is required. Return 400 for an empty ids list,
consistent with the missing-ids case.

12. The old POST /inbox/{token} route is retained alongside the new PUT
    /inbox/{token}/{msgId} — dead code and potential confusion

Both routes exist in the server. The old POST doesn't accept a msgId and thus
provides no idempotency. All client code now uses PUT. The POST route should
be removed.

13. Test: poll does not ACK when all messages fail decryption posts garbage with
    ByteArray(80) (all zeros) — not realistic

kotlin
EncryptedMessagePayload(v=PROTOCOL_VERSION, envelope=ByteArray(80),
ct=ByteArray(64))
All-zeros is a degenerate case that might coincidentally pass or fail certain
size checks differently from real noise. The original test used { (it * 37 +
13).toByte() } for deliberate pseudo-randomness. Consider restoring that for
more realistic failure coverage.

14. MetadataScopeImpl.pendingInvites setter does DB writes inside
    storeLock.withLock — but addDiagnosticEvent calls storeLock from outside

addDiagnosticEvent is not a suspend function and directly updates _diagnosticLog
without the lock. If called concurrently with a withMetadataLock block that also
modifies diagnosticLog, there's a race. Either make addDiagnosticEvent acquire
the lock (making it suspend) or use a separate MutableStateFlow update that's
atomic by nature.

15. SQLDelight 2.0.2 is not the latest stable — 2.0.2 has known iOS coroutine
    driver issues

Consider pinning to 2.1.x or confirming 2.0.2 is intentional given the iOS
native-driver dependency.




