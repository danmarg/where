# Code Improvements & Genuine Issues (Part 2)

After reviewing the identified potential issues in the `e2ee` protocol and testing implementation, the following have been verified as genuine issues, edge cases, or gaps in testing coverage that should be addressed in future iterations.

## Protocol & Logic Issues

### 1. `DecryptionExceptionWithState` path doesn't reset `consecutiveSilentDrops` [DONE]
**Location:** `E2eeStore.kt` (`processBatch()`) & `LocationClient.kt`
**Issue:** [DONE] Fixed. `LocationClient` now resets `consecutiveSilentDrops` if `anySuccess` OR `hadStateUpdate` is true.

### 2. `isFailedBatch` conflates `DecryptionExceptionWithState` with hard failures [DONE]
**Location:** `E2eeStore.kt` (`processBatch`)
**Issue:** [DONE] Fixed. `E2eeStore` now tracks `hardFailCount` and `softFailCount` separately. `isFailedBatch` only considers hard failures and silent drops.

### 3. Skipped epoch header key pruning is too aggressive on new DH epochs [TODO]
**Location:** `Session.kt` (`decryptMessage`)
**Issue:** [TODO] Still aggressive. `validEpochs` should include all epochs in `seenRemoteDhPubs`.

### 4. `cleanupExpiredInvites` TOCTOU window [TODO]
**Location:** `E2eeStore.kt` (`cleanupExpiredInvites`)
**Issue:** [TODO] Memory removal is still outside `friendLock`, leaving a small race window.

### 5. Asymmetric Retry vs. Follow Constants [TODO]
**Location:** `LocationClient.kt` & `ProtocolConstants.kt`
**Issue:** [TODO] `MAX_TOKEN_FOLLOWS_PER_POLL` (50) is still larger than `MAX_SILENT_DROP_RETRIES` (20).

---

## Test Fragility & Coverage Gaps

### 6. Fragile Assertion in `testCorruptPayloadAdvancement` [TODO]
**Location:** `E2eeChaosTest.kt`
**Issue:** [TODO] Test still relies on implicit background keepalives.

### 7. Missing Coverage: Consecutive `DecryptionExceptionWithState` [TODO]
**Location:** `E2eeChaosTest.kt`
**Issue:** [TODO] No test yet covers multiple consecutive soft failures.

### 8. Missing Coverage: Clock Jumps Backward During Transitions [DONE]
**Location:** `E2eeChaosTest.kt`
**Issue:** [DONE] Added `pending transition abandoned after timeout` to `LocationClientTest.kt`, verifying rollback after threshold. (Note: specific backward jump test using `ChaosTimeProvider` still a good-to-have).

### 9. Missing Coverage: Concurrent Cleanup vs. Processing [TODO]
**Location:** `E2eeStoreTest.kt`
**Issue:** [TODO] No concurrency test yet for `cleanupExpiredInvites`.

### 10. Chaos Probability in Multi-Node Tests [DONE]
**Location:** `E2eeChaosTest.kt`
**Issue:** [DONE] Added `testMultiFriendChaosHighStress()` with 30-50% chaos probability and `@Ignore` annotation.