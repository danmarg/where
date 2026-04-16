# Security Review: Cryptographic Protocol Implementation

**Reviewed:** `shared/src/commonMain/kotlin/net/af0/where/e2ee/`
**Spec:** `docs/e2ee-location-sync.md`

---

## Overall Assessment

The cryptographic implementation is **substantially correct**. The core Double Ratchet logic, HKDF parameters, AEAD usage, and key exchange are all faithful to the spec. The test suite is excellent, including RFC test vectors for all primitives. No critical protocol-level vulnerabilities were found.

Three medium issues and several low issues are documented below.

---

## HKDF Parameter Correctness — PASS

All HKDF calls were verified against the spec:

| Call | IKM | Salt | Info | Length | Status |
|------|-----|------|------|--------|--------|
| Key exchange expansion | SK | null (→ 32 zero bytes) | `"Where-v1-KeyExchange"` | 160 | ✓ |
| DH ratchet step (KDF_RK) | dhOutput | rootKey | `"Where-v1-RatchetStep"` | 96 | ✓ |
| Key confirmation | SK | null | `"Where-v1-ConfirmKey"` | 32 | ✓ |
| Routing token | rootKey (or SK for epoch 0) | null | `"Where-v1-RoutingToken" \|\| senderFp \|\| recipFp` | 16 | ✓ |
| Discovery token | discoverySecret | 32 zero bytes | `"Where-v1-Discovery"` | 16 | ✓ |
| Safety number | SHA-256(ordered pubkeys) | null | `"Where-v1-SafetyNumber"` | 60 | ✓ |
| Message nonce | messageKey | null | `"Where-v1-MsgNonce"` | 12 | ✓ |

All info strings are unique (domain-separated). Output lengths match the spec exactly.

---

## AEAD AAD Construction — PASS

`Session.buildMessageAad` (`Session.kt:445-457`) constructs:
```
AAD_PREFIX ("Where-v1-Message") || PROTOCOL_VERSION (4 bytes, BE) || senderFp (32) || recipientFp (32) || seq (8 bytes, BE) || dhPub (32)
```

The spec (§8) specifies `dhPub` in the AAD for ciphertext binding. The directionality (`isAlice` check) correctly determines sender/recipient fingerprint ordering. The `pn` field is inside the encrypted payload (not in AAD), which matches the spec's §7.4.1 design for metadata obfuscation.

Header encryption uses empty AAD (`ByteArray(0)`) with a random 12-byte nonce per call — acceptable given header keys are per-epoch, not per-message.

---

## Key Confirmation — PASS

`KeyExchange.buildKeyConfirmation` (`KeyExchange.kt:265-282`) correctly implements:
```
K_confirm = HKDF(ikm=SK, info="Where-v1-ConfirmKey", length=32)
MAC = HMAC-SHA-256(K_confirm, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
```

`verifyKeyConfirmation` uses a constant-time XOR accumulator with a prior length check — correct and resistant to timing attacks.

Alice verifies the routing token in addition to the key confirmation (step 5 of §4.2, `KeyExchange.kt:122-127`), which the spec mandates. ✓

---

## MEDIUM: KDF_CK Uses HMAC, Not HKDF — Minor Spec Delta

**File:** `Ratchet.kt:41-55`

```kotlin
internal fun kdfCk(chainKey: ByteArray): ChainStep {
    val messageKey  = hmacSha256(chainKey, byteArrayOf(0x01))
    val newChainKey = hmacSha256(chainKey, byteArrayOf(0x02))
    val nonce = hkdfSha256(ikm = messageKey, ...)
}
```

The spec (§5.1) describes KDF_CK as a "KDF chain" but does not prescribe HMAC with fixed-byte inputs vs HKDF. The Signal Double Ratchet spec uses exactly this pattern (HMAC-SHA-256 with 0x01/0x02 constants). This is correct.

However, the nonce is derived from the message key (`hkdfSha256(ikm = messageKey, info = "Where-v1-MsgNonce")`). This makes the nonce fully deterministic given the chain key, which is the spec's intention and is safe — each `(key, nonce)` pair is unique because each chain key advance is irreversible.

No action needed; documenting for completeness.

---

## MEDIUM: `zeroize()` on JVM Uses `Array.fill(0)` — JIT May Elide

**File:** `jvmAndAndroidMain/kotlin/net/af0/where/e2ee/CryptoPrimitivesImpl.kt:125-127`

```kotlin
internal actual fun ByteArray.zeroize() {
    this.fill(0)
}
```

The spec (§5.5) explicitly warns: *"On the JVM, `Arrays.fill()` is inherently limited by garbage collector behavior... For improved hygiene, consider using off-heap storage... or native bindings to libsodium."*

The JVM/Android implementation uses `fill(0)` which may be elided by the JIT compiler if the array is deemed "dead" after the fill. This is a known limitation acknowledged in the spec. The iOS implementation correctly uses `memset_s` which cannot be elided.

**Risk:** Low in practice for typical threat models (server compromise, backup recovery) but is a gap versus the stated spec guarantee.

**Recommendation:** Consider using JNI to call `sodium_memzero()` on Android, or `Unsafe.setMemory()` as a more reliable zeroing mechanism.

---

## LOW: `hmacSha256` Duplicated Across Platform Files

**Files:** `iosMain/.../CryptoPrimitivesImpl.kt:30-59`, `jvmAndAndroidMain/.../CryptoPrimitivesImpl.kt:27-55`

Both platform `CryptoPrimitivesImpl.kt` files contain **identical** HMAC-SHA-256 implementations in pure Kotlin. Since both platforms use the same `com.ionspin.kotlin.crypto` (libsodium-kotlin) library for the underlying `sha256()` primitive, the HMAC wrapping is pure Kotlin and could live in `commonMain`.

This is a code quality issue, not a security issue. A bug in the HMAC implementation would be fixed in only one copy.

**Fix:** Move `hmacSha256` to `commonMain`; keep only `sha256`, `sha512`, `randomBytes`, `x25519`, `aeadEncrypt`, `aeadDecrypt`, and `zeroize` as `expect/actual`.

---

## LOW: Safety Number Spec Uses SHA-256 Pre-Hash; IKM Should Be Raw Keys

**File:** `Fingerprint.kt:31-41`; Spec §4.4

Spec:
```
safety_number_bytes = HKDF-SHA-256(ikm=SHA-256(lower_EK.pub || higher_EK.pub), ...)
```

Implementation:
```kotlin
val input = if (cmp <= 0) localEkPub + remoteEkPub else remoteEkPub + localEkPub
val ikm = sha256(input)
return hkdfSha256(ikm = ikm, ...)
```

This exactly matches the spec. ✓ (Documenting because the double-hash pattern looks unusual but is intentional.)

---

## LOW: Header Nonce Is Random Per Message — Birthday Bound

**File:** `Session.kt:412`

```kotlin
val nonce = randomBytes(HEADER_NONCE_SIZE)  // 12 bytes
```

Random 96-bit nonces have a birthday collision probability of ~50% after 2^48 messages. For a friendship session with 30-second polling over a 7-day session: ~20,000 messages. The collision probability is negligible (≈ 10^{-9}). No action needed.

---

## LOW: `decryptMessage` Swallows Original Chain State on DH Failure

**File:** `Session.kt:236-241`

When a DH ratchet step occurs and AEAD decryption subsequently fails, the code correctly preserves the original `cleanState`. However, the speculative state's `localDhPriv` is zeroed (`speculativeState.localDhPriv.zeroize()`) — but `speculativeState.localDhPriv` is a *new* key pair generated inside `performDhRatchet`. Zeroing the freshly-generated key is harmless (it's discarded), but on a failed ratchet the calling code re-throws without committing the new state, which is the correct behavior.

No bug — zeroing a freshly-generated, uncommitted key is correct hygiene.

---

## PASS: Replay Protection

- Same-epoch replay: rejected by `seq <= speculativeState.recvSeq` check (`Session.kt:164-166`). ✓
- Large `seq` gap: rejected by `stepsNeeded > MAX_GAP + 1` check (`Session.kt:168-170`). ✓ (MAX_GAP = 100)
- Cross-epoch replay with old `dhPub`: rejected by `seenRemoteDhPubs` check (`Session.kt:154-157`). ✓
- Max gap overflow (attacker sends `seq = Long.MAX_VALUE`): rejected immediately by the gap check before any HKDF work, verified by `malicious large seq is rejected immediately without work` test. ✓

---

## PASS: Sequence Number Overflow Protection

`Session.encryptMessage` throws `SessionBrickedException` when `sendSeq == Long.MAX_VALUE` (`Session.kt:23`). This prevents nonce reuse at the ceiling of the sequence counter.

---

## PASS: Post-Compromise Security (PCS)

The DH ratchet is triggered on receipt of a new `remoteDhPub`. The keepalive mechanism (`LocationClient.pollFriend:127-134`) ensures that even a passive party (not sharing location) responds with a keepalive when it sees a new DH epoch from the peer, triggering mutual ratchet advancement. This correctly implements the spec's PCS guarantee.

---

## PASS: Token Verification at Key Exchange

Per spec §4.2 step 5: *"Alice MUST verify that the token in KeyExchangeInit matches her independently derived T_AB_0."*

`KeyExchange.aliceProcessInit` (`KeyExchange.kt:122-127`) derives the expected token and compares it with a byte-level `contentEquals`. Mismatch throws `AuthenticationException`. ✓

---

## Summary

| Issue | Severity | File |
|-------|----------|------|
| `zeroize()` on JVM may be JIT-elided | Medium | `jvmAndAndroidMain/CryptoPrimitivesImpl.kt:125` |
| `hmacSha256` duplicated in both platform files | Low | Both `CryptoPrimitivesImpl.kt` files |
| Header random nonce (birthday bound, negligible risk) | Informational | `Session.kt:412` |
