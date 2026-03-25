# E2EE Location Sync — Implementer's Checklist

**Status:** Ready to implement.  
**Date:** 2026-03-25  
**References:** [PR #2](https://github.com/danmarg/where/pull/2)

Split into client-crypto, client-app, and server.

## 1. Client Crypto (per platform)

### Key material
- Generate and persist per-device keys:
  - `IK` = X25519 keypair (DH only), stored in secure storage.
  - `SigIK` = Ed25519 keypair (signing only), **independent seed from `IK`**. No derivation.
- For each friend, maintain `SessionState`: `root_key`, `send_chain_key`, `recv_chain_key`, `routing_token`, `send_seq`, `recv_seq`, `epoch`, `my_ek_priv`, `my_ek_pub`, `their_ek_pub`.

### Key exchange
- Generate fresh ephemeral keypair `EK_A` for Alice's QR code.
- **Alice's QR payload MUST include a `sig` field:** Ed25519 signature over `(ik_pub_bytes || ek_pub_bytes || sig_pub_bytes)` using `Alice.SigIK.priv`. This closes the asymmetric authentication gap — without it, an attacker who replaces Alice's QR before Bob scans it can impersonate Alice to Bob (see §2.3 and §4.2).
- Parse QR/link for `ik_pub`, `ek_pub` (Alice's ephemeral), `sig_pub`, `suggested_name`, `fingerprint`, `sig`.
- **Bob MUST verify Alice's QR `sig` over `(ik_pub || ek_pub || sig_pub)` using `sig_pub` before deriving any key material.** Abort on failure.
- On `KeyExchangeInit`:
  - Verify Ed25519 sig over `(ik_pub || ek_pub || sig_pub)`; abort on failure.
  - Compute:
    - `DH1 = X25519(Bob.EK_B.priv, Alice.IK.pub)`
    - `DH2 = X25519(Bob.IK.priv, Alice.EK_A.pub)`
    - `DH3 = X25519(Bob.EK_B.priv, Alice.EK_A.pub)`
    - `DH4 = X25519(Bob.IK.priv, Alice.IK.pub)`
  - `SK = HKDF-SHA-256(DH1 || DH2 || DH3 || DH4, info="Where-v1-KeyExchange")`.
  - `T_AB_0 = HKDF-SHA-256(SK, salt=0x00000000, info="Where-v1-RoutingToken")[0:16]`. (salt = epoch=0 as 4-byte big-endian uint32).
  - Initialize session state; **Alice MUST delete EK_A.priv immediately after derivation**.

### Ratchet
- `KDF_RK`: HKDF-SHA-256 for DH ratchet steps.
- `KDF_CK`: HMAC-SHA-256 for chain advancement + HKDF-SHA-256 for key/nonce derivation. **Deterministic nonces are mandatory**:
  - `new_chain_key = HMAC-SHA-256(key=chain_key, data=0x01)`
  - `buf[0:44] = HKDF-SHA-256(ikm=chain_key, salt=<absent>, info="Where-v1-MsgKey")[0:44]`
  - `message_key = buf[0:32]`  (AES-256-GCM key)
  - `message_nonce = buf[32:44]`  (12-byte GCM nonce)
- **Outgoing location**:
  - Derive `message_key` and `message_nonce` from `send_chain_key`; delete old chain key.
  - Increment `send_seq` (uint64).
  - AAD = `"Where-v1-Location"` (UTF-8) `|| version` (4 bytes, big-endian uint32 = 1) `|| sender_fp` (16 bytes, first 16 bytes of SHA-256(sender IK.pub || sender SigIK.pub)) `|| recipient_fp` (16 bytes, first 16 bytes of SHA-256(recipient IK.pub || recipient SigIK.pub)) `|| epoch` (4 bytes BE uint32) `|| seq_be` (8 bytes BE uint64).
  - AES-256-GCM encrypt padded JSON using `message_nonce`.
  - Send `EncryptedLocation` wrapped in a `Post` envelope with top-level **`"v": 1`** field: `epoch`, `seq` (JSON string — decimal-encoded to avoid JS uint64 precision loss; native clients parse as `uint64`), `ct`. **No `nonce` field** (nonce is deterministic and derived independently by both sides — transmitting it leaks chain-reset timing). **No `ek_pub`**.
- **Incoming**:
  - **Drop** (do not buffer) any frame with `seq <= max_seq_received`. Track only `max_seq_received` (a single uint64) — a full set is unnecessary under policy A and would grow unboundedly.
  - Verify GCM tag; advance chain; delete old keys.

### DH ratchet & token rotation
- **Send `RatchetAck` upon receiving each `EpochRotation`** (event-driven, not timer-driven): include a fresh X25519 `ek_pub`, `ts` (uint64 Unix seconds), and Ed25519 sig over the canonical blob `(v || epoch_seen || new_ek_pub || ts || sender_fp || recipient_fp)` — all fixed-width big-endian: `v` 4 B, `epoch_seen` 4 B, `new_ek_pub` 32 B, `ts` 8 B, `sender_fp` 16 B, `recipient_fp` 16 B (total 80 bytes). If multiple `EpochRotation` messages arrive before Bob can respond (e.g., post-reconnect), send a **single** `RatchetAck` referencing the **highest `epoch_seen`** only. All `RatchetAck`/`EpochRotation`/`Poll` envelopes also carry top-level `"v": 1`.
- **On receiving `RatchetAck` or `EpochRotation`**: reject if `ts` is outside ±5 min of local clock (clock-skew grace window).
- **On receiving `RatchetAck`** (Alice's side): validate `epoch_seen` before processing:
  - `epoch_seen < current_epoch` → stale ack; ignore silently.
  - `epoch_seen > current_epoch` → invalid (Alice cannot have sent an EpochRotation for an epoch she hasn't reached); reject and log.
  - `epoch_seen == current_epoch` → apply:
  - `dh_out = X25519(my_ek_priv, their_new_ek_pub)`.
  - `new_root_key, new_chain_key = KDF_RK(...)`.
  - `new_routing_token = HKDF-SHA-256(new_root_key, salt=new_epoch (4-byte big-endian uint32), info="Where-v1-RoutingToken")[0:16]`.
  - Generate fresh `my_ek_priv`/`my_ek_pub` for the next step.
  - **Send `EpochRotation`** on the **current (old) routing token** with `epoch`, `new_ek_pub`, `ts` (uint64 Unix seconds), and Ed25519 sig over canonical blob `(v || epoch || new_ek_pub || ts || sender_fp || recipient_fp)` (same 80-byte fixed-width encoding as `RatchetAck`). Bob has not yet derived `new_routing_token`; posting to the new token would be undeliverable. Identity binding in the sig prevents cross-session replay.
- **On receiving `EpochRotation`** (Bob's side): perform the same `KDF_RK` step and derive `new_routing_token`. Then send a `RatchetAck` for this epoch. If a batch of `EpochRotation` messages arrives (e.g., post-reconnect), process all in order and send a **single** `RatchetAck` citing only the highest epoch.
- Alice MUST retransmit her latest `EpochRotation` if no `RatchetAck` arrives within T (one epoch period, e.g. 10 min), and MUST continue retransmitting every T minutes until a valid `RatchetAck` at or above the current epoch is received. This is the sole recovery mechanism for a stalled DH ratchet. A late-arriving `RatchetAck` MUST be applied regardless of when it arrives.
- **Token Transition Protocol**:
  - Alice posts all frames *after* `EpochRotation` to `new_routing_token`.
  - Bob polls **both** `current` and `new` tokens (for all message types, including `RatchetAck`).
  - Bob retires `current` token only after receiving a valid frame on `new` token OR after `2 * T` (2 epoch periods, e.g. 20 min).
  - **Alice MUST accept `RatchetAck` messages arriving on the old (current) token** during the transition window. Bob may not yet have derived `new_routing_token` when he sends his next `RatchetAck`; rejecting it on the old token would stall the DH ratchet.
  - **T_AB_0 SHOULD be discarded** after the first successful DH ratchet step completes. Never reuse the bootstrap token.
  - **Out-of-order delivery during transition**: a poll batch from the old token may contain both `EpochRotation` and subsequent `EncryptedLocation` frames. Process `EpochRotation` messages before `EncryptedLocation` frames of higher epoch within any batch. If frames arrive on `new_routing_token` before the corresponding `EpochRotation` is processed, buffer up to 64 frames and decrypt them once `EpochRotation` is applied; discard buffered frames older than `2 * T` (2 epoch periods).
- **No fallback**: if no `RatchetAck` is received, Alice continues broadcasting on the symmetric ratchet (per-message FS maintained). The DH ratchet stalls — PCS is suspended until Bob reconnects and sends a `RatchetAck`. Alice SHOULD apply the inactivity threshold (§12 item 5) rather than broadcasting indefinitely into silence.

### Secure storage
- Wipe message/chain/ephemeral priv keys after use.
- Persist only epoch state in keychain/keystore (**backup disabled**).
- On invalid state: drop session, re-key friend.

## 2. Client App Logic

### Identity & safety numbers
- Safety number = `SHA-256(lower_IK.pub || lower_SigIK.pub || higher_IK.pub || higher_SigIK.pub)` — key pairs sorted lexicographically by `IK.pub`, so both the DH key and signing key are covered (a signing-key-only substitution would otherwise be invisible).
- On `KeyExchangeInit` with mismatched pinned key: block with "Safety Number Changed"; require explicit confirm.

### Polling & metadata
- Poll `GET /inbox/{token}` at a **constant rate** (recommended: **60 s**; 10 s is excessive and leaks app-foreground state). Shuffle token order. Bob sends one `RatchetAck` per `EpochRotation` received (event-driven); the epoch period T is a cryptographic parameter and is independent of polling cadence.
- Dummy token polling for cover traffic is **deferred future work** (see §7.5); do not implement yet. Cover traffic provides no meaningful privacy benefit until the user base is large enough to provide population-level anonymity.

## 3. Server (Ktor)

### Mailbox API
- `POST /inbox/{token}`: queue payload; TTL 30–60 min.
- `GET /inbox/{token}`: drain queue; return `200 []` for empty/unknown tokens.
- **Constant-Time Invariant**: Ensure mailbox lookup and response for empty/unknown tokens are indistinguishable from active tokens to prevent timing side-channels.
- Treat payloads as opaque bytes; no parsing.

### Storage & logging
- Index only by token (16 bytes); no user-ID links.
- Log/hashes truncated tokens only; no full payloads.
