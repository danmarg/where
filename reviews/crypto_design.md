<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are a cryptography expert reviewing the design of an end-to-end encrypted location sharing protocol. Read the full protocol design document at:

[https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md](https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md)

Also read the state machine doc at:
[https://github.com/danmarg/where/blob/main/docs/state_machine.md](https://github.com/danmarg/where/blob/main/docs/state_machine.md)

Review the protocol design for the following, producing a detailed written analysis with specific section citations from the doc:

Threat model completeness. Does §2 accurately enumerate attacker capabilities? Are the "What this does NOT protect" admissions accurate and complete? Are any realistic threats missing (e.g. malicious server actively forging messages, clock manipulation attacks on the timestamp window in §9.3)?

Key exchange correctness. In §4, evaluate whether the TOFU-based bootstrap (no long-term identity keys, ephemeral-only) is sound. Is the key confirmation MAC (HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)) sufficient to detect all relevant MITM substitutions? Is the discovery token derivation (HKDF(EK_A.pub, salt=0, info="Where-v1-Discovery")) safe — note that IKM = EK_A.pub (a public key, not a secret) is used as HKDF input material.

Ratchet design soundness. In §5, evaluate the hybrid symmetric/DH ratchet adapted for one-way streaming. Specifically: (a) Is per-message forward secrecy actually achieved given the KDF_CK construction in §8.3? (b) Is the OPK-based PCS strategy (Strategy 3) correct? In particular, evaluate whether the K_rot key derivation (HKDF(pre_rotation_root_key, salt=epoch_be4, info="Where-v1-RotationAuth")) appropriately isolates epoch-rotation authentication from the data channel. (c) Evaluate the fallback behavior when Alice exhausts her OPK cache — does this create a detectable pattern for the server?

Routing token design. In §4.2, tokens are derived as HKDF(SK, salt=epoch||direction, info="Where-v1-RoutingToken"||alice_fp||bob_fp). Evaluate whether the token construction is domain-separated enough from other HKDF usages in the protocol, and whether token rotation on epoch boundary (§8.3 KDF_RK) is correctly specified (old vs. new token transition protocol).

AEAD construction review. For EncryptedLocation (§8.3), EpochRotation, RatchetAck, and PreKeyBundle (§9.3), evaluate: (a) Is the AAD for each message type sufficient to prevent cross-context replay? (b) Is the nonce construction safe — in particular the deterministic nonce for location frames derived via KDF_CK, and the epoch_be4 || zeros8 nonce for control messages?

Gap/skipped-message handling. §8.3.1 requires advancing the chain (incoming_seq - current_seq) steps for dropped messages. Evaluate whether this creates any vulnerabilities (e.g. DoS via artificially high seq numbers) and whether the lack of a skipped-message-key buffer is acceptable.

Open questions assessment. Comment on §12 open questions — in particular the absence of post-quantum cryptography and multi-device support — and assess whether any of the deferred items represent current security gaps rather than just future improvements.
For each issue found, classify it as: Critical (breaks confidentiality/authentication), Major (meaningful security degradation), Minor (hardening recommendation), or Note (design observation).

I have fully read both documents. Here is my complete security analysis.

***

# Security Review: *Where* E2EE Location Sync Protocol


***

## 1. Threat Model Completeness (§2)

### 1.1 Missing Attacker: Active/Forging Server

**Classification: Major**

§2.1 distinguishes "compromised server" from "honest-but-curious server," but neither attacker class explicitly covers a server that *actively forges or injects messages* . The document states the honest-but-curious server "does not forge or modify ciphertexts," yet the compromised server description stops at "has full access to the server process, memory, database." No attacker row covers an actively malicious server that:

- Replays stale `EpochRotation` messages to old tokens to stall Bob's ratchet advancement
- Injects crafted `PreKeyBundle` messages (with substituted OPK public keys) into Bob's send channel before Alice reads them — a subtler attack than classical MITM since the server *is* the channel
- Silently drops or delays `RatchetAck` to exhaust Alice's OPK cache (a DoS on PCS, per the state machine doc §4 discussion of cache depletion)

The `PreKeyBundle` MAC (`HMAC-SHA-256(K_bundle, v||send_token||canonical_keys_blob)`) is specifically designed to stop key substitution , but the threat is never named in §2.1. Without naming it, implementers may overlook it.

### 1.2 Clock Manipulation Attack on Timestamp Windows

**Classification: Major**

§9.3 mandates that recipients reject any `EpochRotation` or `RatchetAck` whose decrypted `ts` falls outside a `T + 5 minute` clock-skew grace window relative to the recipient's local clock . This is a cryptographically enforced freshness check, but the threat model in §2 contains **no mention of clock manipulation**. An attacker who can skew the victim's system clock (NTP poisoning, rogue cellular time broadcasts, GPS spoofing) by more than `T + 5 minutes` can:

- Cause Alice to reject all future `EpochRotation` messages as stale, permanently stalling the DH ratchet
- Or cause Bob to accept a replayed historical `EpochRotation` at the boundary of the tolerance window

The `T` default is 5–10 minutes per §5.3 . Combined with a `+5 minute` grace, the entire validity window is at most ~15 minutes — a very narrow target, but one entirely absent from the "What this does NOT protect against" (§2.3) list.

### 1.3 Incomplete Admission: TOFU Impersonation

**Minor (admission partially exists, but understated)**

§2.3 acknowledges TOFU impersonation via QR substitution . However, it omits a closely related attack: **relay interception of Option B (link sharing)**. SMS links are sent through a carrier's infrastructure; iMessage links through Apple's servers. An attacker with access to those channels (law enforcement, carrier, or national-level adversary) can substitute the `ek_pub` parameter without any QR camera being involved. The document encourages Safety Number verification for Option B (§4.3) but does not name this as a separate threat row in §2.1.

### 1.4 Missing: State Rollback / Backup Restoration

**Note (§5.5 discusses mitigations but not as a threat model entry)**

§5.5 discusses keychain backup risk at length , yet §2.1 has no attacker row for "adversary who restores a backup snapshot to replay location frames." Adding it would round-trip the threat model.

***

## 2. Key Exchange Correctness (§4)

### 2.1 TOFU-Only Bootstrap — Soundness

**Classification: Note (design choice, with known residual)**

The TOFU-only design (no `IK`, no `SigIK`, no PKI) is a deliberate choice well-justified by §3.1 : because re-pairing on device loss is mandatory anyway, long-term keys provide no continuity benefit and introduce long-term exposure. The design is **sound within its stated scope**. The residual risk — a substituted QR establishing a session with an attacker — is correctly characterized in §2.3. The Safety Number mechanism is a correct mitigation (it produces `SHA-256(lower||higher)` of the two bootstrap public keys).

One hardening gap: the Safety Number is computed as `SHA-256(lower_EK.pub || higher_EK.pub)` . This is correct for collision-resistance but does not commit to the *direction* of the exchange. That is, the safety number for `(EK_A, EK_B)` is identical whether Alice holds `EK_A.priv` or `EK_B.priv`. In practice this is a non-issue since Alice always holds `EK_A.priv` and the roles are asymmetric, but documenting the role binding would be cleaner.

### 2.2 Key Confirmation MAC Sufficiency

**Classification: Minor**

```
key_confirmation = HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
```

This MAC is computed by Bob and verified by Alice . It proves:

- Bob holds a value consistent with `SK = X25519(EK_B.priv, EK_A.pub)` (i.e., he possesses `EK_B.priv`)
- Neither `EK_A.pub` nor `EK_B.pub` was tampered with in transit after Bob computed it

What it does **not** prove: it does not authenticate to *Bob* that Alice also computed the same `SK`. Bob sends the MAC; Alice verifies it. Alice learns Bob derived the correct `SK`. But Bob has no confirmation from Alice's side. A malicious actor who relayed Bob's `KeyExchangeInit` to Alice without modification would pass Alice's verification, while potentially feeding different content to Bob. In practice, since the `KeyExchangeInit` travels from Bob → server → Alice and Bob derives the MAC over `EK_A.pub` he read from the QR, this is bounded by the TOFU trust. A **bidirectional key confirmation** (Alice also sending a MAC back to Bob) would be strictly stronger. Currently Bob must rely solely on the `EpochRotation` reply as implicit confirmation Alice holds the session.

### 2.3 Discovery Token Construction

**Classification: Major**

```
discovery_token_A = HKDF-SHA-256(IKM = Alice.EK_A.pub, salt = 0x00*32, info = "Where-v1-Discovery")[0:16]
```

**The HKDF IKM is a public key, not a secret.** This is a meaningful cryptographic misuse . HKDF is designed to extract entropy from a high-entropy secret input and expand it into keying material. Using a public key (which is by definition a value the protocol intends to share) as IKM produces a discovery token that is computable by *anyone who sees the QR code* — including an attacker who briefly observed the QR, a bystander who photographed it, or a server-side network observer who sees Bob's GET request body.

The concrete harm: the discovery token is supposed to be a rendezvous identifier Bob uses to post `KeyExchangeInit`. If the token is derivable from the public key, and the public key appears in the QR, then the "secret" rendezvous point is only as private as the QR itself. For Option A (in-person QR) this is acceptable — you intend Bob to derive it. The problem is **the server can also derive it** if it ever observes `EK_A.pub` (e.g., in Bob's `KeyExchangeInit` message, which carries `ek_pub` in cleartext per §9.3). A server that stores Bob's `KeyExchangeInit` can retroactively map `EK_A.pub → discovery_token_A` and link the ephemeral discovery-phase activity to the subsequent session activity.

The fix is to use `EK_A.priv` (or a fresh random 32-byte value committed to in the QR) as HKDF IKM, ensuring the discovery token is truly unguessable by anyone who only sees the public side.

### 2.4 Routing Token Domain Separation from Discovery Token

**Classification: Minor**

Both discovery token and routing tokens use `HKDF-SHA-256` with distinct `info` strings (`"Where-v1-Discovery"` vs `"Where-v1-RoutingToken"`), so collision between them is prevented. However, the discovery token uses `IKM = EK_A.pub` while routing tokens use `IKM = SK` — a different secret entirely. Domain separation within routing tokens is achieved by the `info_token = "Where-v1-RoutingToken" || alice_fp || bob_fp` construction, which binds each token to a specific session pair . This is correct. The only concern is that `alice_fp = SHA-256(EK_A.pub)` is itself derivable from the public key, so the domain separation does not add secrecy — it adds binding, which is the correct purpose.

***

## 3. Ratchet Design Soundness (§5)

### 3.1 Per-Message Forward Secrecy via KDF_CK

**Classification: Note (correct, with an implementation subtlety)**

```
(new_chain_key || message_key || message_nonce) = HKDF-SHA-256(ikm=CK, salt=absent, info="Where-v1-MsgStep")[0:76]
```

Per-message FS is correctly achieved : advancing the chain replaces `CK` with `new_chain_key` and the prior `CK` is immediately deleted per §5.5. If `CK_n` is exposed, messages 0 through n-1 are protected. The key deletion policy in §5.5 is commendably detailed.

One concern: `HKDF-SHA-256` with `salt = <absent>` (the HKDF "no salt" case) causes the RFC 5869 Extract step to use `HMAC(key=0x00*32, data=IKM)`. This is cryptographically sound (the zero-salt case is specifically addressed in RFC 5869 §3.3), but using the current chain key itself as a constant salt or an explicit counter-based input would better align with standard KDF chain constructions (e.g., Signal uses `HMAC-SHA-256(CK, 0x01)` for chain step). The current construction, while not broken, ties both the next chain key and the message key to the same CK with no distinguishing constant other than byte position in the output — which is fine given HKDF's PRF properties but less idiomatic.

### 3.2 OPK-Based PCS (Strategy 3) Correctness

**Classification: Note/Minor**

The K_rot derivation:

```
K_rot = HKDF(pre_rotation_root_key, salt=epoch_be4, info="Where-v1-RotationAuth")[0:32]
```

This correctly isolates epoch-rotation authentication from the data channel  because `K_rot` uses the **pre-rotation** root key while `K_ack` uses the **post-rotation** root key — the two are cryptographically separated by the DH ratchet step. A party who only possesses the post-rotation root key cannot forge an `EpochRotation` from a past epoch. This is correct.

One subtlety: `K_rot` is derived from the pre-rotation root key, but `K_bundle = HKDF(SK, ...)` is derived from the *initial* bootstrap `SK` and cached forever . This means `K_bundle` does not rotate with epochs. A compromise of the initial `SK` would allow an attacker to forge `PreKeyBundle` messages into perpetuity, even after many epoch rotations have occurred. Since `SK` is never transmitted (it is ephemeral DH output deleted after bootstrap) this is not a practical risk, but it creates an architectural asymmetry: `K_rot` enjoys PCS because it derives from the evolving root key; `K_bundle` does not. **Recommendation:** Derive `K_bundle` from the current root key and refresh it at each epoch rotation.

### 3.3 OPK Cache Exhaustion — Detectability Pattern

**Classification: Minor**

When Alice exhausts her OPK cache, she falls back to pure symmetric ratchet — no DH epoch advancement . The state machine document confirms this: the cache starts at 10 OPKs, and without `RatchetAck` responses (which carry new bundles implicitly by triggering Bob to post a new `PreKeyBundle`), Alice's cache drains in ~42 hours at heartbeat rate .

The detectability concern is twofold:

1. **Traffic pattern**: When Alice has OPKs, she emits one `EpochRotation` message every `T` minutes on the old token, followed by subsequent location frames on the new token. When the cache is exhausted, `EpochRotation` messages cease entirely. A server observing the token activity can distinguish "actively rotating" from "OPK-starved" sessions by the absence of epoch transitions — revealing the state of Alice's PCS health.
2. **Token staleness**: With no epoch rotation, the routing token never changes. Long-duration sessions on a static token are more easily correlated over time (IP analysis across multiple polling sessions). §8.3 notes tokens rotate with every epoch , so OPK depletion removes this correlation barrier.

Neither is catastrophically exploitable, but both represent meaningful metadata leakage the threat model does not address.

***

## 4. Routing Token Design (§4.2, §8.3)

### 4.1 Domain Separation

**Classification: Note (correct)**

```
T_AB_0 = HKDF-SHA-256(IKM=SK, salt=epoch_be4||direction, info="Where-v1-RoutingToken"||alice_fp||bob_fp)[0:16]
```

The use of `info = "Where-v1-RoutingToken" || alice_fp || bob_fp` as a session-binding label and `salt = epoch_be4 || direction` as the varying input creates output that is:

- Distinct from all other HKDF usages by the `"Where-v1-RoutingToken"` prefix
- Distinct per session by `alice_fp || bob_fp`
- Distinct per epoch and direction by the salt

All other HKDF usages in the protocol use fully distinct `info` strings (`"Where-v1-RatchetStep"`, `"Where-v1-MsgStep"`, `"Where-v1-RotationAuth"`, `"Where-v1-AckAuth"`, `"Where-v1-BundleAuth"`, `"Where-v1-Discovery"`). Domain separation is correctly implemented.

### 4.2 Token Transition Protocol Specification Gap

**Classification: Major**

§8.3 specifies the token transition protocol in detail, but has an important race condition gap . Consider the sequence:

1. Alice sends `EpochRotation` on old token → starts posting on new token
2. Bob polls old token, receives `EpochRotation`, derives new token
3. Bob polls old token again (as required for `2*T` continuation) and receives `EncryptedLocation` frames that Alice posted on the **new** token — which are absent from the old token

The doc states Bob MUST continue polling both old and new tokens during the `2*T` window and MUST buffer up to 64 frames received on new token before processing `EpochRotation` . However, the protocol does not define what happens when:

- The `EpochRotation` message is **permanently lost** (server TTL expired, e.g., 30–60 minutes per §10.2). Bob would receive frames on the new token he cannot decrypt, buffer up to 64 of them, then discard them after `2*T` — leaving Bob's session irrecoverably desynchronized. There is no re-keying or re-sync mechanism short of a full re-pair. This is a **session liveness** risk, not a confidentiality risk, but it is undocumented.

***

## 5. AEAD Construction Review (§8.3, §9.3)

### 5.1 AAD Sufficiency for Location Frames

**Classification: Note (correct, one observation)**

```
aad = "Where-v1-Location" || version(4B) || alice_fp(32B) || bob_fp(32B) || epoch(4B) || seq(8B)
```

This AAD is well-constructed : the type prefix prevents cross-type replay; `alice_fp || bob_fp` binds to the session; `epoch || seq` prevents cross-epoch or cross-position replay. **One observation**: `version` is 4 bytes but currently always `1`. If a future version changes the AEAD scheme, the version field would prevent cross-version reuse. This is forward-looking hygiene, correctly included.

### 5.2 AAD for EpochRotation and RatchetAck

**Classification: Minor**

```
aad = alice_fp || bob_fp || routing_token
```

The AAD for `EpochRotation` and `RatchetAck` does not include a **message type tag** . While cross-type confusion between these two messages is unlikely given different keys (`K_rot` vs `K_ack`), best practice is to include an explicit type discriminator in every AEAD AAD. If a future message type were introduced that reused the same key for a different purpose, the missing type tag would be a vulnerability. **Recommendation:** Prepend `"Where-v1-EpochRotation"` or `"Where-v1-RatchetAck"` to the AAD.

Also, `EpochRotation` AAD binds `routing_token` (the old send token), but does not bind `epoch` (despite the outer unencrypted envelope carrying it). A server could swap the `epoch` field in the outer envelope while the AEAD-protected inner payload carries `epoch` in the plaintext — leading to a parsing discrepancy. Making `epoch` part of the AAD would close this.

### 5.3 Deterministic Nonce for Location Frames

**Classification: Note (correct, with backup risk)**

The deterministic `message_nonce` derived via `KDF_CK` is explicitly justified in §8.3 : because `message_key` is unique per step, a (Key, Nonce) collision cannot occur in normal operation. The protocol correctly mandates deterministic nonces over random nonces precisely because backup restoration could re-derive the same key. This reasoning is sound.

### 5.4 Control Message Nonces — Epoch Reuse Risk

**Classification: Minor**

```
nonce = epoch_be4 || 0x00...00   (12 bytes)
```

For `EpochRotation` and `RatchetAck`, the nonce is the epoch number padded with zeros . The protocol states "at most one `EpochRotation` per epoch" — so under `K_rot`, nonce uniqueness holds. However:

- `K_rot` is derived from `(pre_rotation_root_key, epoch_be4)`. If epoch rollback occurs (e.g., via backup restoration), epoch `N` would reuse the same `(K_rot, nonce)` pair with potentially different plaintext — a direct nonce reuse under the same key. The §8.3 note warns about backup restoration for location frames and requires deterministic nonces; this same warning is not applied to control messages. Given the Secure Enclave protections in §5.5, this is mitigated but not explicitly closed for control messages.

***

## 6. Gap / Skipped-Message Handling (§8.3.1)

### 6.1 DoS via Artificially High Sequence Numbers

**Classification: Major**

§8.3.1 requires:

> If `incoming_seq > current_seq + 1`, call `KDF_CK` exactly `(incoming_seq - current_seq)` times

There is no stated maximum on `(incoming_seq - current_seq)`. Since `seq` is a `uint64`, a malicious server could deliver a single message with `seq = 2^63` to a fresh session. The receiver would invoke `KDF_CK` 2^63 times — a trivial denial-of-service, as each `KDF_CK` call is an HKDF expansion (computationally non-trivial). The document mentions `uint64` overflow at `UINT64_MAX` triggers re-keying , but does not bound the forward-skip distance. The protocol requires authentication of the seq field *inside* the AEAD, but replay rejection only drops frames with `seq ≤ max_seq_received` — a crafted frame with a valid-looking future seq and an invalid ciphertext would still trigger the chain-advance loop before the AEAD tag is checked.

**Critical fix**: Bound the maximum forward skip (e.g., 1000 steps; equivalent to ~8 minutes of missed frames). Reject any frame that would require advancing more than `MAX_SKIP` steps, and AEAD-verify before chain advancement.

### 6.2 Absence of Skipped-Message-Key Buffer

**Classification: Note (acknowledged design choice)**

§8.3.1 explicitly acknowledges that out-of-order frames with `seq < max_seq_received` are silently dropped . This is an acceptable tradeoff given the streaming nature of location data (stale location frames have diminishing value), but it means any packet reordering by the network causes permanent frame loss. The doc notes this may be extended in the future, which is the right posture. The lack of a key buffer is not a security problem — it is a reliability tradeoff.

***

## 7. Open Questions Assessment (§12)

### 7.1 Post-Quantum Cryptography — Current Gap

**Classification: Major (deferred, but represents a current vulnerability)**

All DH operations use X25519 . The protocol correctly acknowledges this in §2.3 ("Quantum adversaries") and §12. However, the framing as "future work" understates the risk: the **harvest-now-decrypt-later** attack is live today. Any ciphertext the server stores now — and the server holds encrypted location payloads in its mailbox TTL buffer — can be decrypted by a future quantum adversary. For a real-time location app, this may seem low-stakes (old location data is rarely sensitive), but `EpochRotation` messages containing `new_ek_pub` and the DH key exchange itself could be harvested and later broken to reconstruct the session key hierarchy. The protocol's ephemeral-only design limits historical exposure, but does not eliminate it. PQ hybrid key encapsulation (e.g., X25519+ML-KEM-768) should be on the near-term roadmap, not deferred indefinitely.

### 7.2 Multi-Device Support — Current Gap

**Classification: Major**

§3.3 and §12 defer multi-device support . The state machine doc confirms identity is single-device . The current design means a user who installs the app on a second device gets a completely separate identity — their tablet and phone appear as separate people to friends, each requiring individual re-pairing. This is a significant usability gap that will drive users toward workarounds (e.g., sharing the same account credentials across devices), which would break the single-session-per-device assumption and introduce security risks the protocol does not consider.

### 7.3 Session Expiry (§12 Item 5) — Operational Gap

**Classification: Note**

The exponential backoff and "no recent location" indicator described in §12 item 5  are sensible operational measures. The one unexplored angle: Bob polls indefinitely against a session Alice has silently abandoned. With no session invalidation message (Bob has no way to know Alice uninstalled), Bob's client maintains a live session object with a stale root key indefinitely. A session tombstone message (encrypted under the current root key, instructing the recipient to purge the session) would be a clean addition.

***

## Summary of Findings

| \# | Section | Issue | Severity |
| :-- | :-- | :-- | :-- |
| 1 | §2.1 | Active/forging server not named in threat model | **Major** |
| 2 | §9.3 | Clock manipulation attacks on timestamp window not in §2.3 | **Major** |
| 3 | §4.2 | Discovery token uses public key as HKDF IKM (not a secret) | **Major** |
| 4 | §8.3.1 | No bound on forward-skip distance; DoS via seq injection before AEAD check | **Major** |
| 5 | §12 | PQ omission: harvest-now-decrypt-later is a live risk | **Major** |
| 6 | §8.3 | Token transition: permanent loss of EpochRotation causes irrecoverable desync | **Major** |
| 7 | §4.2 | Key confirmation is unidirectional; Bob has no confirmation from Alice | **Minor** |
| 8 | §5.3 | K_bundle does not rotate with epochs; static from bootstrap SK | **Minor** |
| 9 | §5.3 | OPK depletion creates detectable traffic pattern on token staleness | **Minor** |
| 10 | §8.3 | EpochRotation/RatchetAck AAD missing type tag and epoch binding | **Minor** |
| 11 | §9.3 | Control message epoch nonce reuse risk under backup restoration | **Minor** |
| 12 | §12 | Multi-device gap drives insecure workarounds | **Major** |
| 13 | §5.1 | KDF_CK uses absent salt; non-idiomatic but not broken | **Note** |
| 14 | §3.1 | Safety Number does not commit to key roles | **Note** |
| 15 | §4.1 | Domain separation of HKDF usages is correct and complete | **Note** |
| 16 | §8.3.1 | No skipped-message-key buffer is an acknowledged reliability tradeoff | **Note** |
| 17 | §12 | No session tombstone for clean session teardown | **Note** |

