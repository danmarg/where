# Project Roadmap & Improvement Log

This document tracks technical debt, architectural improvements, and security-hardening tasks.

## PENDING

### 1. Architectural Refactoring
- **[TODO] Decompose `LocationSource`**: Move UI state (e.g., `isInviteSheetShowing`, `pendingQrForNaming`) out of `LocationRepository` into dedicated ViewModels or a `UIStore`.
- **[TODO] Unify Preferences**: Make `LocationRepository` a consumer of `UserStore` rather than duplicating sharing preferences (`isSharingLocation`, `pausedFriendIds`).
- **[TODO] Refactor `E2eeStore`**: Extract persistence logic and batch-sorting logic into dedicated components to reduce the 900+ line complexity.
- **[TODO] Singleton Global State**: Refactor `object LocationRepository` to a standard class to support parallel testing and better dependency injection.

### 2. Robustness & Error Handling
- **[DONE] Typed Exceptions**: Replaced brittle string-based exception checking (e.g., `e.message.contains("resolve")`) with explicit exception types or sealed result classes.
- **[TODO] `LocationClient.pollPendingInvite()`**: Remove deprecated function (still used in tests).

### 3. Test Coverage Gaps
- **[TODO] Fragile Assertion**: Fix `testCorruptPayloadAdvancement` in `E2eeChaosTest.kt` to remove implicit dependency on background keepalives.
- **[TODO] Consecutive Soft Failures**: Add test coverage for multiple consecutive `DecryptionExceptionWithState` events.
- **[TODO] Concurrent Cleanup vs. Processing**: Add concurrency test for `cleanupExpiredInvites` in `E2eeStoreTest.kt`.

---

## COMPLETED

### Security & Memory Hygiene
- **[DONE] Sensitive Material Persistence**: Explicitly zeroized `PendingInvite.aliceEkPriv` and bootstrap `localDhPriv` in `E2eeStore` and `KeyExchange`.
- **[DONE] Serialization Bloat**: Removed monolithic `SerializedStore` and implemented granular per-friend storage using `DoubleBufferedStorage`.
- **[DONE] Memory Hygiene**: Added explicit `zeroize()` calls for ephemeral keys.

### Protocol & Logic Improvements
- **[DONE] Silent Drop Counter**: `LocationClient` now resets `consecutiveSilentDrops` on soft failures (`DecryptionExceptionWithState`).
- **[DONE] Failure Conflation**: Separated hard and soft failures in `E2eeStore.processBatch`, ensuring `lastDecryptFailed` only triggers on genuine errors.
- **[DONE] Header Key Pruning**: `Session.kt` now retains header keys for all epochs present in `seenRemoteDhPubs`.
- **[DONE] Atomic Cleanup**: `cleanupExpiredInvites` now uses a synchronized lock hierarchy (`friendLock` then `metadataLock`) to prevent TOCTOU races.
- **[DONE] Constant Alignment**: Aligned `MAX_TOKEN_FOLLOWS_PER_POLL` with `MAX_SILENT_DROP_RETRIES` to 20.
- **[DONE] Abandoned Transitions**: Implemented automatic rollback for pending transitions after a 7-day timeout.

### Performance & Testing
- **[DONE] Parallel Networking**: `LocationClient` uses `async`/`awaitAll` for concurrent peer processing.
- **[DONE] High-Stress Chaos**: Added `testMultiFriendChaosHighStress()` with 30-50% chaos probability for manual verification.
