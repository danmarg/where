# Android→iOS Ratchet Desync: Analysis

## Symptom

After ~12 hours of operation, the Android→iOS direction desyncs: iOS's `recvToken` no longer matches Android's `sendToken`. iOS→Android continues to work. The ChaosTest does not catch it.

Device pattern: the desync happens with a Samsung phone that has unreliable connectivity (can download but upload frequently times out). A Pixel with good connectivity does not trigger the bug.

---

## Why the ChaosTest Misses This

Two bugs in the test infrastructure together blind the chaos test to the production failure modes:

### Bug 1: `ChaosMailboxClient` delivers buffered (reordered) messages to the wrong token

`ChaosMailboxClient.outboxBuffer` stores the payload but **not** the token it was meant for. When the buffer is flushed on the next non-reordered call to `post`, it uses the **current** token—which may be different:

```kotlin
if (Random.nextDouble() < reorderProbability) {
    outboxBuffer.add(payload)           // token is NOT stored
} else {
    outboxBuffer.forEach { client.post(baseUrl, token, it) }  // uses current token!
    outboxBuffer.clear()
    client.post(baseUrl, token, payload)
}
```

A transition message meant for `prevSendToken` can silently land on `sendToken`. The chaos test models "message delivered to wrong mailbox," not "message delivered late." No production analog exists for this, so it tests a scenario that can't happen in prod while missing the ones that can.

**Fix**: store `token to payload` pairs in the buffer and deliver to the original token on flush.

### Bug 2: `RelayMailboxClient.poll` is destructive; `ack` is a no-op

```kotlin
override suspend fun poll(...): List<MailboxPayload> = inbox.remove(token) ?: emptyList()
override suspend fun ack(...) {}  // no-op
```

In production: `GET /inbox/{token}` is non-destructive; `DELETE /inbox/{token}?n=N` removes N messages. In the test: poll removes all messages immediately, ACK does nothing. This means:
- Any message that fails to decrypt (due to corruption or out-of-order delivery) is permanently lost in the test, but retryable in production.
- Messages never accumulate on the server between polls in the test, so the queue-fill deadlock scenario is never exercised.
- The relay has no `MAX_QUEUE_DEPTH`, so a retry flood never triggers 429.

**Fix**: make poll non-destructive; implement `ack` to remove the first `count` messages; add `maxQueueDepth` that throws `ServerException(429)` when exceeded.

---

## Server behavior (confirmed)

`POST /inbox/{token}` returns 204 (success) or 429 (rate-limited / queue full). It **never returns 404**.
Therefore the 404/410 branch in `processOutboxes` is dead code in production.

- Server TTL: 7 days (`MAILBOX_TTL_MS = 7 * 24 * 60 * 60 * 1000L`). Not the issue.
- `MAX_QUEUE_DEPTH = 1000`: queue fills in ~100 minutes at max retry rate
- `RATE_LIMIT_MAX_POSTS = 10` per minute per token
- `drain()` returns up to 50 messages non-destructively

---

## Root Cause (confirmed)

**Outbox retry flood fills `prevSendToken` inbox; iOS ratchets away; deadlock.**

### Full Mechanism

1. Samsung receives iOS's latest DH key B_n, performs a DH ratchet:
   - `prevSendToken = T_old`, `sendToken = T_new`, `isSendTokenPending = true`
2. Samsung tries to POST the transition message (carrying DH key A_{n+1}) to `T_old`.
3. Bad connectivity: the HTTP request reaches the server (message stored) but the response never arrives → timeout → `NetworkException`.
4. Samsung retries (outbox still pending). Each retry deposits another copy of the same seq=1 message on `T_old`. With `RATE_LIMIT_MAX_POSTS = 10/min`, the queue fills in ~100 minutes.
5. iOS wakes up, polls `recvToken = T_old`, gets a batch of up to 50 messages.
   - First message (seq=1) decrypts successfully: iOS performs DH ratchet → `recvToken = T_new`.
   - Remaining 49 copies of seq=1 fail to decrypt (iOS has already advanced its symmetric state): `hadSilentDrops = true` → **no ACK**.
6. iOS follows the recvToken rotation to `T_new` and polls there.
   - `T_old` still has ~950 messages queued.
7. Samsung retries to `T_old` → 429 (queue still ≥ `MAX_QUEUE_DEPTH` after iOS ACKed only 50 at force-ACK time). Outbox never clears.
8. Outbox pending → `sendMessageToFriendInternal` guard blocks all new sends.
9. **Deadlock**: iOS polls `T_new` (empty); Samsung can only retry to `T_old` (full); neither side can advance.

### Why Only Android→iOS

Android (background service) has a reliable download connection and polls iOS's messages consistently. iOS receives Android's transition messages before they accumulate. Samsung uploads fail intermittently, allowing copies to stack up in `T_old`. iOS downloads are unaffected, so iOS→Android works fine.

### Why ~12 Hours

Time-to-deadlock depends on Samsung's retry frequency, iOS's polling interval, and how quickly `T_old` reaches `MAX_QUEUE_DEPTH`. With 10 retries/min, 1000 messages accumulates in ~100 minutes, but intermittent connectivity stretches this to hours in practice.

---

## ~~Ruled-Out Hypotheses~~

### ~~Hypothesis A (original) — Server-side TTL~~

*Ruled out: TTL is 7 days; POST never returns 404; 404/410 branch in `processOutboxes` is dead code in production.*

### ~~Hypothesis B — Transient 404 triggers false desync declaration~~

*Ruled out: server never returns 404 on POST.*

---

## Remaining Secondary Hypotheses

### Hypothesis C — Multiple DH ratchets within one `processBatch` skip prevSendToken

If Android receives two messages from iOS in one batch where the second carries a different DH key (iOS ratcheted from a prior Android keepalive), `performDhRatchet` is called twice:

```kotlin
prevSendToken = state.sendToken.copyOf()  // takes current sendToken, not epoch-0
```

After two ratchets:
- Ratchet #1: `prevSendToken = T0`, `sendToken = T2`
- Ratchet #2: `prevSendToken = T2`, `sendToken = T4`

Android's next send goes to `T2`. But iOS is on `recvToken = T0`. Desync.

Reachable if two messages with different DH epochs arrive in the same `processBatch` call.

### Hypothesis D — `silentDropCounts` reset on app restart prevents force-ACK

`silentDropCounts` lives only in memory on `LocationClient`. iOS app restarts reset it to 0. A permanently stuck message requires `MAX_SILENT_DROP_RETRIES = 5` consecutive polls without restarts to trigger a force-ACK. With iOS killed every few hours, the counter may never reach 5, extending the window in which `T_old` stays un-ACKed and the deadlock persists.

---

## Production Fixes (Implemented)

### Fix 1: Atomic Token Transitions (TOCTOU Prevention)

A race condition between `processOutboxes` (recovery) and `sendLocation` could cause `finalizeTokenTransition` to overwrite a newer session state with an older one (e.g., rolling back `sendSeq` and `sendChainKey`). This led to cryptographic desyncs (replay rejections). 

Fixed by introducing `E2eeStore.clearSendTokenPending`, which atomically checks and clears the `isSendTokenPending` flag under a lock. `LocationClient` now uses this atomic check before sending its post-transition keepalive.

### Fix 2: Relax `hadSilentDrops` to allow ACKs of duplicates

Modified `E2eeStore.processBatch` to distinguish between "true" silent drops (unrecognized headers) and decryption failures of recognized headers (e.g., duplicates from a retry flood). Duplicates no longer set `hadSilentDrops = true`, allowing the peer to ACK and clear the `prevSendToken` queue immediately.

### Fix 3: Chronological Batch Message Sorting

A bug in `E2eeStore.processBatch` caused messages from the "Current" epoch to be sorted *before* messages from the "Last" epoch within a single batch. This could cause decryption to fail or skip messages if the receiver ratcheted forward before processing all historical messages from the same batch.

Fixed by correcting the sorting order (Last=0, Current=1) and adding a secondary sort by `pn` (previous sequence number) to handle multiple unknown NEW epochs arriving in a single batch (Hypothesis C).

### Fix 4: Removed Hazardous 429 Deadlock Recovery

A previous attempt at "Deadlock Recovery" (`MAX_OUTBOX_429_RETRIES`) was found to be hazardous. It conflated HTTP 429 (Rate Limit) with HTTP 429 (Queue Full). If a client hit the server's rate limit during a transition, it would inappropriately abandon the DH transition message, permanently desyncing the peer.

This logic has been removed. The "Queue Fill Deadlock" is now handled robustly by Fix 2 (receiver ACKing duplicates) without the need for the sender to abandon critical transition messages.

### Fix 5: Persistent `silentDropCounts` (Future Hardening)

`silentDropCounts` still resides in memory in `LocationClient`. While Fix 2 resolves the immediate retry-flood deadlock, moving this to `E2eeStore` remains a valid hardening step for cases where Fix 2 might not apply (e.g., actual corruption).

---

## Summary

| Issue | Status | Production impact |
|---|---|---|
| `ChaosMailboxClient` delivers reordered messages to wrong token | **Fixed (test)** | Tests non-production scenario |
| `RelayMailboxClient.poll` destroys messages; no queue depth | **Fixed (test)** | Hides retry-flood deadlock |
| Retry flood fills `T_old` queue; iOS ratchets to `T_new`; deadlock | **Fixed (prod)** | Permanent desync in ~12 h |
| `silentDropCounts` reset on iOS restart delays force-ACK | Mitigation applied | Extends deadlock window |
| Double-ratchet in one `processBatch` may skip `prevSendToken` | Unconfirmed edge case | Possible separate desync path |
