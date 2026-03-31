# E2EE Location Sync — Implementer's Checklist

**Status:** Ready to implement.
**Date:** 2026-03-26
**References:** [PR #2](https://github.com/danmarg/where/pull/2), [Issue #6](https://github.com/danmarg/where/issues/6), [Issue #7](https://github.com/danmarg/where/issues/7)

Split into client-crypto, client-app, and server.

## 1. Client Crypto (per platform)

### Key material
- No long-term device identity keys (no IK, no SigIK). All keys are session-scoped ephemeral X25519 keypairs.
- For each friend, maintain `SessionState`: `root_key`, `send_chain_key`, `recv_chain_key`, `routing_token`, `send_seq`, `recv_seq`, `epoch`, `my_ek_priv`, `my_ek_pub`, `their_ek_pub`, `alice_fp`, `bob_fp`, `k_bundle`.

### Key exchange
- Generate fresh ephemeral X25519 keypair `EK_A` for Alice's QR code.
- **QR payload**: `ek_pub` (Alice's `EK_A.pub`, base64), `fingerprint` (first 8 bytes of `SHA-256(EK_A.pub)`, hex), `name` (Alice's display name). Link format: `where://add?ek=<base64>&fp=<fingerprint>&name=<name>`.
- Alice persists `EK_A.priv` until the exchange completes (Bob replies with `KeyExchangeInit`).
- Bob scans Alice's QR, generates his own `EK_B`, and sends `KeyExchangeInit`:
  - `SK = X25519(EK_B.priv, EK_A.pub)`.
  - Derive chain keys: `HKDF-SHA-256(SK, salt=<absent>, info="Where-v1-KeyExchange")` → 64 bytes: `root_key = buf[0:32]`, `send_chain_key_bob = buf[32:64]`.
  - `alice_fp = SHA-256(EK_A.pub)`, `bob_fp = SHA-256(EK_B.pub)`.
  - `K_bundle = HKDF-SHA-256(SK, info="Where-v1-BundleAuth")` (for `PreKeyBundle` HMAC auth).
  - `K_rot = HKDF-SHA-256(root_key, info="Where-v1-EpochRotation")` (for `EpochRotation` AEAD).
  - `K_ack = HKDF-SHA-256(root_key, info="Where-v1-RatchetAck")` (for `RatchetAck` AEAD).
  - `routing_token_0 = HKDF-SHA-256(SK, info="Where-v1-RoutingToken" || alice_fp || bob_fp)[0:16]`.
  - **Include `key_confirmation`**: `HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)`.
  - **Include `suggested_name`**: Bob's display name for Alice to pre-fill in the peer naming dialog.
  - Post `KeyExchangeInit` (including `ek_pub`, `key_confirmation`, `suggested_name`) to `routing_token_0`.
- Alice receives `KeyExchangeInit`:
  - Compute `SK = X25519(EK_A.priv, EK_B.pub)`.
  - Derive chain keys identically (Alice's `send_chain_key` = Bob's `recv_chain_key`, derived symmetrically).
  - **MUST verify `key_confirmation`**: recompute `HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)` and compare in constant time. Abort and discard session on mismatch.
  - **Alice MUST delete `EK_A.priv` immediately after SK derivation.**
  - Show Bob's `suggested_name` pre-filled in the peer naming dialog; Alice confirms or edits before storing the friend.
- On `KeyExchangeInit` with mismatched pinned session: block with "Safety Number Changed"; require explicit confirm.

### Ratchet
- `KDF_RK`: HKDF-SHA-256 for DH ratchet steps.
- `KDF_CK`: Single `HKDF-SHA-256(ikm=current_chain_key, salt=<absent>, info="Where-v1-MsgStep")` producing 76 bytes:
  - `new_chain_key = buf[0:32]`
  - `message_key = buf[32:64]`
  - `message_nonce = buf[64:76]` (12-byte deterministic nonce)
- **Outgoing location**:
  - Derive `message_key` and `message_nonce` from `send_chain_key`; delete old chain key.
  - Increment `send_seq` (uint64).
  - **Overflow check**: If `send_seq == UINT64_MAX`, terminate session and re-key.
  - AAD = `"Where-v1-Location"` (UTF-8) `|| version` (4 bytes, big-endian uint32 = 1) `|| alice_fp` (32 bytes, `SHA-256(EK_A.pub)`) `|| bob_fp` (32 bytes, `SHA-256(EK_B.pub)`) `|| epoch` (4 bytes BE uint32) `|| seq_be` (8 bytes BE uint64).
  - AES-256-GCM encrypt padded JSON using `message_nonce`.
  - Send `EncryptedLocation` wrapped in a `Post` envelope with top-level **`"v": 1`** field. **No `nonce` field**. **No `ek_pub`**.
- **Incoming**:
  - **Drop** (do not buffer) any frame with `seq <= max_seq_received`.
  - **Chain gap advancement**: if `incoming_seq > current_seq + 1`, pump `KDF_CK` exactly `(incoming_seq - current_seq - 1)` times, discarding derived keys. This advances the chain state past dropped/skipped messages before decrypting the arriving frame.
  - Verify GCM tag; advance chain; delete old keys.

### DH ratchet & token rotation
- **Bob: Periodically generate and post `PreKeyBundle`**:
  - Generate a batch of X25519 keypairs (OPKs).
  - Post public keys with unique IDs to the shared mailbox.
  - **Authenticate bundle with HMAC**: `HMAC-SHA-256(K_bundle, v || token || keys_json_canonical)`. (No Ed25519; `K_bundle` is session-derived.)
- **Alice: Consume OPK for Epoch Rotation**:
  - Cache Bob's OPKs from mailbox. Verify HMAC before use.
  - On epoch boundary (every `T` minutes), pop one OPK.
  - `dh_out = X25519(my_ek_priv, Bob.OPK.pub)`.
  - `new_root_key, new_chain_key = KDF_RK(root_key, dh_out)`.
  - `new_routing_token = HKDF-SHA-256(new_root_key, info="Where-v1-RoutingToken" || alice_fp || bob_fp)[0:16]`.
  - Derive updated `K_rot = HKDF-SHA-256(new_root_key, info="Where-v1-EpochRotation")` and `K_ack`.
  - **Alice MUST continue polling the old (current) token** for `PreKeyBundle` and `RatchetAck` until `2 * T` expires.
  - **Send `EpochRotation`** on the **current (old) routing token**: AEAD-encrypt with `AES-256-GCM(key=K_rot, plaintext=canonical_blob, aad=routing_token_current)`. Canonical blob contains `epoch`, `opk_id`, `new_ek_pub`, `ts`.
- **Timestamp validation**: On receiving `EpochRotation` or `RatchetAck`, reject if `ts` is outside `T + 5 min` of local clock.
- **Bob: Process Epoch Rotation**:
  - Retrieve private key for `opk_id` from secure storage.
  - Perform `KDF_RK` to derive `new_root_key` and `new_routing_token`.
  - Verify AEAD tag on `EpochRotation` using `K_rot` before accepting. Abort on failure.
  - **MUST delete OPK private key immediately after use.**
  - (Optional) Send `RatchetAck`: AEAD-encrypted with `AES-256-GCM(key=K_ack, plaintext=canonical_blob, aad=routing_token_current)`. Canonical blob contains `epoch_seen`, `ts`.

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
- Safety number = first 12 bytes of `SHA-256(lower_EK.pub || higher_EK.pub)` — session-scoped, where "lower/higher" is lexicographic order of the two `EK.pub` values. Display as 6 groups of 4 decimal digits.
- On `KeyExchangeInit` with mismatched pinned session: block with "Safety Number Changed"; require explicit confirm.

### Peer naming
- **Bob's side**: include own display name as `suggested_name` in `KeyExchangeInit`. If no display name is set, omit or send empty string.
- **Alice's side**: pre-fill the peer naming dialog with `suggested_name` from the received `KeyExchangeInit`; user confirms or edits before the friend entry is stored.
- **Bob's side**: on scanning Alice's QR code, show a naming dialog (pre-filled with `name` from the QR link, if present); user confirms or edits before `KeyExchangeInit` is sent.

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
