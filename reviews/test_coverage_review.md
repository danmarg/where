# Test Coverage Review

**Reviewed:** `shared/src/commonTest/kotlin/net/af0/where/e2ee/`, `ios/Tests/WhereTests/`, `android/src/androidUnitTest/`

---

## Overall Assessment

The shared crypto layer has **strong unit test coverage**: RFC test vectors for all primitives, full key exchange round-trips, DH ratchet correctness, replay rejection, gap handling, and transactional safety. The integration and platform layers are more sparsely tested.

---

## What Is Well-Covered

| Area | Test file | Notes |
|------|-----------|-------|
| Crypto primitives (SHA-256, HMAC, X25519, ChaCha20-Poly1305, HKDF) | `CryptoPrimitivesVectorTest.kt` | RFC test vectors; runs on all platforms |
| Key exchange (QR payload, key confirmation, token verification) | `KeyExchangeTest.kt` | Covers tampered payloads, MITM session divergence, safety number, discovery token |
| Ratchet KDF (kdfCk, kdfRk, routing token derivation) | `RatchetTest.kt` | Determinism, output sizes, domain separation |
| Session encrypt/decrypt round-trip, DH ratchet | `SessionTest.kt` | Sequential messages, replay, MAX_GAP, DH ratchet advancement, stale key expiry |
| Header envelope encrypt/decrypt | `EnvelopeTest.kt` | — |
| Token rotation and follow limit | `TokenTransitionTest.kt` | Verifies MAX_TOKEN_FOLLOWS_PER_POLL |
| Outbox persistence and recovery | `OutboxRecoveryTest.kt` | Crash simulation, 404 permanent failure |
| Dual-post replay across tokens | `DualPostReplayTest.kt` | Transactional safety on failed ratchet |
| Key recycling rejection (`seenRemoteDhPubs`) | `SessionTest.testRecycledDhPubRejection` | — |
| E2eeStore persistence | `E2eeStoreTest.kt` | — |
| iOS throttle / poll logic | `LocationSyncServiceTests.swift` | Foreground/background state, rapid poll |

---

## Missing Tests

### MEDIUM: `processBatch` Receives Out-of-Order Messages Within Same Epoch

`processBatch` sorts messages by DH epoch then seq (`E2eeStore.kt:500-521`). There is no test verifying that messages arriving out of seq order within the *same* DH epoch (e.g., seq 3, then seq 1, then seq 2) are correctly reordered and decrypted using the skipped-message-keys cache.

**Suggested test:** Encrypt messages seq 1, 2, 3; deliver to `processBatch` in order [3, 1, 2]; verify all three decrypt correctly and the final session has `recvSeq = 3`.

---

### MEDIUM: `processBatch` With Mixed Old- and New-Epoch Messages in One Batch

The sorting logic in `processBatch` uses `remoteDhPub` comparison to prioritize old-epoch messages before new-epoch messages. There is no test for a batch that contains messages from two different DH epochs arriving simultaneously (the transition-message plus the first message of the new epoch).

**Suggested test:** Deliver `[msg_epoch_1_seq_3, msg_epoch_2_seq_1]` in one batch; verify both decrypt and that the recv token advances correctly.

---

### MEDIUM: `isStale` Boundary Conditions

`FriendEntry.isStale` returns true after `ACK_TIMEOUT_SECONDS` (7 days). There is no test for:
- A friend exactly at the 7-day boundary (off-by-one risk)
- A friend with `lastRecvTs == Long.MAX_VALUE` (the never-stale sentinel — set nowhere in code, but the check is there)
- `LocationClient.sendLocation` skipping stale friends

---

### MEDIUM: `sanitizeName` Homograph Protection

There is no test verifying that `sanitizeName` strips lookalike Unicode, limits length, and filters non-alphanumeric characters. The `normalizeName` (NFKC) contract is tested implicitly but not directly for homograph edge cases (e.g., `"Ａlice"` → `"Alice"`, Cyrillic lookalikes).

---

### LOW: `acc` (GPS Accuracy) Round-Trip

`sendLocation` hardcodes `acc = 0.0` (see `android_review.md`). Once fixed, there should be a test verifying that non-zero accuracy survives the encrypt/decrypt round-trip and is accessible to the recipient.

---

### LOW: `processBatch` `outgoing` List Is Untested (and Always Empty)

The `outgoing` field of `PollBatchResult` is never populated and never tested. A test confirming it is empty would at least make the dead code explicit. Better: remove the field entirely (see `dead_code_review.md`).

---

### LOW: `deriveDiscoveryToken` With All-Zero Secret

The discovery token is derived from a 32-byte random secret. No test verifies that an all-zero secret (which `randomBytes` should never produce but theoretically could) still produces a valid token distinct from a non-zero secret. This is a robustness edge case, not a security issue.

---

### LOW: Android `LocationService` Foreground → Background Poll Interval Transition

`LocationServiceTest.kt` tests `pollInterval()` return values in isolation but does not test the full transition: service starts in foreground (10s), app moves to background (300s or 1800s), then foreground again. A state-machine test would catch regressions in the `isRapidPolling` / `isAppInForeground` / `isSharingLocation` combination.

---

### LOW: iOS Pairing Flow — Cold Start Clears Pending Invite

There is no test verifying that a cold start during an active QR invite exchange (`InviteState.Pending`) clears the invite. This tests a known UX regression identified in `ios_review.md`.

---

### LOW: `Session.unpad` With Empty Plaintext

`padToFixedSize` asserts `data.size > 0`. `unpad` with a buffer containing only the 0x80 marker at index 0 (empty plaintext) is not tested. This is a degenerate case — `Keepalive` messages have no payload beyond the JSON skeleton, but the JSON encoding is never empty.

---

## Android Test Coverage

`LocationServiceTest.kt` mocks the fused location provider and E2EE store to test:
- `sendLocationIfNeeded` throttle logic
- `pollInterval` computation

Missing:
- Test that `doPoll` correctly calls `updateLastLocation` with the sender's timestamp (not current time) — would catch the `now / 1000L` bug
- Test that the service does NOT start from `BootReceiver` before `ACTION_USER_UNLOCKED` on FBE devices

## iOS Test Coverage

`LocationSyncServiceTests.swift` tests throttle logic and basic service lifecycle. Missing:
- Test that `pollAll` stores `update.timestamp`, not current time
- Test that cold-start does not clear an unexpired pending invite

---

## Summary

The crypto core is well-tested. Gaps are concentrated in:
1. `processBatch` multi-message and cross-epoch ordering
2. Stale-friend boundary conditions
3. The timestamp storage bug (which a test would have caught)
4. Platform-level reliability scenarios (BootReceiver, cold start)
