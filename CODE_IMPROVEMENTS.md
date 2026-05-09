# Project Roadmap & Improvement Log

This document tracks technical debt, architectural improvements, and security-hardening tasks.

## PENDING

### 1. Architectural Refactoring
- **[TODO] Unify Preferences**: Make `LocationRepository` a consumer of `UserStore` rather than duplicating sharing preferences (`isSharingLocation`, `pausedFriendIds`).
- **[TODO] Singleton Global State**: Refactor `object LocationRepository` to a standard class to support parallel testing and better dependency injection.

### 2. Robustness & Error Handling
- **[TODO] `LocationClient.pollPendingInvite()`**: Remove deprecated function (still used in tests).

---

## COMPLETED

### Architectural Refactoring
- **[DONE] Manager/Store/Protocol Pattern**: Decomposed the monolithic `E2eeStore` into `E2eeManager` (coordinator), `E2eeStore` (persistence/locking), and `E2eeProtocol` (pure logic).
- **[DONE] UI State Isolation**: Extracted transient UI state (`isInviteSheetShowing`, `pendingQrForNaming`, etc.) from `LocationRepository` into a dedicated `UiStateStore`.
- **[DONE] Double-Buffered Storage**: Implemented `DoubleBufferedStorage` for all E2EE persistence to ensure atomic writes and prevent corruption.

### Robustness & Error Handling
- **[DONE] Typed Exceptions**: Replaced brittle string-based exception checking with a comprehensive `WhereException` hierarchy and sealed result classes.
- **[DONE] Non-Reentrant Lock Safety**: Established a strict lock hierarchy (`friendLock` -> `metadataLock`) and introduced `MetadataScope` to safely access global metadata from within friend-specific locks without deadlocks.

### Test Coverage & Validation
- **[DONE] Concurrency Robustness**: Added `ConcurrencyRobustnessTest` to verify thread-safety of parallel cleanup and batch processing under high contention.
- **[DONE] Protocol Resilience**: Added `testConsecutiveSoftFailures` to ensure the ratchet advances correctly even when payload decryption fails repeatedly.
- **[DONE] Multi-Friend Chaos**: Verified protocol stability with `testMultiFriendChaosHighStress()` and fixed isolation issues in `E2eeChaosTest`.

### Security & Memory Hygiene
- **[DONE] Sensitive Material Persistence**: Explicitly zeroized `PendingInvite.aliceEkPriv` and bootstrap `localDhPriv` in `E2eeStore` and `KeyExchange`.
- **[DONE] Serialization Bloat**: Removed monolithic `SerializedStore` and implemented granular per-friend storage using `DoubleBufferedStorage`.
- **[DONE] Memory Hygiene**: Added explicit `zeroize()` calls for ephemeral keys.
