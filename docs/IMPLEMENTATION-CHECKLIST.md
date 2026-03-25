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
- Parse QR/link for `ik_pub`, `ek_pub` (Alice's ephemeral), `sig_pub`, `suggested_name`, `fingerprint`.
- On `KeyExchangeInit`:
  - Verify Ed25519 sig over `(ik_pub || ek_pub || sig_pub)`; abort on failure.
  - Compute:
    - `DH1 = X25519(Bob.EK_B.priv, Alice.IK.pub)`
    - `DH2 = X25519(Bob.IK.priv, Alice.EK_A.pub)`
    - `DH3 = X25519(Bob.EK_B.priv, Alice.EK_A.pub)`
  - `SK = HKDF-SHA-256(DH1 || DH2 || DH3, info="Where-v2-KeyExchange")`.
  - `T_AB_0 = HKDF-SHA-256(SK, salt=0x00...00, info="Where-v2-RoutingToken")[0:16]`.
  - Initialize session state; **Alice MUST delete EK_A.priv immediately after derivation**.

### Ratchet
- `KDF_RK`: HKDF-SHA-256 for DH ratchet steps.
- `KDF_CK`: HMAC-SHA-256 for symmetric chain. **Deterministic nonces are mandatory**:
  - `new_chain_key = HMAC-SHA-256(chain_key, 0x01)`
  - `message_key = HMAC-SHA-256(chain_key, 0x02)`
  - `message_nonce = HMAC-SHA-256(chain_key, 0x03)[0:12]`
- **Outgoing location**:
  - Derive `message_key` and `message_nonce` from `send_chain_key`; delete old chain key.
  - Increment `send_seq` (uint64).
  - AAD = `"Where-v2-Location"` (UTF-8) `|| version` (4 bytes, big-endian uint32 = 1) `|| sender_fp || recipient_fp || epoch` (4 bytes BE uint32) `|| seq_be` (8 bytes BE uint64).
  - AES-256-GCM encrypt padded JSON using `message_nonce`.
  - Send `EncryptedLocation` wrapped in a `Post` envelope with top-level **`"v": 1`** field: `epoch`, `seq` (JSON string — decimal-encoded to avoid JS uint64 precision loss; native clients parse as `uint64`), `nonce` (message_nonce), `ct`. **No `ek_pub`**.
- **Incoming**:
  - **Drop** (do not buffer) any frame with `seq <= max_seq_received` (strict ordering policy; keys are forward-deleted so retroactive decryption is impossible anyway).
  - Verify GCM tag; advance chain; delete old keys.

### DH ratchet & token rotation
- Send `RatchetAck` every 10 min: new X25519 `ek_pub` + Ed25519 sig over `(v || epoch_seen || new_ek_pub)`. All `RatchetAck`/`EpochRotation`/`Poll` envelopes also carry top-level `"v": 1`.
- **On receiving `RatchetAck`** (Alice's side):
  - `dh_out = X25519(my_ek_priv, their_new_ek_pub)`.
  - `new_root_key, new_chain_key = KDF_RK(...)`.
  - `new_routing_token = HKDF-SHA-256(new_root_key, salt=0x00...00, info="Where-v2-RoutingToken")[0:16]`.
  - Generate fresh `my_ek_priv`/`my_ek_pub` for the next step.
  - **Send `EpochRotation`** on the **current (old) routing token** with `epoch`, `new_ek_pub`, and Ed25519 sig over `(v || epoch || new_ek_pub)`. Bob has not yet derived `new_routing_token`; posting to the new token would be undeliverable.
- **On receiving `EpochRotation`** (Bob's side): perform the same `KDF_RK` step and derive `new_routing_token`.
- Alice SHOULD retransmit her latest `EpochRotation` if no `RatchetAck` arrives within R (10 min) to accelerate recovery; a late-arriving `RatchetAck` is still valid and SHOULD be applied.
- **Token Transition Protocol**:
  - Alice posts all frames *after* `EpochRotation` to `new_routing_token`.
  - Bob polls **both** `current` and `new` tokens (for all message types, including `RatchetAck`).
  - Bob retires `current` token only after receiving a valid frame on `new` token OR after `2 * R` seconds (20 min).
- **Fallback**: if no `RatchetAck` received within 20 min, perform a time-based DH ratchet step using Bob's last known `their_ek_pub` **unchanged from session state** (one-sided refresh — Alice's EK rotates but Bob's does not; PCS degrades, FS is maintained).

### Secure storage
- Wipe message/chain/ephemeral priv keys after use.
- Persist only epoch state in keychain/keystore (**backup disabled**).
- On invalid state: drop session, re-key friend.

## 2. Client App Logic

### Identity & safety numbers
- Safety number = `SHA-256(local_IK.pub || remote_IK.pub)` (lex sorted).
- On `KeyExchangeInit` with mismatched pinned key: block with "Safety Number Changed"; require explicit confirm.

### Polling & metadata
- Poll `GET /inbox/{token}` every 10s at **constant rate**, shuffle token order.
- Optional: poll dummy tokens for cover traffic.

## 3. Server (Ktor)

### Mailbox API
- `POST /inbox/{token}`: queue payload; TTL 30–60 min.
- `GET /inbox/{token}`: drain queue; return `200 []` for empty/unknown tokens.
- **Constant-Time Invariant**: Ensure mailbox lookup and response for empty/unknown tokens are indistinguishable from active tokens to prevent timing side-channels.
- Treat payloads as opaque bytes; no parsing.

### Storage & logging
- Index only by token (16 bytes); no user-ID links.
- Log/hashes truncated tokens only; no full payloads.
