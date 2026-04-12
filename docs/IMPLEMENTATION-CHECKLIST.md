# E2EE Location Sync — Implementer's Checklist

**Status:** Completed and Refactored.
**Date:** 2026-04-12
**References:** [Issue #182](https://github.com/danmarg/where/issues/182)

Split into client-crypto, client-app, and server.

## 1. Client Crypto (per platform)

### Key material
- No long-term device identity keys (no IK, no SigIK). All keys are session-scoped ephemeral X25519 keypairs.
- For each friend, maintain `SessionState`: `root_key`, `send_chain_key`, `recv_chain_key`, `send_token`, `recv_token`, `send_seq`, `recv_seq`, `local_dh_priv`, `local_dh_pub`, `remote_dh_pub`, `alice_fp`, `bob_fp`. Note: `local_dh_priv` is currently persisted for stability.

### Key exchange
- Generate fresh ephemeral X25519 keypair `EK_A` for Alice's QR code.
- **QR payload**: `ek_pub` (Alice's `EK_A.pub`, base64), `fingerprint` (first 8 bytes of `SHA-256(EK_A.pub)`, hex), `name` (Alice's display name). Link format: `where://add?ek=<base64>&fp=<fingerprint>&name=<name>`.
- Alice persists `EK_A.priv` until the exchange completes (Bob replies with `KeyExchangeInit`).
- Bob scans Alice's QR, generates his own `EK_B`, and sends `KeyExchangeInit`:
  - `SK = X25519(EK_B.priv, EK_A.pub)`.
  - Derive base keys via `HKDF-SHA-256`.
  - **Include `key_confirmation`**: `HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)`.
  - Post `KeyExchangeInit` to the discovery token.
- Alice receives `KeyExchangeInit`:
  - Compute `SK = X25519(EK_A.priv, EK_B.pub)`.
  - **MUST verify `key_confirmation`** in constant time.
  - Alice immediately performs a **DH Ratchet step** to break the bootstrap session deadlock, generating `A1` and finalizing her session. She rotates `sendToken` for Epoch 1.

### Ratchet
- Unified Double Ratchet for every friendship. Both peers send and receive standard encrypted message envelopes.
- `KDF_RK`: HKDF-SHA-256 for DH ratchet steps.
- `KDF_CK`: HKDF-SHA-256 for symmetric chain steps.
- **Message format**: `EncryptedMessagePayload` contains `seq`, `dhPub`, and `ct`.
- Plaintext payload contains `lat`, `lng`, `acc`, `ts`, or acts as a `Keepalive`.
- Padding to fixed size of `512` bytes to obscure movement thresholds and keepalives.

### DH ratchet & token rotation
- Standard Double Ratchet: Receiving a message with a new `dhPub` triggers `KDF_RK`.
- **Token Rotation**: `send_token` and `recv_token` are derived from the root key. They rotate whenever the DH Ratchet advances.
- **Transition Buffer**: The first message sent in a new DH epoch is posted to the *previous* `send_token` (`isSendTokenPending`) because the remote peer is not yet listening on the new token. Next messages go to the new token.
- Alice and Bob continually advance the DH Ratchet by replying to each other's messages.
- If no natural location update is being sent but a new DH key was received, the client sends an automated **Keepalive** to explicitly advance the caller's DH Ratchet and ensure Post-Compromise Security (PCS).

### Secure storage
- Wipe message/chain/ephemeral priv keys after use.
- Persist epoch state in keychain/keystore (**backup disabled**). Note: `localDhPriv` is currently persisted along with the state to ensure session continuity across restarts.

## 2. Client App Logic

### Identity & safety numbers
- Safety number = first 32 bytes of `SHA-256(lower_EK.pub || higher_EK.pub)` lexicographically sorted. Display as groups of digits.

### Polling & metadata
- Poll `GET /inbox/{token}` at a **constant rate**.
- **Staleness/Timeout**: If no message (Location or Keepalive) is received from a friend within 7 days, location sharing to that friend is paused on the local device. This ensures PCS by not transmitting onto an indefinite one-way symmetric chain if the peer has uninstalled the app or lost their keys.

## 3. Server (Ktor)

### Mailbox API
- `POST /inbox/{token}`: queue payload; TTL 30–60 min.
- `GET /inbox/{token}`: drain queue; return `200 []` for empty/unknown tokens.
- **Constant-Time Invariant**: Maintain timing consistency preventing side-channels.
- Treat payloads as opaque bytes; no parsing.
