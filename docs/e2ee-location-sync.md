# End-to-End Encrypted Location Sync — Design Document

**Status:** Draft
**Authors:** Engineering
**Date:** 2026-03-26

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

### 1.2 Location Precision Control

As a core privacy feature, the protocol supports client-side precision degradation. Before encryption, the sender may apply a configurable rounding function to their coordinates (e.g., snapping to a coarser grid or rounding to N decimal places). This allows users to share "neighbourhood" or "city" level precision with specific friends. Because degradation happens before encryption, the recipient only ever receives the degraded coordinates, and the server cannot recover the original precision.

### 1.3 Scope

This document covers:
- Key establishment between peers with no central identity server
- A ratchet-based forward secrecy scheme adapted for continuous one-way location broadcasting
- Group fan-out (one sender, N recipients)
- The resulting wire format and server changes

This document does **not** cover:
- Post-quantum cryptography (deferred; see Section 12)
- Multi-device support (deferred)
- Server-side persistence encryption

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

- **Server compromise revealing historical locations.** *Forward secrecy (per-message):* deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others. *Post-compromise security:* the DH ratchet step with fresh OPK material limits how long a leaked chain key remains exploitable.
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing sequence counter which is also authenticated (as AEAD additional data). The recipient rejects any frame with a counter it has already seen.
- **Ciphertext forgery.** ChaCha20-Poly1305 authentication tags cover both the ciphertext and associated data (sender session fingerprint, sequence number). A server or attacker cannot modify a frame without detection.
- **Ratchet hijacking.** DH ratchet steps use fresh ephemeral key material. An attacker without session state cannot forge or inject valid ratchet updates.
- **Key mismatch at bootstrap.** The `key_confirmation` field in `KeyExchangeInit` (§4.2) proves that both parties derived the same `SK` before any location data is shared.

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server sees packet timing, packet sizes, and connection metadata regardless of payload encryption. For a location app, timing is nearly as sensitive as content: update intervals increasing from 30 s (moving) to 5 min (stationary) reveal movement state; silence reveals offline/stopped status; synchronized update timing across multiple users suggests co-location. A metadata-analyzing server can infer movement patterns from timing alone, independent of content. Mitigations (cover traffic, padding) are discussed in §7.4; they add overhead and battery cost and are not a panacea. This is elevated here as a top-level limitation for threat models that include a compromised or curious server.
- **Initial TOFU impersonation.** The first key exchange (§4) uses an ephemeral key delivered out-of-band (QR or link). If an attacker intercepts or replaces Alice's QR before Bob scans it, Bob will establish a session with the attacker rather than Alice. The `key_confirmation` in `KeyExchangeInit` (§4.2) proves that the responder (Bob) derived the correct `SK` from Alice's authentic `EK_A.pub` — but it does not authenticate Alice's QR as originating from Alice rather than an attacker who substituted a different key. Safety number verification (§3.4) is the primary mitigation for this residual TOFU risk.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server never sees user IDs or UUIDs — routing tokens are opaque and random-looking. However, the server observes which IP addresses `POST` to and `GET` from each token. If Alice's IP consistently posts to token T and Bob's IP consistently polls T, the server can infer they share a friendship, even without decrypting any payload. Timing correlation reinforces this: correlated activity (Alice posts, Bob polls seconds later) on the same token is a strong signal. See §7 for partial mitigations (constant-rate polling, dummy tokens).
- **Map tile server leakage.** When a recipient views a friend's location on a map, the map provider (e.g., Google Maps, Apple Maps, Mapbox) may infer the friend's location from which tiles the recipient's device requests. This can be mitigated at the application layer via tile pre-fetching or caching, but is outside the scope of this protocol.
- **Denial of service.** This protocol does not protect against a server that drops or delays messages.
- **Clock manipulation.** An attacker who can skew the victim's clock (e.g., via NTP poisoning or rogue cellular time) by more than 15 minutes can cause the recipient to reject valid messages, permanently stalling the session.
- **Quantum adversaries.** All DH operations here use X25519 (256-bit elliptic curve). A cryptographically relevant quantum computer running Shor's algorithm could break these. See §12.

---

## 3. Identity and Key Management — Session as Identity

### 3.1 The Model: No Long-Term Keys, No PKI

This protocol uses **no long-term identity keys**. There is no `IK`, no `SigIK`, and no central registry. Identity is scoped entirely to a single friendship session, anchored by the ephemeral keys exchanged at bootstrap.

This design decision is motivated by the device management policy (§3.3): when a device is lost or the app is reinstalled, all contacts must be manually re-added regardless. Because there is no long-term identity that survives a device loss, the X3DH model's primary benefit — binding a session to a stable device identity — does not apply here. Dropping long-term keys eliminates the exposure of a stable device identifier to the server (§4.4) and removes the risk that a long-term key compromise retroactively breaks all historical session keys.

Each friendship session is identified by the pair `(EK_A.pub, EK_B.pub)` — the initial bootstrap ephemeral public keys from Alice and Bob respectively. These keys are used to derive the Safety Number (§3.4) and session fingerprints (§8.3). The session's root key `SK` is derived from a single X25519 operation over these keys; both private keys are deleted immediately after derivation.

### 3.2 Naming and Local Aliases

To avoid requiring users to manage public keys in the UI, the protocol implements local aliases:

1. **Invite Payload:** An invite contains `{ek_pub, suggested_name: "Alice", fingerprint}`. Alice's suggested name is pre-filled in Bob's naming dialog.
2. **KeyExchangeInit:** Bob includes his own `suggested_name` when responding to Alice's QR. Alice's app pre-fills her naming dialog with Bob's suggested name.
3. **Local Import:** The receiving party sees the other's suggested name but may rename it locally before confirming.
4. **Local Storage:** The name is a purely local alias. It is never sent to the server.

Both sides can therefore assign human-readable names to each other at the time of first exchange, with no extra protocol round-trips.

### 3.3 Device Management

1. **One Primary Device per Person:** Identity is scoped to a single primary device (typically a phone).
2. **Lost Device / Device Replacement:** When a user gets a new phone or loses their device, they generate a new ephemeral key pair per new invite. This is a manual "session reset":
   - The user reinstalls the app and re-adds all contacts via new invite links.
   - Each new pairing produces a new Safety Number.
3. **No Cloud Backup:** Encrypted cloud backup of session state is explicitly excluded, as it reintroduces a third-party trust dependency.

### 3.4 Trust Establishment and Safety Numbers

This protocol uses **Trust-on-First-Use (TOFU)** with local session pinning.

**Safety Numbers:** Two users can optionally verify their connection by comparing a safety number fingerprint.
- **Calculation:** `SHA-256(lower_EK.pub || higher_EK.pub)`, where `lower_EK.pub` and `higher_EK.pub` are the two bootstrap ephemeral public keys sorted lexicographically by their raw 32-byte values. The result is displayed as a human-readable 40-character hex string or QR code.
- This is **session-scoped**: the Safety Number is unique to the specific pairing event, not to a device. Every re-pairing after a device reset produces a new Safety Number.
- **Key-Change Detection:** If Alice receives a new `KeyExchangeInit` for a contact she already has an active session with (identified by local name), the app MUST display a "Session Reset" warning before replacing the old session. The user should compare the new Safety Number out-of-band before confirming. This can indicate: (1) the friend reinstalled and re-paired — expected; (2) a MITM substituted a key — suspicious.

**Risk:** If the invite link (Option B, §4.3) is intercepted over an unauthenticated channel (e.g., SMS), an attacker can substitute their own key. Fingerprint verification is the primary countermeasure.

---

## 4. Key Exchange Flow

### 4.1 Prerequisites

Each exchange requires only one ephemeral X25519 key pair per side, generated fresh per invite:
- **Alice:** Generates a fresh ephemeral key pair `EK_A` when displaying a QR/link. No persistent key material required.
- **Bob:** Generates a fresh ephemeral key pair `EK_B` when scanning Alice's QR.

Both private keys are deleted immediately after `SK` is computed and verified.

### 4.2 Option A: In-Person QR Code Exchange (Recommended)

**Setup:**

Alice opens "Add Friend" and generates a fresh ephemeral key pair `EK_A` and a fresh random 32-byte `discovery_secret`. She displays a QR code encoding:
```
{
  "ek_pub":            base64(Alice.EK_A.pub),  // X25519 ephemeral public key (32 bytes)
  "suggested_name":    "Alice",
  "fingerprint":       hex(SHA-256(EK_A.pub)[0:10]),
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
- Once Alice retrieves and processes the `KeyExchangeInit`, she switches to polling `recv_token` for all subsequent messages.
- The discovery token is single-use and ephemeral: implementations MUST discard it after `aliceProcessInit` completes.

**Key Agreement:**

Bob generates a fresh ephemeral key pair `EK_B` and computes:

```
SK = X25519(Bob.EK_B.priv, Alice.EK_A.pub)
   = X25519(Alice.EK_A.priv, Bob.EK_B.pub)   // Alice computes identically

// Session fingerprints (session-scoped, not device-scoped)
alice_fp = SHA-256(EK_A.pub)   // 32 bytes
bob_fp   = SHA-256(EK_B.pub)   // 32 bytes

// Safety Number (for out-of-band verification)
safety_number = SHA-256(lower_EK.pub || higher_EK.pub)   // sorted lexicographically

// Derive initial bootstrap routing token from SK
// AliceToBob token (Alice posts, Bob polls)
T_AB_0 = HKDF-SHA-256(IKM  = SK,
                       salt = 0x00000000,
                       info = "Where-v1-RoutingToken" || alice_fp || bob_fp)[0:16]
```

**Key Confirmation:**

Bob MUST include a key confirmation MAC in his `KeyExchangeInit` to prove he derived the same `SK` as Alice. Without this, a bit-flip or substitution of `EK_B.pub` in transit would cause Alice to compute an incorrect `SK` and stream location data into a void, with neither party detecting the mismatch.

```
key_confirmation = HMAC-SHA-256(key  = SK,
                                 data = "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
```

Bob transmits:
```json
{
  "type":             "KeyExchangeInit",
  "token":            "<base64, T_AB_0>",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "suggested_name":   "Bob",
  "key_confirmation": "<base64, HMAC-SHA-256(SK, 'Where-v1-Confirm' || EK_A.pub || EK_B.pub)>"
}
```

Bob POSTs this to `POST /inbox/{hex(discovery_token_A)}`.

**Alice's processing:**

Alice receives the `KeyExchangeInit` and:

1. Derives `SK = X25519(Alice.EK_A.priv, Bob.EK_B.pub)`.
2. Recomputes the expected `key_confirmation = HMAC-SHA-256(SK, "Where-v1-Confirm" || EK_A.pub || EK_B.pub)`.
3. **Aborts and discards** if the MAC does not match — this indicates `EK_B.pub` was corrupted or substituted in transit.
4. Derives `alice_fp`, `bob_fp`, `T_AB_0` using the same formulas above.
5. **Deletes `EK_A.priv` immediately.**
6. Prompts user to name Bob (pre-filled with `suggested_name` from `KeyExchangeInit`).
7. Stores the session.

Bob **deletes `EK_B.priv` immediately** after posting the `KeyExchangeInit`.

### 4.3 Option B: Out-of-Band Copy-Paste (Existing Flow Extension)

Users share a link through any out-of-band channel (iMessage, Signal, in-person). The link is:
```
where://add?ek=<base64_ek_pub>&fp=<fingerprint>&name=<name>
```

The key agreement proceeds identically to Option A, using the same discovery token mechanism. The app MUST display a prominent prompt encouraging Safety Number verification after the friend is added via this path.

### 4.4 What the Server Learns from Key Exchange

- **Discovery phase:** A 16-byte token was used briefly for rendezvous. This token is derived from a random 32-byte `discovery_secret` embedded in the QR (not from `EK_A.pub`), so neither the server nor any observer who later sees `EK_A.pub` in a `KeyExchangeInit` message can compute or correlate the discovery-phase mailbox. The server observes that some IP polled it and another IP posted to it, but learns nothing about who those IPs represent.
- **`KeyExchangeInit` phase:** The server sees Bob's `EK_B.pub` (ephemeral, 32 bytes) and the HMAC confirmation tag. Both are ephemeral and single-use. No stable long-term key material is exposed to the server at any point.
- **Nothing** about the resulting shared secret `SK`, the session fingerprints, or the identities of the participants.

The server cannot derive `SK` or link any routing token to a real identity without one of the parties' private keys.

---

## 5. Ratchet Design for One-Way Streaming Location Data

### 5.1 Per-Message Token Rotation

Token rotation is per-message and structurally enforced.

#### 5.1.1 Polling Invariant

Bob MUST poll the server at a constant rate for messages from Alice. At any point in time, Bob MUST poll exactly one `recv_token` per Alice. The single-token invariant is structurally enforced by the per-message token rotation mechanism.

#### 5.1.2 Token Lifetime

A `recv_token` is single-use. Each token is valid for exactly one message delivery. After Bob retrieves the message associated with `recv_token_i`, that token is consumed and MUST NOT be polled again.

#### 5.1.3 Token Delivery

Alice MUST generate a fresh `recv_token_{i+1}` for each message she sends to Bob. Alice MUST include `recv_token_{i+1}` in the encrypted body of message `i`, delivered to the server in the same HTTP request as the message ciphertext. No additional round-trip is required.

#### 5.1.4 Alice's Behavior

For each message Alice sends to Bob:
1. Generate fresh `recv_token_{i+1}` (16 bytes, cryptographically random).
2. Encrypt `recv_token_{i+1}` inside the message body to Bob.
3. Upload the message ciphertext and `recv_token_i` (the token Bob will poll to retrieve this message) to the server in a single HTTP request.

#### 5.1.5 Bob's Behavior

Upon successfully retrieving message `i` from the server:
1. Decrypt the message body and extract `recv_token_{i+1}`.
2. Immediately begin polling `recv_token_{i+1}`.
3. Stop polling `recv_token_i`; discard it from local state.

Bob MUST NOT poll more than one `recv_token` per Alice at any time.

### 5.2 Post-Compromise Security via Integrated DH Ratchet

To achieve Post-Compromise Security (PCS) without separate control messages, DH ratchet steps are integrated into location updates using **One-Time Pre-Keys (OPKs)**.

#### 5.2.1 Pre-Key Delivery

Bob periodically generates a batch of fresh ephemeral X25519 keypairs (e.g., 20 OPKs). He includes these public keys in a `PreKeyBundle` message posted to his current `send_token` (which is Alice's current `recv_token`).

#### 5.2.2 Integrated Ratchet Step (Alice)

When Alice sends a location update:
1. She pops the oldest OPK from her local cache for Bob.
2. She generates a fresh ephemeral keypair `EK_new`.
3. She computes `dh_out = X25519(Alice.EK_new.priv, Bob.OPK.pub)`.
4. `(new_root_key, new_CK) = KDF_RK(root_key, dh_out)`.
5. She includes `opk_id` and `EK_new.pub` in the encrypted message body.
6. She replaces her current root key and uses `new_CK` for the next message.

#### 5.2.3 Consumption (Bob)

When Bob retrieves and decrypts the message:
1. He extracts `opk_id` and `EK_new.pub`.
2. He retrieves the private key for `opk_id`.
3. He computes `dh_out = X25519(Bob.OPK.priv, Alice.EK_new.pub)`.
4. He derives `new_root_key` and `new_CK`.
5. **Bob MUST delete the OPK private key immediately after use.**

### 5.3 Forward Secrecy Granularity

Symmetric-key ratcheting (KDF chain) provides per-message forward secrecy. Deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others.

### 5.4 Message Key Deletion Policy

Forward secrecy is only as strong as the message key deletion discipline:
- Message keys `MK_n` MUST be deleted from memory immediately after encrypting/decrypting the corresponding frame.
- Chain keys `CK` MUST be deleted from memory after deriving the next step.
- Root keys MUST be overwritten immediately after deriving new chain keys.
- Ephemeral DH private keys MUST be deleted after computing the shared secret.
- On Android, keys live in a `SecureRandom`-backed in-memory structure; `Arrays.fill(key, 0)` before GC. On iOS, `Data` is zeroed explicitly before dealloc.
- No message keys or chain keys are persisted to disk. If the app is killed and restarted, the session restarts from the last stored state (root key + current chain key). The root key is stored in the platform keychain (Android Keystore / iOS Secure Enclave-backed Keychain).

**Keychain backup and state-rollback risk:** Platform keychains may be backed up (iCloud Keychain on iOS, Google Play Backup on Android). If a backup is restored to a different device or is compromised, the attacker gains access to the stored root key and current chain key, and can re-derive message keys from the backed-up state forward — until the next DH ratchet step heals the session.

Mandatory mitigations:
- **iOS:** Mark all session-state keychain items with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. This attribute excludes the item from iCloud Backup.
- **Android:** Store session state in `EncryptedSharedPreferences` backed by a Keystore key created with `setIsStrongBoxBacked(true)` and `allowBackup=false` in the manifest.
- **Both:** On detecting a fresh install or that session state is missing/invalid (e.g., root key absent), invalidate the session and initiate re-keying with all affected friends rather than accepting a potentially stale backup.

---

## 6. Group Broadcasting — Per-Friend Keys

### 6.1 The Problem

Alice broadcasts her location to N friends. She must encrypt separately for each friend because each friend has a unique `recv_token` and potentially a different ratchet state. Group key schemes (like MLS) require all-to-all consistency and are overkill for Where's small group sizes. Using a single group key would mean that any one friend's device compromise exposes Alice's location to everyone.

### 6.2 Per-Friend Symmetric Sessions

Alice maintains one independent ratchet session per friend. When sending a location update, she encrypts a unique payload for each friend, each containing that friend's specific `next_token` and potential DH parameters.

**Bandwidth:** For N friends, Alice sends N encrypted frames per location update. For Where's small group sizes (typically < 50 friends), O(N) per-friend encryption is the right tradeoff: simpler, stronger blast-radius isolation, no group-state synchronization needed.

### 6.3 Handling Friend Add/Remove

**Adding a friend:** When Alice adds Bob as a friend, she runs the key exchange (§4) and initializes a new ratchet session. There is no effect on other friends' sessions.

**Removing a friend:** Alice removes the ratchet session state for Bob. Since she was encrypting separately for each friend, Bob's removal immediately stops the flow of ciphertext addressed to him. He cannot recover future location updates.

---

## 7. Server's Role and Obliviousness

### 7.1 The Mailbox Model

The server's role is strictly limited to acting as a stateless message router for anonymous mailboxes.
1. **Routing Token (T):** A random-looking 16-byte token derived pairwise by clients.
2. **Mailbox API:**
   - `POST /inbox/{token}`: Clients push an encrypted payload into the mailbox.
   - `GET /inbox/{token}`: Clients poll for and retrieve all available payloads.
3. **Server Obliviousness:** The server does not know the sender or recipient identity—only the opaque routing token.

### 7.2 Invariant: Indistinguishable Responses

To prevent the server from learning whether a routing token corresponds to a real relationship, the following invariant is mandatory:

**The server MUST return an identical response (HTTP 200 OK with `[]`) for all token queries where no messages are pending, regardless of whether the token has ever been "registered" or used.**

There is no "create mailbox" or "register token" step. Mailboxes exist implicitly upon the first `POST`. A `GET` for a non-existent token is indistinguishable from a `GET` for an empty real mailbox.

### 7.3 Metadata Exposure and Traffic Analysis

The server can still observe the timing and frequency of `POST` and `GET` requests for specific tokens. IP correlation can be used to infer relationships over time.

### 7.4 Mitigations

- **Payload padding (mandatory):** All payloads MUST be padded to a fixed length (512 bytes recommended) before encryption.

### 7.4.1 Polling Strategy

To prevent timing-based social-graph inference, Bob MUST poll at a **constant rate** regardless of whether messages are expected. Polling more frequently when a location update is expected, or less frequently when offline, creates a timing side-channel the server can exploit to infer when friends are actively sharing.

**Polling cadence is a UX/battery parameter, not a cryptographic one.** The polling interval is independent and should be set based on freshness requirements and battery budget. A 60–120 second poll interval provides acceptable location freshness for a mapping application without the battery drain of 10-second polling, and without revealing fine-grained app-foreground state to the server. Recommended default: **60 seconds**.

Bob polls for all of his friendship tokens in a fixed, shuffled order. The shuffle MUST be re-randomised on each poll cycle to prevent ordering-based inference. This, combined with the indistinguishable-response invariant (§7.2), means the server cannot distinguish "polling for a real active friendship" from "polling for a stale or never-used token".

### 7.5 Future: Dummy Token Polling

Because of the indistinguishable response invariant (§7.2), clients can implement **dummy token polling** as a future enhancement. A client polls for random "dummy" tokens alongside real ones. The server cannot distinguish real polls from noise. This significantly raises the bar for traffic analysis and requires no server-side changes to implement.

---

## 8. Concrete Protocol Recommendation

### 8.1 Key Types and Operations

| Purpose | Algorithm | Key size | Notes |
|---|---|---|---|
| Bootstrap DH (Alice) | X25519 (`EK_A`) | 256-bit | Ephemeral, generated per invite; deleted after SK derivation |
| Bootstrap DH (Bob) | X25519 (`EK_B`) | 256-bit | Ephemeral, generated per QR scan; deleted after SK derivation |
| One-Time Pre-Key (Bob) | X25519 (`OPK`) | 256-bit | Ephemeral, generated in batches; deleted after use |
| Root KDF | HKDF-SHA-256 | 256-bit output | Inputs: DH output + current root key |
| Chain KDF | HKDF-SHA-256 | — | Advancing symmetric ratchet |
| Message encryption | ChaCha20-Poly1305 | 256-bit | Per-message key; deleted after use |
| Message authentication | ChaCha20-Poly1305 tag | 128-bit | Included in AEAD output; covers AAD |
| Key exchange KDF | HKDF-SHA-256 | — | `info = "Where-v1-KeyExchange"` (initial SK) |
| Discovery token | HKDF-SHA-256 | 16-byte output | `ikm = discovery_secret` (32-byte random, from QR payload), `salt = 0x00*32`, `info = "Where-v1-Discovery"` (§4.2) |
| Bundle auth key | HKDF-SHA-256 | 32-byte output | `K_bundle = HKDF(SK, salt=0, info="Where-v1-BundleAuth")`; for PreKeyBundle HMAC |

### 8.2 Session State Per Friend-Pair

```
SessionState {
  root_key:        [32]byte
  send_chain_key:  [32]byte
  recv_chain_key:  [32]byte
  send_token:      [16]byte   // token this client posts to
  recv_token:      [16]byte   // token this client polls from
  send_seq:        uint64
  recv_seq:        uint64
  alice_fp:        [32]byte
  bob_fp:          [32]byte
  k_bundle:        [32]byte
}
```

### 8.3 Ratchet Step Functions

**KDF_RK (Diffie-Hellman ratchet step):**
```
(new_root_key, new_chain_key) = HKDF-SHA-256(
    salt = current_root_key,
    ikm  = X25519(my_ek_priv, their_opk_pub),
    info = "Where-v1-RatchetStep"
)
```

**KDF_CK (symmetric ratchet step):**
```
(new_chain_key || message_key || message_nonce) = HKDF-SHA-256(
    ikm  = current_chain_key,
    salt = <absent>,
    info = "Where-v1-MsgStep"
)[0:76]
```

---

## 9. Wire Format

### 9.1 Location Update (Alice → Server → Bob)

```json
{
  "v": 1,
  "type": "Post",
  "token": "<token_i>",
  "payload": {
    "type": "EncryptedLocation",
    "seq":   "1337",
    "ct":     "<base64, ciphertext>"
  }
}
```

**Plaintext (before encryption):**
```json
{
  "lat": 37.7749,
  "lng": -122.4194,
  "acc": 15.0,
  "ts":  1711152000,
  "next_token": "<base64_16_bytes>",
  "ratchet": {
    "opk_id": 101,
    "ek_pub": "<base64_32_bytes>"
  }
}
```

---

## 10. Server Changes

- **Anonymous Mailboxes.** Routes opaque payloads by random routing tokens.
- **Short TTL on consumption.** After a `GET`, retain message for 60 seconds to support retries, then delete.
- **7-day absolute TTL.** Messages not polled within 7 days are deleted.

---

## 11. Cryptographic Primitives Summary

- **X25519** for DH.
- **ChaCha20-Poly1305** for AEAD.
- **HKDF-SHA-256** for KDF.
- **HMAC-SHA-256** for authentication.

---

## 12. Open Questions and Future Work

1. **Cover traffic.**
2. **Multi-device support.**
