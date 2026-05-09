# Code Improvements & Genuine Issues (Part 2)

After reviewing the identified potential issues in the `e2ee` protocol and testing implementation, the following have been verified as genuine issues, edge cases, or gaps in testing coverage that should be addressed in future iterations.

## Protocol & Logic Issues

### 1. `DecryptionExceptionWithState` path doesn't reset `consecutiveSilentDrops`
**Location:** `E2eeStore.kt` (`processBatch()`) & `LocationClient.kt`
**Issue:** When a `DecryptionExceptionWithState` is caught, the protocol successfully authenticates the header and advances the DH ratchet (a "soft" failure), but `anySuccess` remains false. In `LocationClient.pollFriendToken()`, this results in `hasFailures = true`, incrementing the `consecutiveSilentDrops` counter. After `MAX_SILENT_DROP_RETRIES` (20), this forces an ACK, potentially dropping real DH advances.
**How to Fix:**
Update the failure condition in `LocationClient.pollFriendToken()` to consider `hadStateUpdate` as a success metric that resets the counter:
```kotlin
val hasFailures = (result.failCount > 0 || result.hadSilentDrops) && !result.anySuccess && !result.hadStateUpdate
// ...
if (result.anySuccess || result.hadStateUpdate) {
    store.resetConsecutiveSilentDrops(friendId)
}
```

### 2. `isFailedBatch` conflates `DecryptionExceptionWithState` with hard failures
**Location:** `E2eeStore.kt` (`processBatch`)
**Issue:** `failCount` increments for both hard failures (`AuthenticationException`) and soft failures (`DecryptionExceptionWithState`). Because `isFailedBatch` evaluates `failCount > 0`, `lastDecryptFailed` is set to `true` even when the session state advances successfully but the payload is malformed. This causes the UI to inappropriately show a "decryption failed" warning.
**How to Fix:**
1. In `processBatch`, track `hardFailCount` and `softFailCount` separately.
2. Increment `softFailCount` inside the `catch (e: DecryptionExceptionWithState)` block, and `hardFailCount` in the generic `catch (e: Exception)` block.
3. Update `isFailedBatch = (hardFailCount > 0 || silentDrops > 0) && !anySuccess`.
4. Ensure `lastDecryptFailed` is driven by `isFailedBatch`, ignoring pure soft failures.

### 3. Skipped epoch header key pruning is too aggressive on new DH epochs
**Location:** `Session.kt` (`decryptMessage`)
**Issue:** When `isNewDhEpoch` is true, the `derivationSkippedKeys` cache is pruned using a `retainAll` block that only preserves keys starting with the current and previous epochs. If a peer sends rapid consecutive ratchets, older valid epochs are dropped from the cache.
**How to Fix:**
Change the `validEpochs` calculation to include all epochs currently in the `seenRemoteDhPubs` state:
```kotlin
if (isNewDhEpoch) {
    val validEpochs = (speculativeState.seenRemoteDhPubs + remoteDhPub.toHex() + cleanState.remoteDhPub.toHex()).toSet()
    derivationSkippedKeys.keys.retainAll { k -> validEpochs.any { e -> k.startsWith("$e:") } }
}
```

### 4. `cleanupExpiredInvites` TOCTOU window
**Location:** `E2eeStore.kt` (`cleanupExpiredInvites`)
**Issue:** The function removes friends from memory under `metadataLock`, releases it, and then acquires `friendLock` to delete the associated storage. A concurrent `processBatch` could execute between these steps, writing new valid data to storage that is subsequently deleted.
**How to Fix:**
Move the memory removal logic out of the initial `metadataLock` block and place it inside the nested `friendLock` loop, matching the thread-safety pattern used in `deleteFriend()`:
```kotlin
toRemove.forEach { id ->
    val friendLock = getFriendLock(id)
    friendLock.withLock {
        metadataLock.withLock {
            friends.remove(id)
        }
        storage.putString("${friendKey(id)}_a", "")
        storage.putString("${friendKey(id)}_b", "")
    }
    locksLock.withLock { friendLocks.remove(id) }
}
```

### 5. Asymmetric Retry vs. Follow Constants
**Location:** `LocationClient.kt` & `ProtocolConstants.kt`
**Issue:** `MAX_SILENT_DROP_RETRIES` is set to 20, but `MAX_TOKEN_FOLLOWS_PER_POLL` is 50. In a replay-livelock attack, a single `pollFriend` call could follow tokens up to 50 times, bypassing the 20-retry force-ACK limit per poll loop.
**How to Fix:**
1. Align the constants in `ProtocolConstants.kt` so `MAX_TOKEN_FOLLOWS_PER_POLL = 20`.
2. In `LocationClient.pollFriendToken()`, if `follows >= MAX_TOKEN_FOLLOWS_PER_POLL`, force an ACK on the current token before breaking the loop to ensure livelocks are strictly broken.

---

## Test Fragility & Coverage Gaps

### 6. Fragile Assertion in `testCorruptPayloadAdvancement`
**Location:** `E2eeChaosTest.kt`
**Issue:** The test asserts `recvSeq == 2` with the comment `"corrupted transition + valid keepalive"`. The keepalive is automatically triggered by Alice's background `pollFriend()` logic. This is an implicit dependency on the keepalive-gating condition.
**How to Fix:**
Update the test to explicitly verify the steps:
```kotlin
// 3. Bob polls and catches the corrupted payload
val result = bobClient.poll()
assertTrue(result.isEmpty())
assertEquals(1, bobStore.getFriend(bobToAliceId)!!.session.recvSeq, "Should ratchet to 1")

// 4. Manually trigger Alice's keepalive (removing implicit background dependency)
aliceClient.sendKeepalive(aliceToBobId)
bobClient.poll()
assertEquals(2, bobStore.getFriend(bobToAliceId)!!.session.recvSeq, "Should ratchet to 2 after keepalive")
```

### 7. Missing Coverage: Consecutive `DecryptionExceptionWithState`
**Location:** `E2eeChaosTest.kt`
**Issue:** There is no test that covers multiple consecutive `DecryptionExceptionWithState` events to verify that `consecutiveSilentDrops` does not inappropriately accumulate.
**How to Fix:**
Add `testConsecutiveCorruptPayloadAdvancement()`. Have Alice send 3 messages, corrupting *only* the ciphertext payload for all 3 using `ChaosMailboxClient.corruptNextPayloadOnly`. Assert that Bob ratchets 3 times, `consecutiveSilentDrops == 0`, and a 4th valid message succeeds.

### 8. Missing Coverage: Clock Jumps Backward During Transitions
**Location:** `E2eeChaosTest.kt`
**Issue:** There is no test verifying what happens if a large backward clock jump occurs while `isSendTokenPending` is true.
**How to Fix:**
Add `testClockJumpBackwardDuringTransition()`. Create a pending transition, then use `TimeSource.setProvider` to roll the clock backward by 10 days (-864,000,000 ms). Call `LocationClient.processOutboxes()` and assert that the pending transition is *not* incorrectly abandoned (since `currentTime - sendTokenPendingSinceMs` would evaluate to a negative number, effectively `< PENDING_TRANSITION_TIMEOUT_MS`).

### 9. Missing Coverage: Concurrent Cleanup vs. Processing
**Location:** `E2eeStoreTest.kt`
**Issue:** No concurrency test validates the interaction between `cleanupExpiredInvites()` and an active `processBatch()` call for the same friend ID.
**How to Fix:**
Add `testConcurrentCleanupAndProcessBatch()`. Use `kotlinx.coroutines.launch` to run `processBatch` (using a mock storage that injects a 500ms delay during `putString`) concurrently with `cleanupExpiredInvites()`. Assert that the application does not throw `ConcurrentModificationException` and the final state is deterministic.

### 10. Chaos Probability in Multi-Node Tests
**Location:** `E2eeChaosTest.kt`
**Issue:** The chaos probability in `testMultiFriendChaos` is capped very low (`p ≤ 0.1`) for CI stability, meaning the `ProcessKilledException` path is rarely exercised.
**How to Fix:**
Duplicate the test into `testMultiFriendChaosHighStress()`. In this new test, use `node.setChaos(Random.nextDouble(0.3, 0.5))`. If the test framework supports it, annotate it with `@Ignore` or restrict it from running in standard CI, keeping it available for manual stress testing.