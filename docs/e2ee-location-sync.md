# End-to-End Encrypted Location Sync — Design Document

**Status:** Implemented
**Authors:** Daniel Margolis
**Date:** 2026-05-24

---

## Table of Contents

1. [Background and Motivation](#1-background-and-motivation)
2. [Threat Model](#2-threat-model)
3. [Identity and Key Management — No PKI](#3-identity-and-key-management--no-pki)
4. [Key Exchange Flow](#4-key-exchange-flow)
5. [Ratchet Design for Streaming Location Data](#5-ratchet-design-for-streaming-location-data)
6. [Group Broadcasting — Per-Friend Keys](#6-group-broadcasting--per-friend-keys)
7. [Server's Role and Obliviousness](#7-servers-role-and-obliviousness)
8. [Concrete Protocol Recommendation](#8-concrete-protocol-recommendation)
9. [Wire Format](#9-wire-format)
10. [Server Changes](#10-server-changes)
11. [Cryptographic Primitives Summary](#11-cryptographic-primitives-summary)
12. [Open Questions and Future Work](#12-open-questions-and-future-work)

---

## 1. Background and Motivation

### 1.1 Motivation: Unencrypted Baseline

Without E2EE, each client connects to a Ktor server. When a client sends a location update, the server fans out the full user-location map to all connected clients. Each client filters the received list to show only users in its local friends list.

**What the server learns without E2EE:**
- The precise GPS coordinates of every connected user at all times.
- Who is connected and when (presence).
- The frequency of movement (indirectly, from update cadence).
- The full social graph: the server observes exactly which users are sharing with whom.

The goal of this protocol is to make the server cryptographically unable to read location payloads and unable to reconstruct the social graph, even if it is fully compromised.

### 1.2 Scope

This document covers:
- Key establishment between peers with no central identity server
- A ratchet-based forward secrecy scheme adapted for continuous one-way location broadcasting
- Group fan-out (one sender, N recipients)
- The resulting wire format and server changes

This document does **not** cover:
- Post-quantum cryptography (deferred; see Section 12)

The following potential features are not planned and are out of scope of this project:
- Persistent user identities
- Multidevice support (i.e., allowing Alice to share her location with Bob from *n* devices without *n* separate pairings with Bob)
- Device backup/upgrade/transfer

This decision significantly simplifies the protocol (no persistent identities!) and the UX (no "which device am I sharing from?" or cross-device backup/transfer) while improving security, but it comes at a cost: if Alice loses or resets her phone, she must repair with Bob and Charlie. Similarly, if Bob deletes his connection with Alice, it is indistinguishable to Alice from Bob's phone being powered off; should they wish to resume sharing, they will have to re-pair their phones.

An additional out-of-scope feature is cloud-to-device push-notification-based latency optimization. Two possible implementations exist for this:

1. Alice could notify the server when she is actively looking at Bob's location (i.e. her app is foregrounded), and the server could ping Bob's phone to update Bob's location.
2. Alice could notify the server when she is actively moving, and the server could ping Bob's phone to update Alice's location.

Option 2 is likely a pointless optimization, since at this point Alice's device has already spent the battery on GPS and cell radio; furthermore, the UX improvement is minimal unless Bob's app is in the foreground (in which case it can probably just afford to do rapid polling). 

Option 1 would probably yield significant battery improvements (by reducing the need for Bob to send high latency pings when moving except when Alice is actively using the app), but it requires fairly complex A->server->B communication that would link transient mailbox tokens with persistent long-term device identifiers (needed by push notification infra), creating a meaningful metadata leak.

---

## 2. Threat Model

### 2.1 Attacker Capabilities

| Attacker | Assumed Capabilities |
|---|---|
| Passive network attacker | Can observe all network packets (mitigated by TLS, included for defense-in-depth) |
| Compromised server | Has full access to the server process, memory, database, and all received ciphertext |
| Actively malicious server | Injects or replays messages |
| Honest-but-curious server | Reads all metadata and routing information but does not actively modify messages |
| Malicious friend | A user who was legitimately added as a friend but later turns adversarial |
| MITM during key exchange | Can intercept out-of-band key exchange if the channel is not authenticated |
| Metadata-analyzing server | A server (honest-but-curious or compromised) that performs timing, presence, and social-graph analysis on connection metadata without decrypting payloads |

### 2.1.1 Metadata Threat Model

Metadata leakage is a first-class concern for this protocol, not a footnote. The server learns social graph, presence, and update timing even without decrypting payloads:

- **Honest-but-curious server:** Social graph, presence, and timing leakage are observable limitations. Mitigations (inbox tokens, padding; see §7.4) reduce but do not eliminate this exposure. Timing-based movement inference is essentially unavoidable without cover traffic.
- **Compromised server:** Metadata is less important than ciphertext recovery, but timing analysis can still reveal movement patterns. Payload padding (§7.4) is a mandatory baseline; cover traffic is optional.
- **Passive network attacker:** TLS at the transport layer (§10.3) hides metadata from the network. The server still observes everything above.

For threat models that include a metadata-analyzing server, the mitigations in §7.4 are the appropriate countermeasures. This document uses "honest-but-curious server" to mean a server that does not forge or modify ciphertexts, but may perform unlimited metadata analysis.

---

### 2.2 What This Protocol Protects Against

- **Server compromise revealing historical locations.** *Forward secrecy (per-message):* deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others. *Post-compromise security (per DH ratchet step):* the DH ratchet step refreshing the root key and symmetric chains limits how long a leaked chain key remains exploitable.
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing message number `msg_num` which is also authenticated (as AEAD additional data for the body, and encrypted within the header envelope). The recipient rejects any frame with a counter it has already seen within the same DH epoch. 

Across-epoch replay protection comes from header-key rotation: the recipient retains only the current and next receive header keys, so a replayed frame from a retired epoch fails header decryption (`tryDecryptHeader`) and is discarded before any ratchet logic runs. See §8.3.1(6).
- **Ciphertext forgery.** ChaCha20-Poly1305 authentication tags cover both the ciphertext and associated data. Metadata (DH public key and sequence number) is sealed within an encrypted envelope, preventing a malicious server from reading or correlating them across token rotations.
- **Ratchet hijacking.** All messages are AEAD-encrypted under keys derived from the current session root key and symmetric chains. An attacker without session state or header keys cannot forge or inject valid messages.
- **Key mismatch at bootstrap.** The `key_confirmation` field in `KeyExchangeInit` (§4.2) detects corruption or bit-flips in `EK_B.pub` in transit — Alice recomputes the MAC and rejects on mismatch. It does NOT authenticate the origin of the QR code or defeat an active MITM who can intercept and substitute the initial ephemeral key material; trust establishment relies on TOFU + Safety Number verification (§3.4). It is also NOT the primary defense against an attacker substituting `EK_B.pub` with a low-order curve point (which would coerce `SK` to a known constant): defense against that class lives at the X25519 layer — see §4.3 *Public-Key Validation*.

To ensure robustness against network failures, the protocol employs **Server-side Idempotency** and **Client-side Write Ahead Logging (WAL)**:
- **Unique Message IDs:** Every message (encrypted frame or handshake) includes a unique `msg_id` (derived from the ciphertext or public key).
- **Idempotent POST:** Clients use `PUT /inbox/{token}/{msgId}` to post messages. The server tracks received IDs and treats duplicate attempts for the same `msgId` as a no-op success.
- **Outbox WAL:** Clients durably persist outgoing messages in a per-friend **outbox** before any network attempt. Messages are only removed from the outbox after the server returns a success code. This allows for reliable recovery and retry after app crashes or network timeouts.

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server sees packet timing, packet sizes, and connection metadata regardless of payload encryption. For a location app, timing is nearly as sensitive as content: update intervals increasing from 30 seconds (moving) to 5 minutes (stationary) reveal movement state; silence reveals offline/stopped status; synchronized update timing across multiple users suggests co-location. A metadata-analyzing server can infer movement patterns from timing alone, independent of content. Mitigations (cover traffic, padding) are discussed in §7.4; they add overhead and battery cost and are not a panacea. This is elevated here as a top-level limitation for threat models that include a compromised or curious server or persistent passive network attacker.
- **Initial TOFU impersonation.** The first key exchange (§4) uses an ephemeral key delivered out-of-band (QR or link). If an attacker intercepts or replaces Alice's QR before Bob scans it, Bob will establish a session with the attacker rather than Alice. The `key_confirmation` in `KeyExchangeInit` (§4.2) ensures Bob derived the correct `SK` from the `EK_A.pub` he scanned, but it does not authenticate Alice's QR as originating from Alice rather than an attacker who substituted a different key. Safety number verification (§3.4) is the primary mitigation for this residual TOFU risk.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server never sees user IDs or UUIDs — routing tokens are opaque and pseudorandom. However, the server observes which IP addresses `POST` to and `GET` from each token. If Alice's IP consistently posts to token T and Bob's IP consistently polls T, the server (or a passive network attacker) can infer they share a friendship, even without decrypting any payload. See §7 for partial mitigations (constant-rate polling, dummy tokens).
- **Compromised backups revealing future epochs.** Because the current implementation persists the active ratchet private key (`localDhPriv`) to ensure session stability across app restarts (§5.5), an attacker who recovers a device backup gains access to the current `localDhPriv` and header keys. They can decrypt headers of future messages to observe the peer's new DH public keys, and use `localDhPriv` to advance the root key through each subsequent peer DH epoch — tracking all future message keys until the compromised device generates a fresh DH keypair and the peer ratchets against it. In practice, the exposure window is bounded by the peer's message cadence: the ratchet self-heals after the next complete DH exchange (roughly one location-update interval if both parties are active), but stalls if the peer is offline. Historical messages remain protected by forward secrecy: deleted chain keys cannot be recovered from the backup snapshot. See §5.6 for a detailed analysis of compromise consequences and self-healing (PCS). As mitigation, the `localDhPriv` key is excluded from cloud backups (see §5.5).
- **Map tile server leakage.** When a recipient views a friend's location on a map, the map provider (e.g., Google Maps, Apple Maps, Mapbox) may infer the friend's location from which tiles the recipient's device requests. This can be mitigated at the application layer via tile pre-fetching or caching, but is outside the scope of this protocol.
- **Denial of service.** This protocol does not protect against a server that drops or delays messages.
- **Quantum adversaries.** All DH operations here use X25519 (256-bit elliptic curve). A cryptographically relevant quantum computer running Shor's algorithm could break these. See §12.

---

## 3. Identity and Key Management — Session as Identity

### 3.1 The Model: No Long-Term Keys, No PKI

This protocol uses **no long-term identity keys**. There is no `IK`, no `SigIK`, and no central registry. Identity is scoped entirely to a single friendship session, anchored by the ephemeral keys exchanged at bootstrap.

This design decision is dependent upon the device management policy (§3.3): when a device is lost or the app is reinstalled, all contacts must be manually re-added. Dropping long-term keys eliminates the exposure of a stable device identifier to the server (§4.4), risking social graph leakage, and considerably simplifies the protocol.

Each friendship session is identified by the pair `(EK_A.pub, EK_B.pub)` — the initial bootstrap ephemeral public keys from Alice and Bob respectively. These keys are used to derive the Safety Number (§3.4) and session fingerprints (§8.3). The session's root key `SK` is derived from a single X25519 operation over these keys. Alice's private key `EK_A.priv` is deleted immediately after derivation. Bob's private key `EK_B.priv` is **not** deleted at this point — it is copied into `localDhPriv` and persists until Bob completes his first DH ratchet step (see §4.4 and §5.5).

### 3.2 Naming and Local Aliases

To avoid requiring users to manage session keys in the UI, the protocol implements local aliases:

1. **Invite Payload:** An invite contains `{ek_pub, suggested_name: "Alice", fingerprint}`. Alice's suggested name is pre-filled in Bob's naming dialog.
2. **KeyExchangeInit:** Bob includes his own `suggested_name` encrypted under `K_name` (derived from `SK`) when responding to Alice's QR. Alice decrypts it after verifying `key_confirmation` and pre-fills her naming dialog.
3. **Local Import:** The receiving party sees the other's suggested name but may rename it locally before confirming.
4. **Local Storage:** The name is a purely local alias. It is never sent to the server in plaintext. *It's important to note that these names are merely human-friendly aliases*; they are not globally unique or authoritative in any way.

This mechanism allows sides to asign human-readable names to each other at the time of first exchange, with no extra protocol round-trips.

### 3.3 Device Management

1. **One Primary Device per Person:** Sessions are scoped to a single primary device (typically a phone).
2. **Lost Device / Device Replacement:** When a user gets a new phone or loses their device, they re-pair with each friend, generating a new ephemeral key pair per new invite. This is a manual "session reset":
   - The user reinstalls the app and re-adds all contacts via new invite links.
   - Each new pairing produces a new Safety Number.
3. **No Cloud Backup:** Encrypted cloud backup of session state is explicitly excluded, as it introduces a third-party trust dependency.

### 3.4 Trust Establishment and Safety Numbers

This protocol uses **Trust-on-First-Use (TOFU)** with local session pinning.

**Safety Numbers:** Two users can optionally verify their connection by comparing a safety number fingerprint.
- **Calculation:** `HKDF-SHA-256(ikm=SHA-256(lower_EK.pub || higher_EK.pub), salt=null, info="Where-v1-SafetyNumber", length=60)`.
- The result is displayed as 12 groups of 5 decimal digits (consistent with §8.3 format).
- This is **session-scoped**: the Safety Number is unique to the specific pairing event, not to a device. Every re-pairing after a device reset produces a new Safety Number.

**Risk:** If the invite link (Option B, §4.3) is intercepted over an unauthenticated channel (e.g., SMS), an attacker can substitute their own key. Fingerprint verification is the primary countermeasure.

---

## 4. Key Exchange Flow

### 4.1 Prerequisites

Each exchange requires only one ephemeral X25519 key pair per side, generated fresh per invite:
- **Alice:** Generates a fresh ephemeral key pair `EK_A` when displaying a QR/link. No persistent key material required.
- **Bob:** Generates a fresh ephemeral key pair `EK_B` when scanning Alice's QR.

Alice's private key `EK_A.priv` is deleted immediately after `SK` is computed and verified. Bob's private key `EK_B.priv` is retained as `localDhPriv` until his first DH ratchet step completes (see §4.4 and §5.5).

### 4.2 Option A: In-Person QR Code Exchange (Recommended)

**Setup:**

Alice opens "Add Friend" and generates a fresh ephemeral key pair `EK_A` and a fresh random 32-byte `discovery_secret`. She displays a QR code encoding:
```
{
  "ek_pub":            base64(Alice.EK_A.pub),  // X25519 ephemeral public key (32 bytes)
  "suggested_name":    "Alice",
  "fingerprint":       hex(SHA-256(EK_A.pub)[0:20]),
  "discovery_secret":  base64(random_32_bytes)   // fresh per QR; HKDF IKM for discovery token
}
```

No long-term keys, no signatures. The QR is intentionally minimal.

**Discovery Token (Pre-Session Rendezvous):**

After Alice generates her QR, she derives the discovery token from `discovery_secret`:

```
discovery_token_A = HKDF-SHA-256(IKM  = Alice.discovery_secret,   // 32-byte random secret
                                 salt = 0x00...00,                // 32 zero bytes
                                 info = "Where-v1-Discovery")[0:16]
```

Using a random secret (rather than `EK_A.pub`) as HKDF IKM ensures that only someone who received the QR out-of-band can compute `discovery_token_A`. A network observer who later sees `EK_A.pub` in Bob's `KeyExchangeInit` message cannot retroactively map it to the discovery-phase mailbox.

- Alice begins polling `GET /inbox/{hex(discovery_token_A)}` immediately.
- Bob derives the same `discovery_token_A` from the scanned `discovery_secret` and POSTs his `KeyExchangeInit` there.
- Alice processes **all** `KeyExchangeInit` messages received during the discovery window, establishing one fully independent session per scanner. Each scanner's `EK_B` is fresh and produces a distinct `SK`, so sessions are cryptographically isolated from one another.
- The discovery window closes when Alice dismisses the Add Friend UI (or an implementation-defined timeout). The discovery token MUST be discarded at that point.
- **Multiple-init UX:** When more than one `KeyExchangeInit` is processed in a single discovery window, Alice's UI SHOULD make this visible (e.g. "Added 3 friends from this QR") and SHOULD prompt Safety Number verification for each resulting session. This ensures that a rogue init — which Alice cannot distinguish cryptographically from a legitimate one — produces a visible, verifiable event rather than a silent side-session.
- **Security note:** A malicious server controlling GET response ordering cannot displace a legitimate scanner under this model, because all inits in the mailbox are processed. The server can still withhold a specific `KeyExchangeInit` entirely (DoS — see §2.3), but it cannot cause Alice to silently pair with an attacker *instead of* a legitimate scanner.

### 4.3 Option B: Out-of-Band (URI / Manual)

For situations where QR scanning is impossible (e.g., remote setup over a secure chat), Alice can encode the setup payload as a string URL.

**Format:**
The payload is identical to the QR content defined in §4.2. Alice shares this setup payload via a secure out-of-band channel, typically encoded into a URL. The application supports various URL formats for sharing, including:

- **Custom URI Scheme:** `where://invite?q=<encoded-payload>`
- **Web Link (App Linking):** A dedicated website URL that can trigger app linking (e.g., `https://where.af0.net/invite#<encoded-payload>`).

The `<encoded-payload>` is a URL-safe Base64 representation of the JSON setup payload. Bob clicks the link or manually imports the URL, and the process continues exactly as it would for a QR scan (polling the discovery mailbox).

(The use of a web URL is a small security compromise: if a recipient does not have the application installed, or if deep linking fails for some reason, the URL can leak the session initialization parameters to a compromised or malicious web server. It is, however, a great user convenience: the "fallback" web destination can be used to instruct users on how to install the app.)

### 4.4 Key Agreement (Universal)

Regardless of the discovery mechanism used (QR or URI), Bob generates a fresh ephemeral key pair `EK_B` and computes:

```
SK = X25519(Bob.EK_B.priv, Alice.EK_A.pub)
   = X25519(Alice.EK_A.priv, Bob.EK_B.pub)   // Alice computes identically

// Session fingerprints (session-scoped, not device-scoped)
alice_fp = SHA-256(EK_A.pub)   // 32 bytes
bob_fp   = SHA-256(EK_B.pub)   // 32 bytes

// Safety Number (for out-of-band verification)
safety_number_bytes = HKDF-SHA-256(ikm=SHA-256(lower_EK.pub || higher_EK.pub), salt=null, info="Where-v1-SafetyNumber", length=60)
safety_number = formatSafetyNumber(safety_number_bytes)
```

**Key Confirmation:**

Bob MUST include a key confirmation MAC in his `KeyExchangeInit` to prove he derived the same `SK` as Alice. Without this, a bit-flip or substitution of `EK_B.pub` in transit would cause Alice to compute an incorrect `SK` and stream location data into a void, with neither party detecting the mismatch.

```
K_confirm = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-ConfirmKey", length=32)

key_confirmation = HMAC-SHA-256(key  = K_confirm,
                                data = "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
```

**Public-Key Validation.** All X25519 operations in this protocol (the `SK` derivation above and every DH ratchet step in §8.3) MUST reject low-order or otherwise invalid public keys. An attacker who substitutes a low-order point for `EK_B.pub` (or for a later ratchet `dh_pub`) could otherwise coerce the X25519 output to a known constant (typically all-zero), compromising forward secrecy and enabling key confirmation to pass under attacker-known inputs.

The reference implementation relies on libsodium's `crypto_scalarmult_curve25519`, which returns an error for all 7 canonical low-order points listed in its blacklist (`0`, `1`, the two cofactor-order generators from cr.yp.to/ecdh.html, and `p-1`, `p`, `p+1`). Our wrappers propagate this as an exception (`shared/.../e2ee/CryptoPrimitivesImpl.kt`), which `KeyExchange` and `Session` surface as a session-creation or message-decryption failure. The regression guard lives in `shared/.../e2ee/X25519LowOrderPointTest.kt`.

Implementations using a different X25519 library MUST add an equivalent check: either reject the input against the published low-order point list (RFC 7748 §6.1) or verify in constant time that the X25519 output is not all-zero.

Both parties initialize their Double Ratchet state (§8.2) seeded with a root key derived from `SK`. Alice and Bob expand `SK` over 192 bytes to obtain initial chain keys, the starting root key, and the initial header keys:

```
(chain_key_0 || chain_key_1 || root_key_0 || header_key_0 || header_key_1 || next_header_key) = HKDF-SHA-256(
    ikm  = SK,
    salt = null,
    info = "Where-v1-KeyExchange",
    length = 192
)
// Split as: [0:32] = chain_key_0, [32:64] = chain_key_1, [64:96] = root_key_0, [96:128] = header_key_0, [128:160] = header_key_1, [160:192] = next_header_key.
```

- **Alice:** Uses `send_chain = chain_key_0`, `recv_chain = chain_key_1`, `send_header_key = header_key_0`, `recv_header_key = header_key_1`.
- **Bob:** Uses `send_chain = chain_key_1`, `recv_chain = chain_key_0`, `send_header_key = header_key_1`, `recv_header_key = header_key_0`.
- **Root Key:** Both start with `root_key = root_key_0`.

Initial routing tokens are also derived from `SK`:

```
T_AB_0 = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-RoutingToken" || alice_fp || bob_fp, length=16)
T_BA_0 = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-RoutingToken" || bob_fp || alice_fp, length=16)
```

To prevent leaking Bob's suggested name to the server, Bob encrypts it under a one-shot key derived from `SK`:
```
K_name = HKDF-SHA-256(
    ikm  = SK,
    salt = null,
    info = "Where-v1-SuggestedName",
    length = 32
)
```
```
name_nonce        = random_bytes(12)
name_ct           = ChaCha20-Poly1305-Encrypt(
                        key       = K_name,
                        nonce     = name_nonce,
                        plaintext = UTF-8(suggested_name),   // <= 64 bytes recommended
                        aad       = EK_A.pub || EK_B.pub     // binds ciphertext to this session
                    )
encrypted_name    = name_nonce || name_ct
```

Bob transmits:
```json
{
  "v": 1,
  "type":             "KeyExchangeInit",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "encrypted_name":   "<base64, 12-byte nonce || ChaCha20-Poly1305 ciphertext>",
  "key_confirmation": "<base64, key_confirmation>"
}
```

Bob POSTs this to `POST /inbox/{hex(discovery_token_A)}`.

**Alice's processing:**

Alice receives the `KeyExchangeInit` and:

1. Derives `SK = X25519(Alice.EK_A.priv, Bob.EK_B.pub)`.
2. Recomputes the expected `key_confirmation`.
3. **Aborts and discards** if the MAC does not match — this indicates `EK_B.pub` was corrupted or substituted in transit.
4. Derives `K_name = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-SuggestedName", length=32)`.
5. Decrypts `encrypted_name` using `ChaCha20-Poly1305-Decrypt` with key `K_name`, nonce `name_nonce` (the first 12 bytes), ciphertext `name_ct` (the remaining bytes), and AAD `EK_A.pub || EK_B.pub`. If decryption fails, Alice **MUST abort and discard** the session.
6. Derives `alice_fp`, `bob_fp`, `T_AB_0`, `T_BA_0` using the same formulas above.
7. **Deletes `EK_A.priv` immediately.**
9. Prompts user to name Bob (pre-filled with the decrypted `suggested_name` from `KeyExchangeInit`).
10. **Eager Ratchet (Deadlock Breaker):** To prevent the session from being stuck in the initial symmetric chain (Epoch 0), Alice immediately generates a new DH keypair (`A1`) and performs one DH ratchet step using `EK_B.pub` before returning the session. This ensures her very first location message is sent in Epoch 1. When Bob receives this message, he will observe the new `A1` and perform his own DH ratchet step, completing the transition to a fully ratcheted state. This eager approach is a deliberate deadlock breaker; while a Keepalive mechanism (§5.3) provides an alternative path for rotation, the implementation chooses this eager transition to ensure post-compromise security from the first message.
11. Stores the session.

Bob **does not delete `EK_B.priv` immediately** after posting the `KeyExchangeInit`. Instead, it is copied into `localDhPriv` in the session state — Bob needs it to perform his first DH ratchet step when he receives Alice's eager-ratchet message (§4.4 step 10). The original `EK_B.priv` buffer is zeroed after the copy. `localDhPriv` is deleted (zeroed) after Bob completes that first ratchet step.

---

## 5. Ratchet Design for Bidirectional Sessions

This protocol uses a standard **Double Ratchet** session per friendship. This provides both per-message forward secrecy and post-compromise security (PCS).

### 5.1 The Double Ratchet

The Double Ratchet algorithm ([Signal spec](https://signal.org/docs/specifications/doubleratchet/)) combines two mechanisms:

1.  **Symmetric-key ratchet (KDF chain):** Each message advances a hash chain. Given a chain key `CK`, each message derives `(CK', MK, nonce)` where `CK'` replaces `CK` for the next message, `MK` is the per-message encryption key, and `nonce` is derived deterministically from the same KDF step. Deleting old keys provides forward secrecy.

2.  **Diffie-Hellman ratchet:** Each message carries the sender's current DH ratchet public key. When a new DH public key is received from the peer, both parties perform a DH calculation to refresh the root key and re-initialize the symmetric chains. This provides post-compromise security: if chain keys leak, the next DH ratchet step heals the session.

### 5.2 Unified Messaging Model

In this model, there is no distinction between "Alice sharing with Bob" and "Bob sharing with Alice" at the cryptographic layer. Every friendship is a single bidirectional communication channel.

Each peer sends messages to the other's inbox. A message is one of:
-   A **Location Update**: contains coordinates and accuracy, with an optional `stationary` flag.
-   A **Keepalive**: contains an empty payload.
-   A **Stopped-Sharing** notice: signals a deliberate end of the share session.

All three use the same unified wire format and all carry the sender's current DH ratchet public key in the header. The `stationary` flag and `StoppedSharing` variant are described in §9.1; their UI semantics, the rule that "stop sharing" continues emitting Keepalives, and the forward-compatibility behavior for unknown message types are described in §5.7.

### 5.3 Gap-Filling and Multi-Epoch Reliability

To handle out-of-order delivery across DH ratchet steps, the receiver maintains a **skipped message key cache** (§5.5). When a receiver observes a gap in sequence numbers, they derive the missing keys and store them for future use.

**Out-of-Order DH Epoch Transitions:**
If Alice ratchets her DH key from `dh_1` to `dh_2`, Bob may receive `Msg(dh_2, seq=1)` before he receives `Msg(dh_1, seq=last)`.
1.  **Speculative Ratchet:** Bob moves to the new DH epoch upon receiving `Msg(dh_2)`.
2.  **Decryption:** Bob decrypts the payload of `Msg(dh_2)` to extract the **encrypted `prev_chain_len` field** (§7.4).
3.  **Gap Filling:** The `prev_chain_len` field tells Bob exactly how many messages Alice sent in epoch `dh_1`. Bob goes back to the old chain, derives all remaining keys up to `prev_chain_len`, and stores them in the cache.
4.  **Historical Delivery:** When `Msg(dh_1, msg_num=last)` eventually arrives, Bob retrieves the key from the cache, verifies the AAD, and decrypts.

This ensures that gaps are filled deterministically even when the metadata needed for gap calculation is hidden behind the AEAD boundary.

**Scope:** the cross-epoch reorder guarantee above applies to messages that arrive *in the same batch* (`decryptAndSort` orders previous-epoch frames before current-epoch ones, and the cache lookup uses a pre-decrypted header — see §8.3.1). A previous-epoch straggler that arrives in a *later* poll than the one that triggered the ratchet will fail `tryDecryptHeader`, because the receiver retains only the current and next receive header keys (§8.3.1(6)) — the previous epoch's receive header key is discarded on DH ratchet. For location samples this is invisible (next update supersedes); for sticky state transitions (`stop`, `stationary`) it can produce a brief UI glitch until the sender's next message arrives. Closing this gap would be a purely local state addition (retain `prev_recv_header_key` for a bounded window) — no wire change — and can be added later if it proves to matter in practice.

### 5.4 Routing Token Rotation and Reliability (Epoch Transitions)

Routing tokens are derived from the current root key. Whenever the DH ratchet advances and a new root key is derived, new routing tokens are computed.

A peer's mailbox state is identified by its DH public key `dh_pub`, which serves as the canonical epoch identifier. Epoch numbers may exist as an internal convenience, but they are derived from DH ratchet progression and must never be treated as an independent source of truth.

**The Mailbox Synchronization Challenge:**
In an anonymous mailbox model, a client polling token `T_old` will never see a message posted to `T_new`. The protocol resolves this with a **transition-message rule** on the send side and a **root-key-advance observation** on the retirement side, rather than a two-token polling window on the receive side.

#### 5.4.1 Send Rule (Transition Message on Old Token)
The first message of a new sending epoch (`seq == 1`) MUST be posted to the *previous* send token and encrypted under the *previous* send header key. All subsequent messages in that epoch go to the new send token under the new send header key.

This is symmetric by construction: `prev_send_token` is derived from the pre-ratchet root key, which is exactly the token the peer is currently polling for messages from us. When the peer decrypts the transition message, the new `dh_pub` triggers a DH ratchet step that derives a new `recv_token` matching our new `send_token` — so the next message we post will land on the token the peer has rotated to.

Implementation: `Session.encryptMessage` selects `prevSendHeaderKey` and `prevSendToken` when `sendSeq + 1 == 1` (`Session.kt:36-41`; `E2eeManager.kt:274`).

#### 5.4.2 Receive Rule (Single Active Token)
The receiver polls exactly one mailbox token at a time (the current `recv_token`). There is no `prev_recv_token` polling — the sender's transition rule (§5.4.1) routes the first new-epoch message onto the token the receiver is already polling. After processing that message, the receiver ratchets forward and switches its polling target to the new `recv_token`.

When a decrypted message carries a new sender `dh_pub`:
1. Speculatively perform the DH ratchet step (§8.3.1(4) requires this to be committed only after body AEAD authentication succeeds — but see §8.3.1(4) note for the deliberate failed-body liveness deviation).
2. Update `recv_token` to the value derived from the new root key.

#### 5.4.3 Send-Side Retirement (Observed Root-Key Advance)
The sender retires its `prev_send_token` (and stops posting fallback retries to it) when it observes an authenticated proof that the peer has processed its new DH key. The proof used here is a successful decryption whose post-ratchet `root_key` is strictly newer than the one in effect when those outbox entries were enqueued — exactly the property an authenticated ack-of-DH would have established, inferred directly from the ratchet instead.

Implementation: `E2eeManager` clears outbox entries still targeting `prevSendToken` once `decryptBatch` returns a session with an advanced `rootKey` (`E2eeManager.kt:433-438`).

`ack_remote_dh_pub` is carried in the header, authenticated, and bound into the message AAD (`buildMessageAad`) so its value cannot be modified in flight. The implementation does not currently read it for any retirement decision — retirement is fully driven by observed root-key advance. The field is retained on the wire for backwards compatibility and to leave room for a future ack-driven retirement scheme without a wire break.

> **v2 candidate:** removing `ack_remote_dh_pub` would shave 32 bytes per message and drop a vestigial primitive, but it is both a wire break and an AAD break (old and new clients cannot decrypt each other's messages), so it can only ship as part of a forced re-pair / protocol-version bump. Reconsider at the next v2 break; until then, keep.

#### 5.4.4 Duplicate Handling and Batch Ordering
**Duplicate Handling:**
Duplicates MUST be ACKable. If a peer receives multiple copies of the same transition message, the first advances state. Subsequent duplicates must not poison the batch and should still allow the receiver to generate the authenticated ACK needed to drain the old queue.

**Header-Undecryptable Frames:**
Frames that fail header decryption (both `header_key` and `next_header_key` fail) are silently skipped — not ACKed and not deleted. They remain in the server queue and are re-fetched on subsequent polls. Clients MUST NOT delete them immediately: the client cannot distinguish a corrupted frame from a genuine future-epoch message it has not yet ratcheted to.

To prevent header-undecryptable frames from permanently filling the server's 50-message GET window, clients MUST force-ACK (delete) an entire batch after `MAX_SILENT_DROP_RETRIES` consecutive polls in which no message from that batch could be processed. At a 30-second poll interval and the default of 5 retries, the maximum starvation window is approximately 2.5 minutes.

Note: a duplicate transition message arriving after the ratchet has already advanced is header-undecryptable (the previous epoch's receive header key has been discarded). It will be cleared by the force-ACK mechanism above. If the queue contains a mix of decryptable and undecryptable frames, successfully decryptable frames reset the retry counter, so an undecryptable duplicate may linger longer — but since it is not blocking progress it only occupies a queue slot until it is eventually force-ACKed or ages out (7 days, §10.2).

**Batch Ordering:**
When processing a batch of messages already retrieved from the server, clients SHOULD process older epoch classes before newer ones, then lower `prev_chain_len`, then lower `msg_num`.

#### 5.4.5 Receive-Side Crash Safety

The GET → process → DELETE sequence is the receive-side analogue of the send-side transactional outbox:

1. `GET /inbox/{token}` returns messages; the server retains them.
2. The client decrypts, updates session state, and **durably saves** the new `recvToken` and ratchet state to local storage.
3. The client calls `DELETE /inbox/{token}?ids=...` to release the messages from the server.

If the client crashes between steps 1 and 2, it re-fetches the same messages on next startup (server still holds them) and re-processes them from the same prior session state — idempotent and correct. If it crashes between steps 2 and 3, the session is already saved with the new `recvToken`; the leftover messages on the server are never fetched again and expire after TTL.

**What this does NOT fix:** If the server loses a message before the client ever GETs it (e.g., server-side storage corruption), the token-transition message is gone and the session will permanently desync. This is a server reliability problem, not a client atomicity problem. It is qualitatively different from the crash-recovery case: it produces an obvious total communication failure rather than a silent one-sided desync, because the sender will also fail to post subsequent messages during the outage.

**Genuine data loss cases** that still require manual re-pairing:
- Client-side storage corruption (flash failure, encrypted storage key loss)
- User-initiated app data deletion or device wipe
- Session JSON schema migration bug

### 5.5 Storage and Memory Hygiene

To maximize forward secrecy, implementations should adhere to the following hygiene rules:

1.  **Zero-after-use:** All temporary buffers used for KDF inputs (`DH_out`, `SK`, `RK`, `CK_n`) MUST be explicitly zeroed immediately after the derived key is computed.
2.  **Delete-after-use:** Each message key `MK_n` MUST be deleted (zeroed) from the `skipped_message_keys` cache immediately after successful decryption.
3.  **Persistence Policy:** 
    - Full `SessionState` (including `localDhPriv`) is persisted to local storage to ensure session stability across app restarts and crashes.
    - **Initial State Hygiene:** Bob's initial ephemeral private key (`ekB.priv`) is copied into `localDhPriv` in the `SessionState` to enable the first DH ratchet step when Alice responds. The original buffer is zeroed immediately after session initialization.
    - **Bootstrap window security implication:** Between Bob posting `KeyExchangeInit` and completing his first DH ratchet step, `localDhPriv` holds a copy of `EK_B.priv`. During this window, an attacker who recovers Bob's device state (e.g., via a backup) *and* has access to the public QR payload (which contains `EK_A.pub`) can reconstruct `SK = X25519(EK_B.priv, EK_A.pub)` and derive all session keys. This window closes as soon as Bob processes Alice's first message and ratchets forward. The QR payload should be treated as sensitive for the duration of the pairing interaction.
    - To mitigate backup-recovery risks, this state MUST be stored using device-local, backup-excluded security controls (e.g., `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` on iOS, `KeyStore`-backed encryption with `allowBackup=false` on Android).
    - **Android 9+:** Use `setIsStrongBoxBacked(true)` if available. If hardware is unavailable, fall back to TEE-backed Keystore with `setUserAuthenticationRequired(true)` and ensure the manifest has `allowBackup=false`.
    - **JVM Memory Hygiene:** On the JVM, `Arrays.fill()` is inherently limited by garbage collector behavior, which may relocate byte arrays and leave stale copies in memory. For improved hygiene, consider using off-heap storage (`DirectByteBuffer`), Conscrypt's `SecretKey` wrappers, or native bindings to libsodium (which handles zeroization natively).
    - **Sequence Safety:** If the message number `send_msg_num` reaches its 64-bit maximum (`Long.MAX_VALUE`), the session is considered "bricked" (`SessionBrickedException`) and no further messages can be sent. This prevents nonce reuse. A full out-of-band session reset is required.
    - If a device backup is nonetheless recovered by an attacker, the forward secrecy guarantee is reduced to the current DH epoch. Per-message forward secrecy is maintained against a server-side observer who does not have access to the device's persistent store.

**Keychain backup and state-rollback risk:** Platform keychains may be backed up (iCloud Keychain on iOS, Google Play Backup on Android). If a backup is restored to a different device or is compromised, the attacker gains access to the stored root key and can re-derive future message keys from the backed-up epoch forward — until the next DH ratchet step heals the session.

### 5.6 Security Consequences of `localDhPriv` Compromise

If an attacker obtains a copy of a user's persistent session state (e.g., via a compromised device backup), they gain access to the active `localDhPriv` and the current `rootKey`.

#### 5.6.1 What is Disclosed?

*   **Selective Disclosure of Future Messages:** The attacker can decrypt all **incoming** messages sent by the peer that target the compromised `localDhPub`. They can also decrypt all **outgoing** messages sent by the compromised device for the current symmetric chain.
*   **Integrity Violation:** The attacker can forge valid-looking messages to the peer, as they possess the necessary symmetric secrets.
*   **Historical Forward Secrecy Holds:** All **past** messages remain secure. Because symmetric chain keys (`CK`) are advanced and deleted after every message (§5.5), an attacker gaining access to the current state cannot retroactively decrypt historical traffic.

#### 5.6.2 Session Self-Healing (PCS)

The Double Ratchet protocol is designed to "self-heal" over time. A session recovers from a one-time compromise through subsequent DH ratchet steps:

1.  **Initiation:** The compromised side (Alice) sends a message or keepalive carrying a **new** `localDhPub`.
2.  **Ratchet:** The peer (Bob) receives this new key, performs a DH step to move his root key to a new epoch, and responds with a message targeting Alice's new key.
3.  **Recovery:** Once Alice receives Bob's response and ratchets her own root key, the old compromised `localDhPriv` is no longer used in root key calculations. The secrets known to the attacker are now "stale."
4.  **Full PCS:** The session is fully healed once both parties have contributed fresh entropy to the root key derivation. An attacker who only possessed the state at timestamp `T_compromise` cannot derive the new root key at `T_healed`.

#### 5.6.3 Risk Limitation: Symmetric-Chain Gaps

For threat models where an attacker might have continuous read-access to the outbox (e.g., a server-side mirror of the mailbox), the session may never fully heal if the attacker can immediately use the compromised root key to stay ahead of the ratchet. This is mitigated by the **mailbox-polling model**: as long as the legitimate user rotates their ratchet keys, an attacker would need to maintain persistent, real-time access to the device's secure enclave to keep up.

Mandatory mitigations:
- **iOS:** Mark all session-state keychain items with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. This attribute excludes the item from iCloud Backup.
- **Android:** Store session state in `EncryptedSharedPreferences` backed by a Keystore key created with `setIsStrongBoxBacked(true)` and `allowBackup=false` in the manifest.
- **Both:** On detecting a fresh install or that session state is missing/invalid (e.g., root key absent), invalidate the session and initiate re-keying with all affected friends rather than accepting a potentially stale backup.

### 5.7 Sharing-State Semantics and Forward-Compatibility

The plaintext message space carries three distinguishable variants today (`Location`, `Keepalive`, `StoppedSharing`) plus an optional `stationary` flag on `Location`. These exist so the recipient can distinguish three otherwise-indistinguishable "silence" cases: peer parked and OS-suspended, peer deliberately stopped sharing, or peer crashed / lost connectivity.

#### 5.7.1 Stationary Flag

A `Location` carrying `stationary: true` is a signal that the sender does not expect to move and may go quiet (typically because the OS is about to suspend the app). The next `Location` without the flag implicitly clears the state on the recipient. The flag MUST be treated as advisory only — receivers without UI support for it MUST render the message as a normal `Location` update.

#### 5.7.2 StoppedSharing — Semantics

A `StoppedSharing` message signals the deliberate end of a share session (manual toggle-off, or expiry of a time-limited share — the protocol does not know which). Receivers SHOULD reflect a terminal state in their UI for some bounded window, then suppress the peer's pin entirely.

**Critical sender rule: "stop sharing" means "stop sending Locations," not "stop talking."**
After emitting `StoppedSharing`, the sender MUST continue its normal Keepalive cadence. The 7-day inactivity window (§7.2) tears down a peer's session entirely once `lastRecvTs` exceeds the timeout; if a sender both stopped Locations *and* stopped Keepalives, every recipient would silently expire the session within a week and lose all post-compromise security guarantees on the channel. Resuming sharing later would require fresh pairing. Keepalives also continue to drive the DH ratchet forward, preserving PCS during the quiet period.

#### 5.7.3 Forward-Compatibility — Unknown Message Types

The plaintext JSON object carries an explicit `"type"` discriminator (`"loc"`, `"ka"`, `"stop"`). To preserve room for future variants without coordinated rollout, **receivers MUST treat any unknown `"type"` value as a Keepalive** — decode the message, advance the ratchet, ACK it, but produce no user-visible side effect. This makes new senders monotonically deployable: older receivers see future variants as keepalives (a safe no-op) rather than dropping them, which would otherwise stall the ratchet and corrupt session state.

Every future variant added to this protocol must therefore round-trip safely through every prior client version. A variant that older clients would mis-decode (or that would break the ratchet) is not a valid extension.

---

## 6. Group Broadcasting — Per-Friend Keys

### 6.1 The Problem

In Signal's 1:1 model, each party has a single Double Ratchet session. Where has a 1:N model: Alice broadcasts her location to N friends. She must encrypt separately for each friend, because:

1. Different friends may be at different ratchet epochs (e.g., Bob has been offline for 2 hours).
2. Group key schemes (like MLS) require all-to-all consistency. In Where, the "group" is implicitly defined by Alice's friend list and changes as Alice adds/removes friends. There is no need for a full group key agreement round.
3. Using a single group key would mean that any one friend's device compromise exposes Alice's location to everyone — a disproportionate blast radius.

### 6.2 Per-Friend Symmetric Sessions

The recommended approach is straightforward: **Alice maintains one independent ratchet session per friend.**

When Alice sends a location update:
1. She computes the plaintext: `loc = {lat, lng, accuracy, timestamp}`.
2. For each friend `F_i` in her friend list:
   - Derive `MK_i` from Alice→F_i ratchet chain.
   - Encrypt: `CT_i = ChaCha20-Poly1305(key=MK_i, plaintext=loc, aad=buildAad(alice_fp_i, bob_fp_i, msg_num_i, dh_pub_i, ack_remote_dh_pub_i))` where `alice_fp_i` and `bob_fp_i` are the session fingerprints for friendship i (see §8.3).
   - Post to `T_send_i`.
3. The server routes each `(token_i, CT_i)` frame to the corresponding mailbox.

**Bandwidth:** For N friends, Alice sends N encrypted frames per location update. At 30-second intervals with 20 friends and ~100 bytes per ciphertext, this is ~2,000 bytes per update — entirely within mobile data budgets.

**Comparison to group key schemes:**

| Approach | Bandwidth | Blast radius on key compromise | Implementation complexity |
|---|---|---|---|
| Per-friend ratchet (recommended) | O(N) per update | Single friend | Low |
| Signal Sender Keys | O(1) send + O(N) distribute | All current group members | Medium |
| MLS (RFC 9420) | O(log N) tree ops | Single member | High |

Signal's Sender Keys protocol would reduce Alice's outbound bandwidth to O(1) — she encrypts once and sends one ciphertext — but the server must then fan it out, and all members share a forward-ratcheting sender key. One compromised recipient can derive past messages from the shared chain. For Where's small group sizes (typically < 50 friends), O(N) per-friend encryption is the right tradeoff: simpler, stronger blast-radius isolation, no group-state synchronization needed.

MLS (RFC 9420) is the gold standard for large groups and achieves O(log N) key operations. It is also vastly more complex to implement correctly, requires tree state synchronization, and is overkill for groups of tens of users.

**Scalability note:** For N ≤ 50 friends, per-friend encryption is practical — deriving 50 message keys per location update is sub-millisecond on modern mobile hardware, and 20-friend sessions require ~8 KB per 30-second cycle.

### 6.3 Handling Friend Add/Remove

**Adding a friend:** When Alice adds Bob as a friend, she runs the key exchange (§4) and initializes a new ratchet session seeded from the resulting `SK`. There is no effect on other friends' sessions.

**Removing a friend:** Alice removes the ratchet session state for Bob. Since she was encrypting separately for each friend, Bob's removal immediately stops the flow of ciphertext addressed to him. He cannot recover future location updates.

**Important:** Removing Bob does not provide cryptographic protection against Bob's having cached past location updates. If Bob logged Alice's decrypted locations before being removed, there is no technical mechanism to prevent that. This is a property of the application layer (consent model), not the cryptographic protocol.

**Multi-device note:** This design does not address multi-device friend-list synchronisation (deferred to §12). Friend additions and removals are per-device: a user with two devices may have divergent friend lists, causing different friends to receive location from each device. Multi-device support with synchronised friend lists is planned for future work.

---

## 7. Server's Role and Obliviousness

### 7.1 The Mailbox Model

The server's role is strictly limited to acting as a stateless message router for anonymous mailboxes.

1. **Routing Token (T):** A random-looking 16-byte token derived pairwise by clients (§4.2).
2. **Mailbox API:**
   - `PUT /inbox/{token}/{msgId}`: Clients push an encrypted payload into the mailbox. **Idempotent.**
   - `GET /inbox/{token}`: Clients poll for pending payloads. **Non-destructive:** messages are retained until explicitly deleted.
   - `DELETE /inbox/{token}/{msgId}`: Clients confirm receipt of a specific message by its ID. **Idempotent.**
3. **Server Obliviousness:** The server does not know the sender or recipient identity—only the opaque routing token.

**Receive atomicity (§5.4.1):** The three-step GET → save → DELETE sequence ensures that a client crash between receiving messages and persisting session state does not cause permanent token desync. On restart, the unACK'd messages are still available for re-processing from the same session state.

### 7.2 Invariant: Indistinguishable Responses

To prevent a network attacker from learning whether a routing token corresponds to a real relationship, the following invariant is mandatory:

**The server MUST return an identical response body for all token queries where no messages are pending, regardless of whether the token has ever been used.**

There is no "create mailbox" or "register token" step. Mailboxes exist implicitly upon the first `POST`. A `GET` for a non-existent token MUST be indistinguishable at the API level from a `GET` for an empty real mailbox.

### 7.3 Metadata Exposure and Traffic Analysis

The server (or a network attacker) can still observe the timing and frequency of `POST` and `GET` requests for specific tokens. IP correlation can be used to infer relationships over time.

### 7.4 Mitigations

#### 7.4.1 Metadata Obfuscation: Hidden Chain Metadata

Standard Double Ratchet protocols leak the message sequence number (`msg_num`) and length of the previous symmetric chain (`prev_chain_len`) in the unencrypted header. This allows a server to observe activity patterns (e.g., "Alice sent 142 messages in her last 30-minute epoch").

To mitigate this, Where moves all session metadata into the **encrypted envelope header** (§9.1.1):
1. **Encrypted Header:** The envelope header includes `dh_pub`, `ack_remote_dh_pub`, `msg_num`, and `prev_chain_len`.
2. **Post-Decryption Extraction:** The receiver first decrypts the envelope header using the current or next `header_key` and *then* reads the metadata to perform historical gap-filling of the old chain (§5.3).
3. **Cache Storage:** When storing skipped keys for out-of-order delivery, the receiver stores `(MK_n, Nonce_n)` plus an insertion timestamp for age-based eviction. `prev_chain_len` is not included in the AAD (`buildMessageAad`), so it is not retained in the cache entry.

This removes session-related metadata from the server's payload view, but it does not eliminate timing, IP correlation, or polling-pattern leakage.

#### 7.4.2 Payload padding

- **Payload padding (mandatory):** All payloads MUST be padded to a fixed length (512 bytes recommended) before encryption. 256 bytes is insufficient: a JSON location payload plus GCM overhead already approaches ~150 bytes, leaving little headroom for variable-length fields. 512 bytes provides comfortable clearance while remaining a small fixed multiple of a cache line.

#### 7.4.3 Polling Strategy

To prevent timing-based social-graph inference, Bob SHOULD poll at a roughly constant rate regardless of whether messages are expected. Polling more frequently when a location update is expected, or less frequently when offline, creates a timing side-channel the server can exploit to infer when friends are actively sharing. Implementations that deviate from constant-rate polling — due to battery optimizations, foreground/background transitions, or movement-triggered updates — increase the risk that the server can infer presence, movement state, or session activity from polling patterns alone.

**Polling cadence is a UX/battery parameter, not a cryptographic one.** The polling interval should be set based on freshness requirements and battery budget. A 60–120 second poll interval provides acceptable location freshness for a mapping application without the battery drain of 10-second polling, and without revealing fine-grained app-foreground state to the server. Recommended default: **60 seconds**.

Bob polls for all of his friendship tokens in a fixed, shuffled order. The shuffle MUST be re-randomised on each poll cycle to prevent ordering-based inference. This, combined with the indistinguishable-response invariant (§7.2), means the server cannot distinguish "polling for a real active friendship" from "polling for a stale or never-used token".

### 7.5 Future: Dummy Token Polling

Because of the indistinguishable response invariant (§7.2), clients can implement **dummy token polling** as a future enhancement. A client polls for random "dummy" tokens alongside real ones. The server cannot distinguish real polls from noise. This significantly raises the bar for traffic analysis and requires no server-side changes to implement.

**Dummy polling is deferred to a future release** and MUST NOT be described as currently available. Cover traffic provides no meaningful privacy benefit until the user base is large enough that dummy polls are indistinguishable from real sessions at the population level; premature deployment creates implementation complexity without the corresponding privacy gain.

---

## 8. Concrete Protocol Recommendation

### 8.1 Key Types and Operations

| Purpose | Algorithm | Key size | Notes |
|---|---|---|---|
| Bootstrap DH (Alice) | X25519 (`EK_A`) | 256-bit | Ephemeral, generated per invite; deleted after SK derivation |
| Bootstrap DH (Bob) | X25519 (`EK_B`) | 256-bit | Ephemeral, generated per QR scan; deleted after SK derivation |
| Ratchet DH | X25519 (`RK`) | 256-bit | Generated per DH ratchet step; private key deleted after DH |
| Root KDF | HKDF-SHA-256 | 256-bit output | Inputs: DH output + current root key |
| Chain KDF | HKDF-SHA-256 | — | Advancing symmetric ratchet |
| Message encryption | ChaCha20-Poly1305 | 256-bit | Per-message key; deleted after use |
| Message authentication | ChaCha20-Poly1305 tag | 128-bit | Included in AEAD output; covers AAD |
| Key exchange KDF | HKDF-SHA-256 | — | `info = "Where-v1-KeyExchange"` (initial SK) |
| Initial routing tokens | HKDF-SHA-256 | 16-byte output each | pair-wise derived from SK and fingerprints (§4.2) |
| Discovery token | HKDF-SHA-256 | 16-byte output | `ikm = discovery_secret` (32-byte random, from QR payload), `salt = 0x00*32`, `info = "Where-v1-Discovery"` (§4.2) |

### 8.2 Session State Per Friend-Pair

Each friendship maintains a standard Double Ratchet state:

```
SessionState {
  root_key:           [32]byte        // current root key
  send_chain_key:     [32]byte        // CK for outgoing messages
  recv_chain_key:     [32]byte        // CK for incoming messages
  send_token:         [16]byte        // token for highest_peer_dh_pub_seen
  recv_token:         [16]byte        // token for current local ratchet key
  send_msg_num:       uint64          // sender chain message number
  recv_msg_num:       uint64          // highest received msg_num in the current epoch (for replay rejection)
  highest_peer_dh_pub_seen: [32]byte  // highest peer DH pub key successfully processed
  local_dh_pub:       [32]byte        // current local ratchet public key
  remote_dh_pub:      [32]byte        // last received remote ratchet public key
  alice_ek_pub:       [32]byte        // bootstrap public key EK_A.pub (stable)
  bob_ek_pub:         [32]byte        // bootstrap public key EK_B.pub (stable)
  alice_fp:           [32]byte        // SHA-256(EK_A.pub) (stable)
  bob_fp:             [32]byte        // SHA-256(EK_B.pub) (stable)
}
```

**Note on Alice's Initial State:** Due to the eager ratchet step performed in `aliceProcessInit` (§4.4), Alice's initial session state will already have `isSendTokenPending = true` and `local_dh_pub` set to $A_1$. Bob's initial state remains in Epoch 0 until he receives Alice's first message.

### 8.3 Ratchet Step Functions

**KDF_RK (Diffie-Hellman ratchet step):**
```
(new_root_key, new_chain_key, new_header_key) = HKDF-SHA-256(
    salt = current_root_key,
    ikm  = X25519(dh_priv, dh_pub),
    info = "Where-v1-RatchetStep",
    length = 96
)
```
Output: 96 bytes split as `[0:32] = new_root_key`, `[32:64] = new_chain_key`, `[64:96] = new_header_key`.

**Routing Token Derivation:**

After each DH ratchet step, new routing tokens MUST be derived from the new root key:
```
new_token = HKDF-SHA-256(
    ikm  = root_key,
    salt = null,
    info = "Where-v1-RoutingToken" || sender_fp || recipient_fp,
    length = 16
)
```
Direction is encoded explicitly via the `sender_fp || recipient_fp` ordering in the info field.

**KDF_CK (symmetric ratchet step):**
```
message_key     = HMAC-SHA-256(key = current_chain_key, data = 0x01)
next_chain_key  = HMAC-SHA-256(key = current_chain_key, data = 0x02)
message_nonce   = HKDF-SHA-256(ikm = message_key, salt = null, info = "Where-v1-MsgNonce", length = 12)
```
The message key is used for AEAD encryption of the payload, and the nonce is derived from it to ensure uniqueness without needing a separate per-message nonce counter in the wire format.

The `dh_pub` is included in the AAD to cryptographically bind the message to the current DH epoch.

### 8.3.1 Ordering, Replay, and Chain Advancement

Each message frame carries a `msg_num` counter. Recipients enforce:

1.  **Replay rejection:** Any frame with `msg_num <= max_msg_num_received` (within the same DH epoch) is dropped, EXCEPT if the key for that message number is present in the **skipped message key cache**.
2.  **Maximum gap:** recipients MUST reject a frame whose same-epoch gap would cause the skipped-key cache to exceed `MAX_SKIPPED_KEYS` (1,000). This pre-check fires before any key derivation begins, bounding the HMAC work a malicious server can force to ~1,000 operations per frame. The server cannot inflate `msg_num` beyond what a genuine sender transmitted (the field is inside the encrypted header), so it can only replay or withhold genuine frames.
3.  **OutOfOrder Support:** If a message is skipped (e.g., recipient receives `msg_num=10` after `msg_num=8`), the recipient advances the symmetric ratchet to `msg_num=10` and stores the intermediate message keys in a bounded cache (1,000 entries, `MAX_SKIPPED_KEYS`). The bound is sized to comfortably absorb a full `MAX_MESSAGES_PER_POLL = 500` backlog with headroom.

    **Cross-epoch gap-filling:** when a new-DH-epoch frame arrives, the receiver also fills in skipped keys for the *previous* epoch's chain up to `prev_chain_len`. This fill is uncapped and evicts oldest cache entries if the combined cache overflows. If a peer sent more messages in the previous epoch than fit in the remaining cache, those skipped keys are silently lost and late-arriving stragglers for that epoch will be undeliverable.
4.  **Transactional Commitment:** The receiving state (receiving chain, root key, skipped keys) MUST only be updated if the message AEAD authentication succeeds. The receiving state MUST not be committed earlier.

    *Exception:* if the header authenticated but the body AEAD failed, the receiver MUST still advance `recv_msg_num` and the chain key. Without this, a server-dropped message and a server-corrupted message would leave different ratchet states, causing permanent DH desync. The failed message's key MUST NOT be cached — caching it confers no robustness benefit (a server willing to corrupt a message can equally drop it) and unnecessarily extends the key's lifetime.
5.  **Epoch Transition:** When a message with a new `dh_pub` is received, the `msg_num` counter resets to 0. All skipped message keys belonging to epochs older than the *previous* valid epoch MUST be cleared.
6.  **Across-Epoch Replay:** Recipients hold only two receive header keys at any time — the current epoch's `header_key` and the next epoch's `next_header_key`. The previous epoch's receive header key is discarded on DH ratchet (`Session.performDhRatchet`). A replayed frame from a retired epoch therefore fails `tryDecryptHeader` and is dropped before any ratchet logic runs, with no dedicated `retired_dh_pubs` set required. Within-epoch replay is caught by the `msg_num <= recv_msg_num` check plus the single-use skipped-key cache.


---

## 9. Wire Format

All messages are JSON-encoded. Every message MUST include a top-level `"v"` field set to the current protocol version (currently `1`). This enables recipients to reject messages from incompatible future versions.

### 9.1 Encrypted Location Frame

The mailbox payload for a standard location update is a JSON object with `type: "EncryptedMessage"`. Metadata (`dh_pub`, `msg_num`, `ack_remote_dh_pub`, etc.) is hidden within an encrypted `envelope` to prevent server correlation.

```json
{
  "type": "EncryptedMessage",
  "v": 1,
  "envelope": "base64(...)",
  "ct": "base64(...)"
}
```

#### 9.1.1 Envelope Structure

The `envelope` is a 110-byte binary blob consisting of:
1. **Nonce (12 bytes)**: A random nonce used for header encryption.
2. **Encrypted Header (98 bytes)**: ChaCha20-Poly1305 ciphertext of the metadata.

**Header Plaintext (82 bytes):**
- `PROTOCOL_VERSION` (1 byte, `0x01`)
- `dh_pub` (32 bytes): sender’s current DH public key.
- `ack_remote_dh_pub` (32 bytes): the newest peer DH public key for which at least one message has been successfully authenticated and committed to session state (see §5.4.3).
- `msg_num` (8 bytes, big-endian Long): sender chain message number.
- `prev_chain_len` (8 bytes, big-endian Long): length of the previous sending chain.
- `flags` (1 byte): optional flags, such as `TRANSITION` (0x01).

The header is encrypted using the current `header_key` derived from the DH ratchet.

**AAD for Header:**
- `alice_fp` (32 bytes): Alice's session fingerprint (constant for the lifetime of the session).
- `bob_fp` (32 bytes): Bob's session fingerprint (constant for the lifetime of the session).

This binds each header ciphertext to its session, preventing a party who has obtained a header key from detaching and re-attaching headers across sessions.

#### 9.1.2 Ciphertext (ct)

The `ct` field contains the ChaCha20-Poly1305 ciphertext of the location payload, plus a 16-byte authentication tag.

**AAD for Ciphertext:**
- `AAD_PREFIX` ("Where-v1-Message")
- `PROTOCOL_VERSION` (4 bytes, `0x01`)
- `sender_fp` (32 bytes)
- `recipient_fp` (32 bytes)
- `msg_num` (8 bytes, big-endian uint64)
- `dh_pub` (32 bytes)
- `ack_remote_dh_pub` (32 bytes)

Implementations MUST parse the `msg_num` field as a uint64 integer and serialize it as 8 bytes in big-endian order for AAD computation. Never hash the decimal JSON string directly.

Note that even though metadata is hidden from the server in the envelope, the client uses the *decrypted* values to verify the body AAD, ensuring the body and header are cryptographically bound together.

**Plaintext (before encryption):**
The plaintext is a JSON object. All plaintext payloads MUST be padded with 0x80 then 0x00 bytes to a fixed 512-byte length **before** encryption. The padding is included in the plaintext and authenticated by the AEAD tag.

Every plaintext object carries a string `"type"` discriminator identifying its variant. Receivers MUST dispatch on `"type"` and MUST treat any unknown value as a Keepalive (see §5.7.3 for the forward-compatibility contract). Receivers MUST also accept the legacy schemas defined below for backwards-compatibility.

For a **Location Update** (`"type": "loc"`):
```json
{
  "type": "loc",
  "lat": 37.7749,
  "lng": -122.4194,
  "acc": 15.0,
  "ts":  1711152000,
  "precision": "FINE",
  "stationary": false
}
```
*   `precision` (string, optional): One of `"FINE"` (exact) or `"COARSE"` (rounded to ~1.1km). If absent, defaults to `"FINE"`.
*   `stationary` (bool, optional): When `true`, signals that the sender is parked and may go quiet (see §5.7.1). Senders SHOULD omit the key entirely when `false` to minimize on-wire entropy. Absent ⇒ `false`.

For a **Keepalive** (`"type": "ka"`):
```json
{ "type": "ka" }
```

For a **Stopped-Sharing** notice (`"type": "stop"`):
```json
{
  "type": "stop",
  "ts": 1711152000
}
```
*   `ts` (int): Sender's wall-clock at the moment sharing was stopped. The recipient uses this to render the terminal state ("stopped sharing at HH:mm"). See §5.7.2 for sender obligations after emitting this message.

Senders MUST emit the `"type"` field on every plaintext message. Receivers MUST dispatch on it, treating any unrecognized value as a Keepalive (§5.7.3).

### 9.2 Poll Request (Bob → Server)

Bob retrieves pending messages by performing a `GET` request to his pairwise receive token: `GET /inbox/{hex(recv_token_T)}`. There is no JSON payload for the poll request itself.

The receiver polls **exactly one token per cycle** — the current `recv_token`. Epoch transitions are handled entirely on the send side (§5.4.1): the sender's transition-message rule routes the first new-epoch message onto the token the receiver is already polling, so the receiver naturally observes the new `dh_pub`, ratchets forward, and switches its polling target without needing a two-token window.

The server returns a JSON array of `MailboxPayload` objects, or an empty array `[]` if no messages are pending (§7.2).

### 9.3 KeyExchangeInit

**KeyExchangeInit** (Bob → Alice, posted to discovery token):
```json
{
  "v": 1,
  "type": "KeyExchangeInit",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "encrypted_name":   "<base64, 12-byte nonce || ChaCha20-Poly1305 ciphertext>",
  "key_confirmation": "<base64, key_confirmation>"
}
```

Alice MUST verify `key_confirmation` and decrypt/verify `encrypted_name` before accepting the session. Abort and discard if either fails.

---

## 10. Server Changes

### 10.1 Server Architecture

| Component | Design |
|---|---|
| Routing Model | **Anonymous Mailboxes.** Routes opaque `EncryptedLocation` payloads by pairwise routing tokens (T). No userid-based addressing. |
| Client Interaction | **Registration-less.** Clients poll `GET /inbox/{token}` and post `POST /inbox/{token}`; the server has no knowledge of user identity. |
| Persistent store | **Opaque Payload Buffer.** Durable buffer of encrypted payloads indexed by routing token T, retained for 7 days. |
| Metadata Exposure | **Obfuscated.** Routing tokens are pairwise and random-looking; social graph is hidden from the server. |

### 10.2 Routing Table

The server maintains a persistent map of **mailboxes** indexed by 16-byte routing tokens. Mailboxes are durable across server restarts.

1. **PUT /inbox/{token}/{msgId}:**
   - Push the payload into the corresponding queue if `msgId` hasn't been seen yet.
   - If `msgId` has been seen, return `204 No Content` without adding to queue (Idempotent).
   - Apply a TTL of 7 days. This is a server retention policy, not a cryptographic guarantee. The 7-day window is only used as a bounded liveness fallback in the client retirement logic.
   - The server MUST durably persist the payload before returning `204 No Content`. Clients treat any non-2xx response as delivery failure and retain the outbox for retry.

2. **GET /inbox/{token}:**
   - Return up to 50 messages from the front of the queue. **Does not delete them.**
   - **Empty-Response Invariant:** The server MUST return an identical response body for non-existent tokens. Server implementations SHOULD minimize timing differences between "hit" and "miss" cases. A server that does not will increase the risk of token-existence oracle attacks, allowing a metadata-analyzing adversary to confirm whether a given token has ever been used and thereby partially reconstruct the social graph.

3. **DELETE /inbox/{token}/{msgId}:**
   - Remove the specific message by ID from the queue. **Idempotent.**

The server exposes the mailbox API: `PUT /inbox/{token}/{msgId}`, `GET /inbox/{token}`, and `DELETE /inbox/{token}/{msgId}`.

### 10.3 Server Cannot Decrypt or Link

With this design, and assuming only the advertised mailbox API and payload encryption:
- The server has no knowledge of any session keys or identity keys.
- The server does not know the sender or recipient identity—only the opaque routing token.
- A full server compromise reveals only the timing and frequency of anonymous posts and polls. Social graph and content remain hidden.
- Location and keepalive messages are padded to the same fixed size (512 bytes), so the server cannot distinguish them based on message length.

---

## 11. Cryptographic Primitives Summary

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Asymmetric key agreement | X25519 (ECDH) | Diffie-Hellman key exchange at bootstrap and each DH ratchet step | libsodium / Tink / CryptoKit |
| Symmetric encryption | ChaCha20-Poly1305 (IETF) | Encrypt location payloads and control messages (AEAD) | libsodium |
| Key derivation (KDF_RK) | HKDF-SHA-256 | Derive new root key and chain key from DH output | libsodium |
| Chain KDF (KDF_CK) | HMAC-SHA-256 + HKDF-SHA-256 | Advance symmetric ratchet: `MK = HMAC(CK, 0x01)`, `CK' = HMAC(CK, 0x02)`, `nonce = HKDF(ikm=MK, info="Where-v1-MsgNonce", length=12)` | libsodium |
| Suggested name KDF | HKDF-SHA-256 | Derive name encryption key `K_name` from shared secret `SK` | libsodium |
| Suggested name encryption | ChaCha20-Poly1305 (IETF) | Encrypt/decrypt suggested name during key exchange | libsodium |
| Session auth | HMAC-SHA-256 | Authenticate `KeyExchangeInit` key confirmation | libsodium |
| Hash / fingerprint | SHA-256 | Session fingerprints (`alice_fp`, `bob_fp`), safety number, discovery token | libsodium |
| Random number generation | OS CSPRNG | Ephemeral key generation | `SecureRandom` (Android) / `SecRandomCopyBytes` (iOS) |

**Library recommendations:**
- **Kotlin Multiplatform:** Use [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium) for all cryptographic primitives (X25519, ChaCha20-Poly1305, SHA-256, HMAC-SHA-256). Libsodium provides a unified API across JVM, Android, and iOS, eliminating platform-specific implementation variance. All crypto operations are common-code `expect/actual` implementations.
- **Android / Kotlin:** Libsodium bindings use `libsodium.so` (statically linked). Store root keys in Android Keystore where supported; a Keystore-backed wrapper key can protect the master key material.
- **iOS / Swift:** Libsodium bindings use the native `libsodium` framework (iOS includes sodium.dylib). Key material persists in the Secure Enclave-backed Keychain. SwiftUI calls the KMP shared module for crypto operations.

---

## 12. Comparison with Signal Protocol (libsignal)

While this protocol shares the core **Double Ratchet** design with Signal, it makes several deliberate architectural departures to optimize for anonymous location broadcasting and decentralized identity.

### 12.1 Identity and Handshake (No X3DH)

- **Signal:** Uses **X3DH** (Extended Triple Diffie-Hellman) which relies on long-term **Identity Keys (IK)** and a central server to host Signed Prekeys and One-time Prekeys. This enables asynchronous session establishment (Alice can message Bob even if he is offline).
- **Where:** Uses an **ephemeral-only bootstrap handshake**. There are no long-term identity keys. "Identity" is scoped to a single friendship session ("Session as Identity"). This eliminates the need for a central PKI or prekey server and ensures that no stable device identifier is ever exposed to the server. Key exchange is synchronous and out-of-band (QR code or secure link).

### 12.2 Cryptographic Primitives (ChaChaPoly vs AES-GCM)

- **Signal:** Modern libsignal implementations primarily use **AES-256-GCM** for message encryption. AES-GCM is hardware-accelerated on most modern desktop and mobile CPUs (AES-NI).
- **Where:** Uses **ChaCha20-Poly1305 (IETF)** for all AEAD operations (both payload and header envelope). ChaCha20 is generally faster and more secure in software-only implementations (preventing timing attacks) and is often preferred for mobile battery efficiency in the absence of specialized AES hardware.

### 12.3 Metadata Protection and Routing

- **Signal:** Historically identified users by stable identifiers (phone numbers, now UUIDs). "Sealed Sender" was added later as an extension to hide the sender from the server.
- **Where:** Metadata protection is integrated into the base protocol.
    - **Header Encryption:** Every message uses an **Encrypted Envelope** that hides the DH public key, message number (`msg_num`), and previous chain length (`prev_chain_len`) from the server.
    - **Dynamic Routing:** Instead of stable user IDs, Where uses **Pairwise Routing Tokens** derived from the session root key. These tokens rotate automatically with the DH ratchet, making it impossible for the server to correlate message *content* across epochs without session state. Cross-epoch correlation at the IP level remains possible: the same client IP that was polling T_old will begin polling T_new in the next cycle. This is a metadata limitation acknowledged in §2.3 and §7.3, not a content-layer weakness.

### 12.4 Group Messaging (Sender Keys vs Per-Friend)

- **Signal:** Uses **Sender Keys** for efficient group messaging. Alice encrypts her message once and the server fans it out. This is O(1) bandwidth for the sender but shares a symmetric chain among all group members.
- **Where:** Uses **Per-Friend Symmetric Sessions**. Alice encrypts a separate ciphertext for each friend (O(N) bandwidth). For small groups (tens of friends), this provides superior blast-radius isolation: a compromise of one friend's device only exposes Alice's location to that friend, with no impact on the security of her sessions with others.

### 12.5 Post-Quantum Resistance

- **Signal:** Recently introduced **PQXDH** and **SPQR**, incorporating Kyber into the initial handshake and ratchet to provide post-quantum confidentiality.
- **Where:** Currently **not quantum-resistant**. The protocol relies entirely on X25519 (ECDH). Quantum resistance is recognized as a future requirement but is not part of the v1 implementation (see §13).

### 12.6 Safety Numbers

- **Signal:** Safety Numbers are derived from long-term Identity Keys and are stable across the lifetime of the user's account.
- **Where:** Safety Numbers are **session-scoped**. They are derived from the ephemeral keys used at bootstrap. A device reset or a session re-pairing results in a new Safety Number, reflecting the transient nature of identity in the protocol.

---

## 13. Open Questions and Future Work

1. **Cross-Device Signing.** The protocol currently scopes identity to a single primary device. A future extension would allow the old device to sign the new pairing's Safety Number, allowing contacts to auto-migrate trust without re-adding the friend.

2. **Post-Quantum Cryptography.** Introducing CRYSTALS-Kyber or similar PQ-resistant key exchange into the ratchet to maintain confidentiality against future quantum adversaries.

3. **Multi-Device Support.** Full session synchronization across multiple devices (e.g., phone and tablet) is a complex challenge planned for future work.

4. **Session Expiry and Staleness Handling.** If Alice stops sharing (app uninstalled, account deleted, extended offline period), Bob's client continues polling indefinitely against a token that will never receive new messages. To provide UX signals, `FriendEntry.isStale` is a heuristic that returns true if no messages have been received for more than 7 days (`ACK_TIMEOUT_SECONDS`). Clients SHOULD surface a "no recent location" warning to the user based on this flag. This is a UX heuristic, not a protocol retirement rule.

