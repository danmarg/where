<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are a security-focused code reviewer. Your task is to verify that the Kotlin Multiplatform implementation of a cryptographic protocol faithfully implements its specification. The spec is at:

[https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md](https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md)

The implementation lives in shared/src/commonMain/kotlin/net/af0/where/e2ee/. Fetch and read each of these files in full:

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Session.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Session.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/KeyExchange.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/KeyExchange.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Ratchet.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Ratchet.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/CryptoPrimitives.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/CryptoPrimitives.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Hkdf.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Hkdf.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/DiscoveryToken.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/DiscoveryToken.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/PreKeyBundleOps.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/PreKeyBundleOps.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/MailboxMessage.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/MailboxMessage.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Types.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Types.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/ProtocolConstants.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/ProtocolConstants.kt)

[https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Fingerprint.kt](https://github.com/danmarg/where/blob/main/shared/src/commonMain/kotlin/net/af0/where/e2ee/Fingerprint.kt)
Check the following, citing specific line numbers and quoting relevant spec language for each finding:

HKDF parameter correctness. For every HKDF call, verify: (a) the ikm, salt, and info strings exactly match the spec (including byte encodings — big-endian epoch, direction byte, etc.); (b) the output length is correct; (c) the info strings are unique per usage (domain separation).

AEAD AAD correctness. For EncryptedLocation encryption, verify that the AAD includes exactly: "Where-v1-Location" (UTF-8) + version (4 bytes, BE) + alice_fp (32 bytes) + bob_fp (32 bytes) + epoch (4 bytes, BE) + seq (8 bytes, BE). For EpochRotation and RatchetAck, verify the routing token is included in AAD in raw binary (not base64). For PreKeyBundle, verify the HMAC covers v_be4 || send_token || canonical_keys_blob.

Nonce handling. For location messages, verify the nonce comes from KDF_CK output (not random). For control messages, verify the nonce is epoch_be4 || 0x00{8} (12 bytes). Confirm no nonce reuse is possible in normal operation.

Key deletion discipline. Verify the implementation explicitly zeroes or discards: (a) EK_A.priv after SK derivation in KeyExchange.kt; (b) EK_B.priv after posting KeyExchangeInit; (c) OPK private keys immediately after computing dh_out in epoch rotation; (d) message keys MK_n immediately after encrypt/decrypt. Identify any locations where secret key material may remain in memory longer than specified.

Replay protection. In Session.kt, verify that incoming seq values are checked against max_seq_received and that the chain is advanced correctly for gaps per §8.3.1. Is there a max-gap check to prevent DoS via very large seq jumps?

Token transition correctness. Verify that EpochRotation is sent on the OLD send_token, and that the implementation correctly transitions to new tokens with the dual-polling window per spec §8.3.

Deviation summary. List every place where the implementation deviates from the spec, even if the deviation appears benign. Use a table with columns: File, Line(s), Spec Section, Description, Severity.

Now I have all the files needed for a thorough analysis. Let me compile the findings.

***

# Security Review: KMP E2EE Implementation vs. Spec

This is a detailed audit of every cryptographic concern across the eleven implementation files, cross-referenced against the [specification](https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md). Findings are ordered by severity.

***

## 1. HKDF Parameter Correctness

### Routing Token Derivation — Salt Bug (HIGH)

The spec (§4.2, §8.3) defines the routing token salt as a 5-byte compound value:

> `salt = epoch (4 bytes, BE) || direction (1 byte, AliceToBob=0, BobToAlice=1)`

In `Ratchet.kt`, `deriveRoutingToken` uses only a 4-byte epoch as the salt :

```kotlin
val salt = intToBeBytes(epoch)  // only 4 bytes; missing direction byte
val info = INFO_ROUTING_TOKEN.encodeToByteArray() + senderFp + recipientFp
```

The direction byte (0 or 1) is instead folded into the `info` string via `senderFp || recipientFp` ordering. This achieves domain separation — the two tokens *will* differ because `senderFp` and `recipientFp` are swapped for each direction — but it is a deviation from the spec's explicit wire-format requirement of `salt = epoch_be4 || direction_byte`. The resulting token values will be **different from a spec-compliant reference implementation** on the other platform, which would be an interoperability-breaking bug.

### Initial Token Derivation vs. Spec (HIGH)

The spec §4.2 says tokens at epoch 0 use `IKM = SK` and `salt = 0x00000000 || 0x00` (or `0x01`). The implementation `deriveRoutingToken(sk, epoch=0, ...)` in `KeyExchange.kt` (line ~40/90) uses `rootKey` as IKM . However, at epoch 0, `rootKey` is *not* `SK` directly — it is `HKDF(SK, salt=null, info="Where-v1-KeyExchange")[0:32]` (the first 32 bytes of the expanded 96-byte output from `initSession`). The spec derives tokens directly from `SK`, not from a KDF'd root key. This means token values at epoch 0 diverge from spec-defined values.

### `KDF_RK` info String Mismatch (LOW)

The spec §8.3 names the info string for `KDF_RK` as `"Where-v1-RatchetStep"`. `ProtocolConstants.kt` defines `INFO_RATCHET_STEP = "Where-v1-RatchetStep"`  and `Ratchet.kt` uses it correctly . ✅

### `KDF_CK` info String (PASS)

`INFO_MSG_STEP = "Where-v1-MsgStep"` matches spec §8.3 exactly. ✅

### Rotation/Ack Auth Key Info Strings — Wrong Name (LOW)

The spec §8.3 names the EpochRotation auth key info as `"Where-v1-RotationAuth"`. `ProtocolConstants.kt` defines `INFO_EPOCH_ROTATION = "Where-v1-EpochRotation"`  and `Session.kt` uses it in `buildEpochRotationCt` . **"Where-v1-EpochRotation" ≠ "Where-v1-RotationAuth"** — this will cause Alice and Bob to derive different `K_rot` values, making every `EpochRotation` undecryptable. This is a critical cryptographic mismatch.

Similarly, `INFO_RATCHET_ACK = "Where-v1-RatchetAck"` but the spec says `info = "Where-v1-AckAuth"`. **"Where-v1-RatchetAck" ≠ "Where-v1-AckAuth"** — same breakage for `RatchetAck`.

### `K_bundle` Derivation IKM Discrepancy (MEDIUM)

The spec §5.3 says `K_bundle = HKDF(SK, salt=0x00..00, info="Where-v1-BundleAuth")`. The implementation derives `K_bundle` by calling `hkdfSha256(ikm=sk, ...)` in `KeyExchange.kt` line ~52 and ~99  where `sk` is the raw X25519 output — this is **correct** in the `bobProcessQr` / `aliceProcessInit` functions. However, the spec §8.1 table also says `K_bundle = HKDF(SK, salt=0, ...)`. The `salt=null` (defaulting to 32 zero bytes per HKDF RFC 5869) matches `salt=0x00..00`, so this is compliant. ✅

### HKDF Output Lengths (PASS)

All outputs verified:

- Discovery token: 16 bytes ✅
- `K_bundle`, `K_rot`, `K_ack`: 32 bytes each ✅
- `KDF_RK`: 64 bytes → split 32+32 ✅
- `KDF_CK`: 76 bytes → 32+32+12 ✅
- Routing token: 16 bytes ✅

***

## 2. AEAD AAD Correctness

### `EncryptedLocation` AAD (PASS with Observation)

The spec (§8.3 / §9.1) requires:

> `aad = "Where-v1-Location" (18 bytes UTF-8) || version (4 bytes BE) || alice_fp (32) || bob_fp (32) || epoch (4 bytes BE) || seq (8 bytes BE)`

`Session.kt`'s `buildLocationAad` builds :

```kotlin
AAD_PREFIX.encodeToByteArray() +   // "Where-v1-Location"
intToBeBytes(PROTOCOL_VERSION) +   // version=1, BE
senderFp + recipientFp +           // alice_fp + bob_fp
intToBeBytes(epoch) +              // epoch, BE
longToBeBytes(seq)                 // seq, BE
```

This matches the spec. **However**, `Session.encryptLocation` passes the caller-supplied `senderFp`/`recipientFp` directly rather than pulling `state.aliceFp`/`state.bobFp` from session state . The KDoc comment on `encryptLocation` erroneously describes `senderFp` as `SHA-256(sender IK.pub || sender SigIK.pub)` — a holdover from an X3DH design — when the spec defines `alice_fp = SHA-256(EK_A.pub)` (no IK). This is a documentation bug but means a caller mismatch would silently produce a valid but wrong AAD.

### `EpochRotation` AAD — Token Encoding (PASS)

The spec §8.3 requires `aad = alice_fp || bob_fp || routing_token` using **raw binary bytes (32 bytes)**, not base64. In `Session.kt`'s `buildEpochRotationCt` :

```kotlin
val aad = senderFp + recipientFp + routingToken
```

`routingToken` is passed as `ByteArray` throughout the call stack, so it is raw binary. ✅

**However**, the spec says the token is 32 bytes (`routing_token` described as 32 bytes in the AAD context). The actual routing tokens in this implementation are **16 bytes** — derived as `hkdfSha256(..., length=16)` . The spec §9.3 confirms the token itself is 16 bytes in the wire format, but §8.3 states `"raw binary token bytes (32 bytes)"`. This is a spec ambiguity — the 16-byte token is used raw (correct for binary), but if any counterpart code expects 32 bytes in AAD, it will fail.

### `RatchetAck` AAD (PASS)

Same structure as `EpochRotation`. `buildRatchetAckCt` uses `senderFp + recipientFp + routingToken` . ✅

### `PreKeyBundle` HMAC Coverage (PARTIAL DEVIATION)

The spec §8.3 defines:
> `mac = HMAC-SHA-256(K_bundle, v_be4 || send_token || canonical_keys_blob)`

where `canonical_keys_blob = count_be4 || (opk_id_be4 || opk_pub_32bytes) || ...`

`PreKeyBundleOps.signedData` produces :

```kotlin
intToBeBytes(PROTOCOL_VERSION) + token + intToBeBytes(sorted.size) + (id_be4 + pub_32 for each)
```

This matches the spec byte-for-byte. ✅

***

## 3. Nonce Handling

### Location Messages — Deterministic Nonce from `KDF_CK` (PASS)

`KDF_CK` outputs 76 bytes with bytes 64–75 as the nonce . `Session.encryptLocation` uses `step.messageNonce` from the `kdfCk` output, not a random value . ✅

### Control Messages — Nonce Construction (DEVIATION)

The spec §8.3 requires: `nonce = epoch_be4 || 0x00...00` (4 bytes epoch + 8 zero bytes = 12 bytes total).

In `Session.kt`, the nonce for `EpochRotation` and `RatchetAck` is passed in as a parameter (not constructed internally), and `MailboxMessage.kt` shows the `EpochRotationPayload` carries a `nonce: ByteArray` field transmitted on the wire . This means the **nonce is random** (populated by the caller using `randomBytes(12)`) rather than the deterministic `epoch_be4 || zeros(8)` the spec mandates. This is a direct violation of §8.3:

> *"The nonce for `EpochRotation` and `RatchetAck` AEAD is `epoch_be4 || 0x00...00`"*

Using a random nonce is not less secure here (ChaCha20-Poly1305 is nonce-misuse resistant in IETF mode), but it deviates from the spec, wastes 12 wire bytes, and loses the spec's nonce-uniqueness guarantee analysis. It also means the `nonce` field in the payload becomes the canonical source, which is accepted by the decrypt functions without validation.

### Nonce Reuse Risk (PASS)

Because location message nonces are derived from `KDF_CK` (deterministic per chain position) and control message nonces are random 12-byte values, nonce reuse cannot occur in normal operation across message types. Within the location stream, each `(message_key, message_nonce)` pair is unique by construction. ✅

***

## 4. Key Deletion Discipline

### `EK_A.priv` After SK Derivation (PARTIAL)

The spec §4.2 step 5: *"Alice MUST delete `EK_A.priv` immediately"* after `aliceProcessInit`. The implementation in `KeyExchange.aliceProcessInit` stores `myEkPriv = ByteArray(32)` (zeroed) in the returned `SessionState` . However, the zeroing happens when constructing the session, and the **original `aliceEkPriv` parameter is NOT zeroed inside `aliceProcessInit`**. The caller holds a reference to the raw private key; if the caller does not zero it, it leaks. The docstring says *"Alice MUST zero aliceEkPriv immediately after this call"* but the function does not enforce it. This is a dangerous API design — the key deletion responsibility is pushed to the caller with no enforcement.

### `EK_B.priv` After Posting `KeyExchangeInit` (PARTIAL)

In `KeyExchange.bobProcessQr`, `ekB.priv` is used for `sk = x25519(ekB.priv, qr.ekPub)` and then `myEkPriv = ByteArray(32)` is passed to `initSession` . The local variable `ekB.priv` itself is **never explicitly zeroed before `ekB` goes out of scope**. The spec §4.2 says *"Bob deletes `EK_B.priv` immediately after posting the `KeyExchangeInit`"*. In Kotlin/JVM, zeroing relies on GC without guarantees; the `ekB.priv` array reference remains live until GC. This is not as dangerous as a long-lived leak but still deviates from the spec's deletion discipline.

### OPK Private Keys After `dh_out` Computation (NOT ENFORCED)

The spec §5.3 step 5: *"Bob MUST delete the OPK private key immediately after use."* In `Session.bobProcessAliceRotation`, the `bobOpkPriv` parameter is used in `x25519(bobOpkPriv, aliceNewEkPub)` but is **never zeroed** inside the function . The comment in the docstring only mentions zeroing `dhOut`. The OPK private key deletion is left entirely to the caller. This is a spec violation.

### Message Keys `MK_n` After Encrypt/Decrypt (PASS)

In `Session.encryptLocation` and `decryptLocation`, `step.messageKey.fill(0)` and `step.messageNonce.fill(0)` are explicitly called . ✅ In `decryptLocation`, the zeroing also happens in the error path via `try/catch`. ✅

### `dhOut` Zeroing (PASS)

`dhOut.fill(0)` is called in `aliceEpochRotation`, `bobProcessAliceRotation`, `bobProcessOwnRotation`, and `aliceProcessRatchetAck` . ✅

### `SessionState` Serializable with `myEkPriv` (MEDIUM)

`SessionState` is annotated `@Serializable` and includes `myEkPriv` as a serialized field . If the session state is persisted to disk (e.g., via JSON), the current epoch's private key is written out. The spec §5.5 says *"No message keys or chain keys are persisted to disk"* but also acknowledges root keys go to the keychain. Having `myEkPriv` in a `@Serializable` data class increases the risk that it gets inadvertently persisted in plaintext. This is a design concern — the private key should be excluded from serialized state or the `@Serializable` annotation should be on a separate persistence type.

***

## 5. Replay Protection

### `seq` Check Against `max_seq_received` (PASS)

`Session.decryptLocation` enforces `seq > state.recvSeq` and throws on violation :

```kotlin
require(seq > state.recvSeq) { "replay — seq $seq must be greater than state.recvSeq ${state.recvSeq}" }
```

This correctly implements §8.3.1 rule 1. ✅

### Chain Advancement for Gaps (PASS)

`stepsNeeded = seq - state.recvSeq` and the loop calls `kdfCk` exactly `stepsNeeded` times, discarding intermediate keys :

```kotlin
repeat(stepsNeeded.toInt()) {
    step = kdfCk(chainKey)
    chainKey.fill(0)
    chainKey = step!!.newChainKey
}
```

This matches §8.3.1 rule 2. ✅

### Max-Gap DoS Prevention (PASS)

`MAX_DECRYPT_GAP = 1000L` is enforced:

```kotlin
require(stepsNeeded <= MAX_DECRYPT_GAP + 1) { "seq gap ... exceeds maximum ..." }
```

This prevents CPU exhaustion from very large seq jumps (mentioned in the spec as a concern). ✅

### `recvSeq` Reset on Epoch Rotation (PASS)

`bobProcessAliceRotation` sets `recvSeq = 0L` . ✅

***

## 6. Token Transition Correctness

### `EpochRotation` on Old Token (PASS)

The spec §8.3 step 5: *"Alice MUST send the `EpochRotation` message on the old (current) send_token."* The `aliceEpochRotation` function returns a new `SessionState` with updated tokens but does NOT handle the EpochRotation message construction/posting — that is left to the caller in `Session.kt` . The caller must therefore retrieve `state.sendToken` *before* calling `aliceEpochRotation` and post the rotation message on the old token. This is an implicit convention not enforced by the API. A caller who posts on `newState.sendToken` would violate the spec. The function should arguably accept a callback or return the old token explicitly.

### Dual-Polling Window (NOT IMPLEMENTED)

The spec §8.3 requires:
> *"Bob MUST immediately start polling both the old and new `recv_token`. Bob stops polling the old `recv_token` only after ... at least one valid `EncryptedLocation` on the new `recv_token`, or after a safety timeout of `2 * T`."*

There is no implementation of this dual-polling logic in any of the reviewed files. `bobProcessAliceRotation` immediately replaces both `sendToken` and `recvToken` with new values . The old token is discarded and there is no mechanism to track it for continued polling. This is a spec violation for the transition protocol and could cause Bob to miss messages posted to the old token during the overlap window.

***

## 7. Deviation Summary Table

| File | Line(s) | Spec Section | Description | Severity |
| :-- | :-- | :-- | :-- | :-- |
| `ProtocolConstants.kt` | line 7 | §8.3 | `INFO_EPOCH_ROTATION = "Where-v1-EpochRotation"` but spec requires `"Where-v1-RotationAuth"`. Causes `K_rot` mismatch; `EpochRotation` undecryptable by spec-compliant peer. | **Critical** |
| `ProtocolConstants.kt` | line 8 | §8.3 | `INFO_RATCHET_ACK = "Where-v1-RatchetAck"` but spec requires `"Where-v1-AckAuth"`. Causes `K_ack` mismatch; `RatchetAck` undecryptable by spec-compliant peer. | **Critical** |
| `Ratchet.kt` | line 55 | §4.2 / §8.3 | Routing token salt is `epoch_be4` (4 bytes) only; spec requires `epoch_be4 \|\| direction_byte` (5 bytes). Direction folded into `info` via `senderFp`/`recipientFp` ordering. Token values differ from spec; interoperability-breaking. | **High** |
| `KeyExchange.kt` | ~40, ~90 | §4.2 | Initial tokens at epoch 0 use `rootKey` (HKDF'd from SK) as IKM. Spec uses raw `SK` directly as IKM. Token values at epoch 0 differ from spec. | **High** |
| `MailboxMessage.kt` | `EpochRotationPayload`, `RatchetAckPayload` nonce field | §8.3, §9.3 | Control message nonce transmitted on wire as random 12 bytes. Spec mandates deterministic `epoch_be4 \|\| zeros(8)`. Wastes 12 wire bytes; random nonce is not validated. | **Medium** |
| `Session.kt` | `bobProcessAliceRotation` ~170 | §5.3 step 5 | `bobOpkPriv` parameter never zeroed after DH computation. Spec: *"Bob MUST delete the OPK private key immediately after use."* | **Medium** |
| `Types.kt` | `SessionState` class | §5.5 | `myEkPriv` is in `@Serializable` data class. If state is serialized to disk, current epoch private key is exposed. Spec requires EK privs to never be persisted. | **Medium** |
| `KeyExchange.kt` | `aliceProcessInit` ~70 | §4.2 step 5 | `aliceEkPriv` parameter not zeroed inside function; zeroing is caller's responsibility (documented but not enforced). | **Medium** |
| `KeyExchange.kt` | `bobProcessQr` ~40 | §4.2 | `ekB.priv` (local `RawKeyPair.priv`) never explicitly zeroed before going out of scope. | **Medium** |
| `Session.kt` | `aliceEpochRotation` return | §8.3 Token Transition | API does not enforce that `EpochRotation` is sent on the *old* `sendToken`. Old token is not returned; caller must capture it before calling the function. Easy to misuse. | **Medium** |
| `Session.kt` | `bobProcessAliceRotation` | §8.3 Token Transition | Dual-polling window not implemented. Old `recvToken` is immediately discarded. Bob may miss frames in the overlap window. | **Medium** |
| `Session.kt` | `encryptLocation` KDoc | §3.4 / §8.3 | KDoc describes `senderFp` as `SHA-256(IK.pub \|\| SigIK.pub)` — X3DH leftover. Spec defines `alice_fp = SHA-256(EK_A.pub)`. Wrong documentation could cause callers to pass incorrect fingerprints, silently producing wrong AAD. | **Low** |
| `Fingerprint.kt` | `formatSafetyNumber` | §3.4 | Safety number formatted as 12 groups of 5 decimal digits per spec. | **PASS** |
| `Session.kt` | `encryptLocation`, seq starts at 1 | §9.1 | `seq = state.sendSeq + 1` starts at 1 (sendSeq initialised to 0). Spec does not define the first seq value explicitly but §8.3.1 assumes `incoming_seq > current_seq` with `recvSeq` starting at 0, which is consistent. Benign but worth noting. | **Info** |
| `ProtocolConstants.kt` | line 4 | §8.1 | `INFO_SESSION = "Where-v1-Session"` is defined but never used in any reviewed file. Dead constant; possible leftover from refactor. | **Info** |

