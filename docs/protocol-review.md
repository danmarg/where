# Protocol Review: `docs/e2ee-location-sync.md`

**Reviewer:** Claude  
**Date:** 2026-04-11  
**Status:** Draft — for engineering discussion

---

## Critical Issues

### 1. DoS via unbounded chain advancement on forged `seq` (§8.3.1)

The spec mandates advancing the symmetric ratchet `(incoming_seq - current_seq)` times to reach the key, with no upper bound. `seq` is a `uint64`, so an attacker who can POST to a known `send_token` (or a malicious server) can inject a ciphertext with `seq = 2^63`. The recipient will call `KDF_CK` 2^63 times before finally failing AEAD verification — a fatal, silent DoS. This is the single worst bug in the spec.

**Fix:** Cap the maximum chain advancement per message (e.g. `MAX_GAP = 1024`). Frames exceeding the cap are dropped before any HKDF work.

---

### 2. §8.3 "Note on nonces" inverts the security argument

The spec says:

> *"...if keychain state is restored to an earlier epoch ..., the same root key may re-derive the same message key. To eliminate the risk of a (Key, Nonce) collision in such scenarios, this protocol requires deterministic nonces..."*

This reasoning is backwards. If `message_key` is re-derived identically after a state restore, deterministic nonces **guarantee** a `(key, nonce)` collision — which is catastrophic for ChaCha20-Poly1305 (plaintext XOR leak + Poly1305 forgery). Random nonces would *avoid* the collision with overwhelming probability.

The actual defense against state-restore reuse is §5.5's "invalidate session on fresh install / missing state" — not the nonce being deterministic. The rationale in §8.3 should be corrected so an implementer doesn't conclude that deterministic nonces are themselves the mitigation.

---

### 3. Initial ratchet state derivation is undefined

Section 4.2 derives `SK` and `T_AB_0`/`T_BA_0`, and §8.3 specifies `KDF_RK` for **subsequent** DH steps, but there is no normative construction for the initial `root_key`, `send_chain_key`, or `recv_chain_key` from `SK`. Two interoperable clients will diverge unless this is pinned down (e.g. `HKDF(SK, salt=0, info="Where-v1-InitRoot", 96 bytes)` → `root_key || send_CK || recv_CK`). Also unspecified: the initial `epoch` (0 or 1?) and who gets which chain as "send" vs "recv" at bootstrap.

---

### 4. Bidirectional ratchet semantics are ambiguous (§5 vs §8.2)

§5 frames the ratchet as "one-way streaming" (Alice sends, Bob receives), yet §8.2's `SessionState` has both `send_chain_key` and `recv_chain_key`, implying *both* parties broadcast locations (which a friend-sharing app clearly requires). The spec never explains:

- Do Alice→Bob and Bob→Alice have **independent** DH ratchets with independent epoch numbers and independent OPK consumption? Or does a single DH step rotate both directions' chains?
- Section 5.3 only derives `new_send_CK`, not `new_recv_CK`, on epoch rotation. If a single DH step is meant to rotate both, the formula for the recv side is missing.
- If rotations are independent, §8.3's "re-derive *both* routing tokens from the new root key" contradicts having two independent root keys per direction.

This is a substantive design gap, not just wording.

---

## High-Severity Issues

### 5. `PreKeyBundle` has no replay / freshness protection

`mac = HMAC-SHA-256(K_bundle, v || send_token || canonical_keys_blob)` and `K_bundle` is static for the life of the session (`HKDF(SK, ...)`). An honest-but-curious or compromised server can:

- Replay an old `PreKeyBundle` after Bob has deleted those OPK private keys.
- Alice caches OPKs for which Bob has no matching private key, then issues `EpochRotation` using a dead OPK, stalling PCS indefinitely (silent degradation).
- There's no way for Alice to tell a replay from a legitimate top-up.

**Fix:** Bind a monotonic bundle version (`bundle_seq`) and a timestamp into the MAC and have Alice reject any bundle whose `bundle_seq ≤ last_seen`. Also consider rotating `K_bundle` per epoch.

---

### 6. `key_confirmation` does not do what §2.2 claims

§2.2 says key confirmation *"proves that both parties derived the same SK before any location data is shared"*. That's only true against random corruption. Against an active attacker who can intercept `KeyExchangeInit`, the attacker can swap `ek_pub` with their own `EK_M.pub`, recompute `SK' = X25519(EK_M.priv, EK_A.pub)` themselves, and recompute `key_confirmation = HMAC(SK', "Where-v1-Confirm" || EK_A.pub || EK_M.pub)`. Alice verifies successfully and establishes a session with the attacker. §2.3 and §3.4 acknowledge this is TOFU, but §2.2's phrasing should be reworded to avoid the misimpression that key confirmation defeats an active MITM on the discovery channel.

---

### 7. `key_confirmation` uses `SK` directly as the HMAC key

Best practice: derive a dedicated confirmation subkey from `SK` via HKDF with a distinct `info = "Where-v1-Confirm"`, instead of using `SK` as the raw HMAC key. The same principle is already applied to `K_bundle`, `K_rot`, `K_ack`. Consistency (and domain separation) would be improved by applying it here too.

---

### 8. Control-message clock-skew bounds are internally inconsistent

- §2.3: clock skew > **15 minutes** permanently stalls PCS.
- §9.3: recipients MUST reject `EpochRotation`/`RatchetAck` with `ts` outside a **`T + 5 minute`** window.

If `T = 5 min` (the recommended default in §5.3), this becomes a 10-minute window — stricter than §2.3's 15-minute figure. If `T = 10 min` (as in §5.4), it's 15. These should be reconciled explicitly, and the interaction between *sender*'s clock skew and *recipient*'s freshness check should be called out (both clocks contribute to the effective skew).

---

### 9. Dual-polling window violates the constant-rate polling invariant

§7.4.1 mandates polling at a constant rate to prevent timing-based social-graph inference. §8.3's token transition protocol has Bob *double* his polling frequency (old + new `recv_token`) for `2 * T`. That's a distinctive timing signature: the server observes one IP briefly double its polling activity across two tokens, which is exactly the kind of correlation §7.4.1 is meant to suppress. Mitigation options (fold both polls into a single multi-token request, or pre-rotate the token so only one is polled at a time) should at least be discussed.

---

## Medium-Severity Issues

### 10. `constant-time response` for Redis-backed mailboxes is very hard

§10.2 requires constant-time lookup for "hit" vs "miss". Redis `LPOP`/`GET` on a missing key is measurably faster than on a populated key, and JSON serialization cost grows with queue length. The spec mandates this invariant but gives no implementation guidance. At minimum it should say "add synthetic delay or query-and-discard with fixed-size latency floor" so implementers don't just shrug and let timing leak.

---

### 11. Safety Number: no domain separation, unclear truncation

`SHA-256(lower_EK.pub || higher_EK.pub)` — no label, so this hash could collide with hashes used elsewhere in the protocol (e.g., fingerprints `SHA-256(EK.pub)` are a strict subset of possible inputs). Also, §3.4 says "40-character hex string" (160 bits), but doesn't specify *which* 160 bits of the 256-bit digest. Suggest:

```
safety_number = HKDF(SHA-256(sorted_EKs), info="Where-v1-SafetyNumber")[0:20]
```

and specify a canonical display format (Signal-style grouped digits would aid usability).

---

### 12. `fingerprint` field in the QR is never verified

§4.2's QR payload includes `fingerprint = hex(SHA-256(EK_A.pub)[0:10])`, but no later section verifies it against `EK_A.pub`. It's either (a) redundant, (b) meant as a visual sanity-check for the user, or (c) a leftover from an earlier design. Clarify or remove. If kept, 80 bits is on the short side for any real authentication purpose.

---

### 13. Mandatory 512-byte padding conflicts with `PreKeyBundle` size

§7.4 mandates padding all payloads to 512 bytes. A bundle with 20 OPKs at 32 bytes each is already 640 bytes of raw key material before overhead. How are oversized payloads handled? Options:

- Pad *up* to the next multiple of 512? Then a 1-OPK bundle vs a 20-OPK bundle are trivially distinguishable by size.
- Split across messages? Requires sequencing.

The spec needs to either commit to a fixed bundle size or specify a padding bucket scheme (`{512, 1024, 2048}` rounded up).

---

### 14. Location AAD doesn't bind the `send_token` (inconsistent with §8.3)

§6.2 has Alice encrypt separately per friend. The AAD is:

```
"Where-v1-Location" || v || alice_fp || bob_fp || epoch || seq
```

`bob_fp` is the intended recipient's fingerprint, which is good. But §8.3 binds control messages to the routing token in their AAD specifically to prevent cross-mailbox replay. Explicitly including `send_token` in location AAD would be low-cost and consistent.

---

### 15. No rate limiting / mailbox flooding protection

Only the opacity of the routing token prevents arbitrary parties from `POST`ing garbage to a mailbox. Once a token leaks (device compromise, backup restore), anyone can stuff the mailbox until the next DH rotation (`T` minutes away by default). Each poll forces the victim to fetch and AEAD-verify every item. §2.3 lists DoS as out-of-scope, but per-token write rate limits and backlog caps would be cheap server-side protections worth adding to §10 as a SHOULD.

---

### 16. `recv_chain_key` in `SessionState` appears to have no consumer

The only Bob→Alice messages are `PreKeyBundle` (MACed with `K_bundle`, not chained) and `RatchetAck` (AEAD under `K_ack`, not chained). Neither uses `recv_chain_key`. Either the spec is missing a definition of when `recv_chain_key` advances, or this field is dead — which would also confirm Issue #4 as a genuine design gap rather than a reading error.

---

## Low-Severity / Nits

### 17. Epoch-in-key-and-nonce is redundant

`K_rot` / `K_ack` are already uniquely derived per epoch (epoch is the HKDF salt). Folding the epoch into both the key AND the nonce is belt-and-suspenders but harmless. The §9.3 implementation note that "Implementations MUST NOT reuse an epoch number with the same key" is tautological since each epoch derives a fresh key.

---

### 18. Wire format token encoding is unspecified

`"token": "<send_token_T>"` — is this hex, base64, base64url? §4.2 uses `hex(discovery_token_A)` for discovery, but other tokens' encoding is implicit. Pin it down.

---

### 19. `seq` canonicalization for AAD needs a MUST

§9.1 notes `seq` is a decimal string for JS compat, but the AAD uses an 8-byte big-endian `uint64`. Implementations must parse the decimal string as `uint64` and re-serialize as 8-byte BE before hashing. An explicit MUST is warranted: *"parse as uint64, re-serialize as 8-byte BE before AAD computation; never hash the decimal string."*

---

### 20. Padding location (pre- vs post-AEAD) is unspecified

§7.4 says pad to 512 bytes but doesn't say *where*: inside the plaintext before AEAD (so it's authenticated) or appended after AEAD (trivially forgeable/strippable). It should be inside the plaintext.

---

### 21. StrongBox is not universally available on Android

§5.5 says `setIsStrongBoxBacked(true)` — StrongBox requires a secure element and is absent on many devices. A graceful fallback path should be specified (e.g., fall back to TEE-backed Keystore with `allowBackup=false`).

---

### 22. JVM memory-zeroization caveat is missing

§5.5 claims `Arrays.fill(key, 0)` is sufficient on Android. On the JVM, arrays can be relocated by GC, leaving stale copies in memory. This is a known limitation; either note the caveat or recommend off-heap / `DirectByteBuffer` / Conscrypt's `SecretKey` wrappers.

---

### 23. `KeyExchangeInit.token` field is redundant

Bob includes `T_AB_0` in his `KeyExchangeInit`, but Alice re-derives it herself from `SK` anyway (§4.2 step 4). Recommend removing the field or explicitly mandating that Alice MUST verify it matches her derived value.

---

### 24. OPK depletion degrades PCS silently

§5.3.1: "If Alice runs out of cached OPKs, the DH ratchet stalls." This is a silent PCS degradation. After missing 2–3 epoch boundaries the app should surface an indicator to the user, otherwise the threat model's claim of "per-epoch PCS" becomes misleading in practice.

---

### 25. "Optional" `RatchetAck` is depended on by the 7-day re-pair timeout

§5.3 says `RatchetAck` is "optional for acknowledgment only" but §5.3.1's re-pair trigger is: "If Alice has not received any valid `EncryptedLocation` or `RatchetAck` on the new token after 7 days...". If Bob is receive-only (never shares his own location), Alice has no signal at all and the session silently expires. Worth clarifying whether `RatchetAck` is effectively mandatory in asymmetric sharing scenarios.

---

### 26. Section title mismatch between TOC and body

§5 is titled "Ratchet Design for **Streaming Location Data**" in the TOC but "Ratchet Design for **One-Way Streaming Location Data**" in the body heading.

---

## Summary Table

| # | Severity | Location | Issue |
|---|---|---|---|
| 1 | **Critical** | §8.3.1 | Unbounded `seq`-gap lets a single message trigger ~2^63 HKDF calls |
| 2 | **Critical** | §8.3 Note on nonces | Deterministic-nonce argument is logically inverted |
| 3 | **Critical** | §4.2 / §8.3 | Initial `root_key`, send/recv `CK`, and epoch value are undefined |
| 4 | **Critical** | §5 vs §8.2 | One-way vs bidirectional ratchet semantics contradict |
| 5 | High | §5.3 / §9.3 | `PreKeyBundle` replay is unconstrained (no version/timestamp) |
| 6 | High | §2.2 | Key-confirmation claim overstates MITM resistance |
| 7 | High | §4.2 | `SK` used directly as HMAC key instead of derived subkey |
| 8 | High | §2.3 vs §9.3 | Clock-skew windows contradict (15 min vs `T + 5 min`) |
| 9 | High | §7.4.1 vs §8.3 | Dual-poll window violates constant-rate invariant |
| 10 | Medium | §10.2 | Constant-time mailbox invariant unrealistic on Redis without guidance |
| 11 | Medium | §3.4 | Safety Number lacks domain separation and truncation spec |
| 12 | Medium | §4.2 | QR `fingerprint` field is never verified |
| 13 | Medium | §7.4 | Mandatory 512-byte padding conflicts with `PreKeyBundle` size |
| 14 | Medium | §6.2 / §9.1 | Location AAD doesn't bind `send_token` (inconsistent with §8.3) |
| 15 | Medium | §10 | No mailbox-level rate limiting or backlog cap |
| 16 | Medium | §8.2 | `recv_chain_key` is defined but appears to have no consumer |
| 17 | Low | §9.3 | Epoch-in-key-and-nonce is redundant |
| 18 | Low | §9.1 | Token encoding on the wire unspecified |
| 19 | Low | §9.1 | `seq` canonicalization for AAD needs an explicit MUST |
| 20 | Low | §7.4 | Padding location (pre- or post-AEAD) unspecified |
| 21 | Low | §5.5 | StrongBox not universally available on Android |
| 22 | Low | §5.5 | JVM memory-zeroization caveat missing |
| 23 | Low | §4.2 | `KeyExchangeInit.token` field is redundant |
| 24 | Low | §5.3.1 | OPK depletion degrades PCS silently with no user signal |
| 25 | Low | §5.3 | "Optional" `RatchetAck` is required for 7-day re-pair timeout |
| 26 | Nit | §5 | TOC title vs body heading mismatch |
