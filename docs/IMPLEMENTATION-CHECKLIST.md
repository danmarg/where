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
  - `SK = HKDF-SHA-256(DH1 || DH2 || DH3, info="Where-v1-KeyExchange")`.
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
- **Bob: Periodically generate and post `PreKeyBundle`**:
  - Generate a batch of X25519 keypairs (OPKs).
  - Post public keys with unique IDs to the shared mailbox.
  - Sign bundle: `Ed25519Sign(Bob.SigIK.priv, v || token || keys_json_canonical)`.
- **Alice: Consume OPK for Epoch Rotation**:
  - Cache Bob's OPKs from mailbox.
  - On epoch boundary (every `T` minutes), pop one OPK.
  - `dh_out = X25519(my_ek_priv, Bob.OPK.pub)`.
  - `new_root_key, new_chain_key = KDF_RK(...)`.
  - `new_routing_token = HKDF-SHA-256(new_root_key, salt=new_epoch, info="Where-v1-RoutingToken")[0:16]`.
  - **Send `EpochRotation`** on the **current (old) routing token** with `epoch`, `opk_id`, `new_ek_pub`, `ts`, and Ed25519 sig over 84-byte canonical blob `(v || epoch || opk_id || new_ek_pub || ts || sender_fp || recipient_fp)`.
- **Bob: Process Epoch Rotation**:
  - Retrieve private key for `opk_id` from secure storage.
  - Perform `KDF_RK` to derive `new_root_key` and `new_routing_token`.
  - **MUST delete OPK private key immediately after use.**
  - (Optional) Send `RatchetAck` (48-byte canonical blob: `v || epoch_seen || ts || sender_fp || recipient_fp`) to prune Alice's retransmit timer.
- **On receiving EpochRotation or RatchetAck**: reject if `ts` is outside ±5 min of local clock.
- **Token Transition Protocol**:
  - Alice posts all frames *after* `EpochRotation` to `new_routing_token`.
  - Bob polls **both** `current` and `new` tokens.
  - Bob retires `current` token only after receiving a valid frame on `new` token OR after `2 * T` (20 min).
  - **T_AB_0 SHOULD be discarded** after the first successful DH ratchet step completes.
- **Out-of-order delivery during transition**: process `EpochRotation` before `EncryptedLocation` of higher epoch in same batch. Buffer up to 64 frames on `new_routing_token` if `EpochRotation` hasn't arrived; discard after `2 * T`.
- **No OPKs**: if Alice runs out of cached OPKs, the DH ratchet stalls (PCS suspended). Alice continues broadcasting on the symmetric ratchet. Alice SHOULD NOT rotate the DH epoch until a new bundle is received.

### Secure storage
- Wipe message/chain/ephemeral priv keys after use.
- Persist only epoch state in keychain/keystore (**backup disabled**).
- On invalid state: drop session, re-key friend.

## 2. Client App Logic

### Identity & safety numbers
- Safety number = `SHA-256(lower_IK.pub || lower_SigIK.pub || higher_IK.pub || higher_SigIK.pub)` — key pairs sorted lexicographically by `IK.pub`, so both the DH key and signing key are covered (a signing-key-only substitution would otherwise be invisible).
- On `KeyExchangeInit` with mismatched pinned key: block with "Safety Number Changed"; require explicit confirm.

### Polling & metadata
- Poll `GET /inbox/{token}` at a **constant rate** (recommended: **60 s**; 10 s is excessive and leaks app-foreground state). Shuffle token order. The epoch period T is a cryptographic parameter and is independent of polling cadence. Bob periodically "tops up" his pre-key bundle in the shared mailbox to enable Alice's asynchronous DH ratchet.
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
