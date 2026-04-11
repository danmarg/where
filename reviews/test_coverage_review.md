<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are a test quality reviewer. Fetch and read every test file in the shared/src/commonTest/ directory:

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/CryptoPrimitivesVectorTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/CryptoPrimitivesVectorTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/E2eeStoreTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/E2eeStoreTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/KeyExchangeTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/KeyExchangeTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/MailboxMessageTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/MailboxMessageTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/PreKeyBundleTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/PreKeyBundleTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/RatchetTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/RatchetTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/SessionTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/SessionTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/LocationSyncStateTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/LocationSyncStateTest.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/TimestampTest.kt](https://github.com/danmarg/where/blob/main/shared/src/commonTest/kotlin/net/af0/where/e2ee/TimestampTest.kt)
Also read the e2e test script:
[https://github.com/danmarg/where/blob/main/e2e-test.sh](https://github.com/danmarg/where/blob/main/e2e-test.sh)
And the manual testing doc:
[https://github.com/danmarg/where/blob/main/MANUAL_TESTING.md](https://github.com/danmarg/where/blob/main/MANUAL_TESTING.md)
Evaluate the following:

Coverage of security-critical paths. For each of the following, state whether there is a test, whether it is adequate, and what is missing:

Key exchange happy path (Alice initiates, Bob responds, both derive same SK)

Key exchange with a tampered key_confirmation MAC (should abort)

Key exchange with a substituted EK_B.pub (MITM simulation)

Per-message forward secrecy: compromise of MK_n does not expose MK_{n-1}

OPK-based epoch rotation: correct ratchet advancement, new tokens derived, old tokens retired

OPK depletion fallback: symmetric-only ratchet continues correctly

Replay rejection: a frame with seq <= max_seq_received is dropped

Gap handling: skipping 3 messages and still decrypting the next one

EpochRotation with wrong key (decryption failure → no ratchet advancement)

PreKeyBundle with tampered MAC (should be rejected)

Token transition: correct dual-polling window behavior

Safety number calculation: deterministic, same on both sides

Test vector quality. CryptoPrimitivesVectorTest.kt presumably contains hardcoded test vectors. Evaluate whether the vectors cover all primitive usages (X25519, HKDF, ChaCha20-Poly1305, HMAC-SHA-256) and whether the vectors are derived from authoritative external sources (RFC test vectors) or are self-generated.

Test isolation. Are tests properly isolated (no shared mutable state between test cases)? Does E2eeTestInitializer.kt do anything that could make tests non-deterministic?

Missing test categories. Produce a prioritized list of test cases that should exist but do not. For each, write a one-paragraph description of what the test should verify and why it matters for security.

E2E test evaluation. Read e2e-test.sh and evaluate whether it exercises the full pairing → location send → receive → epoch rotation flow, or whether it only tests a subset. What scenarios does it miss?
Produce your output as: (a) a coverage table (path, covered?, adequate?), (b) missing test list with priority, (c) overall test quality assessment.

Now I have a complete picture of all test files. Here is the full analysis:

***

## (a) Security-Critical Path Coverage Table

| \# | Security Path | Test Exists? | Adequate? | What Is Missing |
| :-- | :-- | :-- | :-- | :-- |
| 1 | **Key exchange happy path** (Alice→Bob, both derive same SK) | ✅ Yes — `KeyExchangeTest`, `E2eeStoreTest.testKeyExchangeFlow`, `SessionTest.exchangeKeys()` | ✅ Adequate | Nothing significant; root key, chain keys, and tokens are all asserted cross-side |
| 2 | **Tampered `key_confirmation` MAC** (should abort) | ✅ Yes — `KeyExchangeTest.aliceProcessInit rejects tampered key_confirmation` | ⚠️ Partial | Test only zeros the confirmation bytes; a single-byte XOR flip (closer to a real MITM) and a confirmation with a correct MAC over *different* public keys are not tested |
| 3 | **Substituted EK\_B.pub** (MITM simulation) | ❌ No | ❌ Missing | No test constructs a `KeyExchangeInit` with a different `ekPub` while preserving a plausible `keyConfirmation`. This is the primary MITM scenario for the X3DH-style exchange and has zero coverage |
| 4 | **Per-message forward secrecy** (compromise of MK\_n does not expose MK\_{n−1}) | ❌ No | ❌ Missing | Tests confirm sequential encryption works but never derive MK\_n independently and verify MK\_{n−1} is unrecoverable from it; the unidirectional KDF property is assumed, not asserted |
| 5 | **OPK-based epoch rotation** (correct ratchet advancement, new tokens derived, old tokens retired) | ✅ Yes — `E2eeStoreTest.testEpochRotationEndToEnd` and `testEpochRotationEncryptDecryptAfterRotation` | ⚠️ Partial | OPK depletion from Alice's cache is checked (`assertEquals(2, theirOpkPubs.size)`), but there is no assertion that the *consumed* OPK private key is deleted from Bob's store after rotation, leaving the door open for key reuse |
| 6 | **OPK depletion fallback** (symmetric-only ratchet continues correctly) | ❌ No | ❌ Missing | No test exercises `processEpochRotation` when Alice has zero cached OPKs, nor the symmetric ratchet path that should engage in that scenario |
| 7 | **Replay rejection** (`seq ≤ max_seq_received` dropped) | ✅ Yes — `SessionTest.replay is rejected` and `frame with lower seq than recvSeq is rejected` | ✅ Adequate | Both exact-replay and below-high-watermark cases are covered with correct error message assertions |
| 8 | **Gap handling** (skip 3 messages, decrypt the next one) | ✅ Yes — `SessionTest.exactly MAX_DECRYPT_GAP missed messages is allowed` | ⚠️ Partial | Only the boundary case (gap = MAX\_DECRYPT\_GAP) is tested; a small realistic gap of 3–5 messages with *in-order delivery of the skipped frames afterward* is never tested |
| 9 | **EpochRotation with wrong key** (decryption failure → no ratchet advancement) | ✅ Yes — `SessionTest.EpochRotation AEAD fails with wrong root key` | ⚠️ Partial | Only the AEAD layer is tested at the `Session` level; there is no store-level test verifying that `processEpochRotation` on `E2eeStore` does *not* advance `bobEntry.session.epoch` when given a bad payload |
| 10 | **PreKeyBundle with tampered MAC** (rejected) | ✅ Yes — `E2eeStoreTest.testStoreOpkBundleRejectsBadMac` | ✅ Adequate | Both single-byte MAC corruption and tampered OPK list (`PreKeyBundleTest`) are covered |
| 11 | **Token transition / dual-polling window** | ❌ No | ❌ Missing | No test verifies that during the epoch overlap window, messages posted to the *old* token are still receivable while the *new* token becomes the primary send address |
| 12 | **Safety number: deterministic and same on both sides** | ✅ Yes — `KeyExchangeTest.safetyNumber is symmetric` + `FriendEntry safetyNumber is stable across epoch rotations` | ⚠️ Partial | Symmetry and stability are tested, but there is no cross-participant test (Alice computes `safetyNumber(aliceFp, bobFp)`, Bob computes it independently from his own session state, and both outputs are compared byte-for-byte) |


***

## (b) Test Vector Quality

**SHA-256:** Two vectors from [FIPS 180-4](https://csrc.nist.gov/publications/detail/fips/180/4/final) (`"abc"` and empty string) are present and correctly labelled . These are authoritative.

**HMAC-SHA-256:** One vector from [RFC 4231 Test Case 1](https://datatracker.ietf.org/doc/html/rfc4231) is present with the correct key and data . However, RFC 4231 has **seven** test cases, including a key longer than the block size (TC 4), a data string repeated to stress the algorithm (TC 6), and a key+data combo that produces a specific truncation (TC 7). Only TC 1 is covered; TC 4 and TC 7 are the most security-relevant omissions.

**X25519:** There is **no hardcoded RFC 7748 §6.1 vector** . The test is a round-trip symmetry check using freshly generated random key pairs. This means a platform-specific bug that produces *self-consistent* but *wrong* Diffie-Hellman output (e.g., a byte-swapped key or a different base point) would not be caught. The RFC 7748 §6.1 Alice/Bob fixed test vector (`e6db6867...` → `c3da5579...`) should be added.

**ChaCha20-Poly1305:** The tests are self-generated round-trips with sequentially incremented keys/nonces . There are no vectors from the [IETF RFC 8439](https://datatracker.ietf.org/doc/html/rfc8439) test vector appendix. A platform divergence (e.g., using the original 96-bit-nonce XSalsa20 variant on iOS vs. the IETF variant on JVM) would produce wrong output that passes the round-trip test because both sides of the round-trip share the same buggy implementation.

**HKDF:** No HKDF vectors at all. The `kdfCk` and `kdfRk` tests in `RatchetTest.kt` only verify determinism and output sizes ; there is no RFC 5869 test vector to confirm the HKDF-Extract / HKDF-Expand computation is correct.

***

## (c) Test Isolation

`E2eeTestInitializer.kt` calls `LibsodiumInitializer.initializeWithCallback {}` . This is a global singleton initialization that is idempotent — calling it multiple times is safe. The callback does nothing.

**Positive:** Every test class that allocates mutable state (`E2eeStoreTest`, `LocationSyncStateTest`) uses `@BeforeTest fun setup()` which creates fresh `MemoryStorage` and `E2eeStore` instances before each case . There is no shared `companion object` mutable state between test instances.

**Concern — double initialization:** `CryptoPrimitivesVectorTest` calls `initializeE2eeTests()` in both the `companion object { init {} }` block and in the instance `init {}` block . This is harmless with libsodium's idempotency but is a code smell that could mask a stricter initializer that throws on double-call.

**Concern — `initializeE2eeTests()` is not `@BeforeTest`:** All other classes call it from instance `init {}`. On Kotlin/Native (iOS), `init {}` on a class runs once per instance, which is correct. On JVM/Android, test runners typically create a new instance per test. The pattern is functionally correct but fragile if the test class is ever made to share an instance.

**Overall isolation verdict:** Tests are well-isolated. No shared mutable state is observable between test cases.

***

## (d) Prioritized Missing Tests

### P0 — Critical (should block release)

**1. MITM via substituted EK\_B.pub**
This test should construct a `KeyExchangeInit` message where `ekPub` is an attacker-controlled key while `keyConfirmation` is recomputed over that substituted key. It should then verify that `aliceProcessInit` either rejects the message (because Alice cannot verify the confirmation against her expected DH output) or that the derived session keys differ from the legitimate exchange. This is the only realistic active attack on the pairing flow — if a server-side or network attacker can replace `EK_B.pub` in transit, they can establish two independent sessions and relay silently. The absence of this test means the `verifyKeyConfirmation` path is never stress-tested against a coherent MITM payload.

**2. RFC 7748 §6.1 fixed-vector test for X25519**
This test should hardcode the test-vector public keys from RFC 7748 §6.1 (`u = 0xe6db6867...`, scalar `= 0xa546e36b...`, output `= 0xc3da5579...`) and assert that `x25519()` produces the exact expected bytes. Without this, the entire security of the key exchange is conditioned on platform X25519 implementations that are tested only for self-consistency, not for spec-conformance. A big-endian/little-endian swap or a wrong clamp would be invisible to the current round-trip test.

**3. Consumed OPK is deleted from Bob's store**
After `E2eeStore.processEpochRotation` succeeds, the test should assert that the specific OPK ID that was consumed is no longer present in `bobEntry.myOpkPrivs`. Currently the epoch rotation tests check that Alice's cached count decrements, but they do not verify that Bob deletes the used private key. If the private key persists, an attacker who compromises Bob's device later can retroactively compute the DH output for that epoch rotation, eliminating forward secrecy.

### P1 — High

**4. Per-message forward secrecy: MK\_n cannot reproduce MK\_{n−1}**
This test should encrypt messages 1 through N, record MK\_N (the message key for the N-th message by calling `kdfCk` on the N-th chain key), then verify that starting from MK\_N alone, no amount of application of any function exposed by the ratchet API can recover MK\_{N-1} or earlier plaintexts. This validates the unidirectional property of the KDF chain: if an attacker records ciphertext and later obtains MK\_N, they must not be able to decrypt message N-1.

**5. OPK depletion fallback: symmetric-only ratchet**
This test should call `initiateEpochRotation` when `theirOpkPubs` is empty and verify that the function either returns a valid symmetric-only rotation payload or returns a well-defined error rather than crashing or leaking state. It should then verify that subsequent message encryption/decryption still works correctly under the fallback ratchet. The current suite has no test for what happens when Alice runs out of Bob's OPKs, so this failure mode is entirely untested.

**6. Store-level processEpochRotation rejects bad payload → epoch unchanged**
This test should deliver a `EpochRotationPayload` to `E2eeStore.processEpochRotation` where the encrypted body has been tampered, then assert that Bob's `session.epoch` remains at the pre-rotation value and that the `myOpkPrivs` list is unmodified. The existing AEAD failure test operates at the `Session` layer, not the `E2eeStore` layer, so a logic bug that advances the epoch counter before verifying the AEAD tag would not be caught.

**7. RFC 8439 ChaCha20-Poly1305 IETF fixed-vector test**
This test should hardcode a plaintext, key, nonce, and AAD from RFC 8439 Appendix A.5 and assert that `aeadEncrypt` produces the exact expected ciphertext+tag bytes. The current test uses self-generated keys and nonces and would pass even if the implementation were using XSalsa20-Poly1305 (the libsodium default) instead of the IETF ChaCha20-Poly1305. Since iOS uses CommonCrypto and Android uses libsodium, confirming they emit identical wire bytes for the same inputs is essential.

### P2 — Medium

**8. Token transition: dual-polling window**
This test should simulate epoch rotation by calling `initiateEpochRotation`, storing the new epoch's routing token, and then posting a message addressed to the *old* token (simulating a message in-flight at rotation time). It should verify that the receiver accepts the message on the old token during the overlap window and rejects it after the window closes.

**9. Gap handling: deliver skipped messages out of order**
This test should send messages 1–5, deliver only 5 to Bob (Bob stores skipped chain states for 1–4), then deliver messages 1–4 in reverse order and verify all decrypt correctly. This exercises the skipped-message-key cache, a subtle state machine that could allow replays or double-decrypt if its purge logic is wrong.

**10. RFC 5869 HKDF fixed-vector test**
This test should use the RFC 5869 Test Case 1 inputs (`IKM = 0x0b0b...`, `salt = 0x000102...`, `info = 0xf0f1...`) and assert that the first N bytes of `kdfCk` or `kdfRk` output match the RFC's expected OKM bytes. This is especially important on iOS where the HKDF implementation differs from JVM libsodium.

***

## (e) E2E Test Evaluation

`e2e-test.sh` exercises only a subset of the full application flow . It covers:

- Alice creates an invite and Bob joins it via the CLI
- Alice polls once to complete key exchange
- Alice sends a location, verifies throttling, overrides with `--force`
- Bob polls and verifies receipt of the final location

**Scenarios the e2e script misses:**

- **No epoch rotation.** The script never sends enough messages to trigger `shouldRotateEpoch`, nor does it manually invoke an epoch rotation. The entire DH ratchet advancement path (OPK generation, `initiateEpochRotation`, `processEpochRotation`, `processRatchetAck`) is untested end-to-end.
- **Bidirectional location send.** Only Alice→Bob direction is exercised. Bob never sends a location to Alice, so the symmetric token setup and Bob's send chain are never exercised via the CLI.
- **OPK lifecycle.** Bob never generates an OPK bundle (`cli opk-replenish` or equivalent), so OPK management is untested in e2e.
- **Reconnect / cold start.** The script does not restart either Alice's or Bob's CLI process between send and poll, so state persistence (the serialize/deserialize path for session keys) is only implicitly tested, not as an explicit reconnect scenario.
- **Multi-friend sessions.** Alice is never paired with a second friend concurrently; per-friend session isolation is untested.
- **Server-side error handling.** The script does not test what happens when the server is down during a send, or when a message is posted to a stale token.

The MANUAL_TESTING.md  covers Android/iOS device setup but describes only the basic `invite → join → sync` flow with no mention of epoch rotation, OPK replenishment, safety number verification, or error scenarios.

***

## Overall Test Quality Assessment

The test suite is **above average for a KMP cryptographic application** and demonstrates genuine security thinking: the replay rejection, key confirmation verification, AEAD binding to sender fingerprints, and PreKeyBundle MAC rejection tests all reflect real threat modelling. The `@BeforeTest` isolation pattern is consistently applied and `E2eeTestInitializer` is clean .

However, there are three structural gaps that would prevent this suite from providing strong security assurance:

1. **No authoritative external test vectors for X25519 or ChaCha20-Poly1305.** Self-generated round-trip tests cannot detect a platform divergence that is internally consistent. This is the single highest-risk gap because the iOS and JVM implementations use entirely different native crypto libraries.
2. **The MITM substituted-EK attack has no test.** The `key_confirmation` tests cover only zeroed-out values, not a coherent adversarial payload.
3. **The e2e script tests approximately 20% of the protocol surface area.** Epoch rotation — the mechanism that provides forward secrecy — is never exercised in any automated integration test.
