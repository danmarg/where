# Code Review & Architectural Improvements

This document outlines technical debt, architectural flaws, and security-hardening opportunities identified during the comprehensive code review.

## 1. Security & Memory Hygiene

### Sensitive Material Persistence [DONE]
While `zeroize()` is used for many keys, some sensitive materials were previously left for the Garbage Collector:
*   **`PendingInvite.aliceEkPriv`**: [DONE] In `E2eeStore.processKeyExchangeInit`, it is now explicitly zeroized in a `finally` block.
*   **Bootstrap `localDhPriv`**: [DONE] In `KeyExchange.aliceProcessInit`, the initial bootstrap key is now zeroized after being superseded.

### Serialization Bloat [DONE]
[DONE] The monolithic `SerializedStore` has been removed and replaced with granular per-friend storage using `DoubleBufferedStorage`. This significantly reduces memory pressure during save/load cycles.

## 2. Architectural Flaws

### Leaky Abstractions in `LocationSource` [TODO]
`LocationRepository` (implementing `LocationSource`) has become a catch-all for state:
*   **UI State Leakage**: Properties like `isInviteSheetShowing` and `pendingQrForNaming` are purely UI concerns and should not be in a shared multiplatform repository.
*   **Massive Interface**: `LocationSource` handles location updates, connection status, friend management, and UI state. It should be decomposed.

### Duplicate Source of Truth [TODO]
There is significant duplication of state between `UserStore` and `LocationRepository`:
*   Fields like `isSharingLocation` and `pausedFriendIds` exist in both.
*   `LocationViewModel` manually syncs them, which is error-prone. If sync logic is missing in a new component, the background service and UI will diverge.

## 3. Performance & Scaling [DONE]

### Parallel Network Processing [DONE]
`LocationClient` now uses `async`/`awaitAll` for polling and sending, ensuring a single slow peer doesn't degrade the experience for others.

## 4. Robustness

### Brittle Error Handling [TODO]
The project frequently relies on parsing exception message strings (e.g., `e.message?.contains("resolve")`) to determine error types in `KtorMailboxClient` and `LocationRepository`. This is brittle and liable to break if the underlying platform or library changes its error messages.

### `E2eeStore` Bloat [TODO]
At over 900 lines, `E2eeStore` handles:
1.  Persistent storage logic (Double Buffering).
2.  Friend registry management.
3.  Complex "Sealed Envelope" header decryption and batch sorting.
4.  Diagnostic logging.

## 5. Dead & Deprecated Code [TODO]

*   **`LocationClient.pollPendingInvite()`**: Marked as deprecated in favor of `pollPendingInvites()`. It is still used in `LocationClientTest.kt` but nowhere in the production app.
*   **`LocationRepository.reset()`**: Only used in unit tests. This highlights the testing friction caused by using a Singleton (`object`) for shared state.

## 6. Testing & Architectural Friction [TODO]

### Singleton Global State [TODO]
Using `object LocationRepository` makes it difficult to run tests in parallel and requires manual cleanup (`reset()`) between tests. This pattern also makes it harder to swap implementations for different build variants or mock environments.

## Recommendations

1.  **Parallelize Networking**: [DONE] Update `LocationClient` to use `async`/`awaitAll` for polling and sending.
2.  **Extract UI State**: [TODO] Move UI-specific flags out of the shared repositories and into ViewModels or a dedicated `UIStore`.
3.  **Unify Preferences**: [TODO] Make `LocationRepository` a consumer of `UserStore` rather than a duplicate holder of sharing preferences.
4.  **Strengthen Memory Hygiene**: [DONE] Add explicit `zeroize()` calls for ephemeral keys in `KeyExchange` and `E2eeStore`.
5.  **Refactor `E2eeStore`**: [TODO] Extract the persistence logic and the batch-sorting logic into dedicated components.
6.  **Typed Exceptions**: [TODO] Replace string-based exception checking with explicit exception types or sealed result classes.
