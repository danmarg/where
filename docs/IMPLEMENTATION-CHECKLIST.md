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
  - `T_AB_0 = HKDF-SHA-256(SK, info="Where-v2-RoutingToken")[0:16]`.
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
  - AAD = `"Where-v2-Location" || sender_fp || recipient_fp || epoch || seq_be`.
  - AES-256-GCM encrypt padded JSON using `message_nonce`.
  - Send `EncryptedLocation`: `epoch`, `seq` (JSON string), `nonce` (message_nonce), `ct`. **No `ek_pub`**.
- **Incoming**:
  - Reject `seq <= max_seq_received` (strict).
  - Verify GCM tag; advance chain; delete old keys.

### DH ratchet & token rotation
- Send `RatchetAck` every 10 min: new X25519 `ek_pub` + Ed25519 sig.
- On `RatchetAck`/`EpochRotation`:
  - `dh_out = X25519(my_ek_priv, their_new_ek_pub)`.
  - `new_root_key, new_chain_key = KDF_RK(...)`.
  - `new_routing_token = HKDF-SHA-256(new_root_key, info="Where-v2-RoutingToken")[0:16]`.
- **Token Transition Protocol**:
  - Alice posts subsequent messages to `new_routing_token`.
  - Bob polls **both** `current` and `new` tokens.
  - Bob retires `current` token only after receiving a valid frame on `new` token OR after `2 * R` seconds (20 min).
- Fallback: time-based DH step after 20 min no `RatchetAck`.

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
