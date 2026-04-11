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

- **Server compromise revealing historical locations.** *Forward secrecy (per-message):* deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others. *Post-compromise security (per DH ratchet step):* the DH ratchet step with fresh OPK material limits how long a leaked chain key remains exploitable; the ratchet advances when Bob acks (§8.3).
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing sequence counter which is also authenticated (as AEAD additional data). The recipient rejects any frame with a counter it has already seen.
- **Ciphertext forgery.** ChaCha20-Poly1305 authentication tags cover both the ciphertext and associated data (sender session fingerprint, sequence number). A server or attacker cannot modify a frame without detection.
- **Ratchet hijacking.** `EpochRotation` and `RatchetAck` control messages are AEAD-encrypted under keys derived from the current session root key. An attacker without session state cannot forge or inject valid control messages.
- **Key mismatch at bootstrap.** The `key_confirmation` field in `KeyExchangeInit` (§4.2) proves that both parties derived the same `SK` before any location data is shared.

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server sees packet timing, packet sizes, and connection metadata regardless of payload encryption. For a location app, timing is nearly as sensitive as content: update intervals increasing from 30 s (moving) to 5 min (stationary) reveal movement state; silence reveals offline/stopped status; synchronized update timing across multiple users suggests co-location. A metadata-analyzing server can infer movement patterns from timing alone, independent of content. Mitigations (cover traffic, padding) are discussed in §7.4; they add overhead and battery cost and are not a panacea. This is elevated here as a top-level limitation for threat models that include a compromised or curious server.
- **Initial TOFU impersonation.** The first key exchange (§4) uses an ephemeral key delivered out-of-band (QR or link). If an attacker intercepts or replaces Alice's QR before Bob scans it, Bob will establish a session with the attacker rather than Alice. The `key_confirmation` in `KeyExchangeInit` (§4.2) proves that the responder (Bob) derived the correct `SK` from Alice's authentic `EK_A.pub` — but it does not authenticate Alice's QR as originating from Alice rather than an attacker who substituted a different key. Safety number verification (§3.4) is the primary mitigation for this residual TOFU risk.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server never sees user IDs or UUIDs — routing tokens are opaque and random-looking. However, the server observes which IP addresses `POST` to and `GET` from each token. If Alice's IP consistently posts to token T and Bob's IP consistently polls T, the server can infer they share a friendship, even without decrypting any payload. Timing correlation reinforces this: correlated activity (Alice posts, Bob polls seconds later) on the same token is a strong signal. See §7 for partial mitigations (constant-rate polling, dummy tokens).
- **Map tile server leakage.** When a recipient views a friend's location on a map, the map provider (e.g., Google Maps, Apple Maps, Mapbox) may infer the friend's location from which tiles the recipient's device requests. This can be mitigated at the application layer via tile pre-fetching or caching, but is outside the scope of this protocol.
- **Denial of service.** This protocol does not protect against a server that drops or delays messages.
- **Clock manipulation.** The protocol does not use wall-clock timestamps for message validity decisions, so clock-skew attacks have no cryptographic impact. Timestamps in location payloads are purely informational and are not authenticated as part of the AEAD AAD.
- **Quantum adversaries.** All DH operations here use X25519 (256-bit elliptic curve). A cryptographically relevant quantum computer running Shor's algorithm could break these. See §12.

---

## 3. Identity and Key Management — Session as Identity

### 3.1 The Model: No Long-Term Keys, No PKI

This protocol uses **no long-term identity keys**. There is no `IK`, no `SigIK`, and no central registry. Identity is scoped entirely to a single friendship session, anchored by the ephemeral keys exchanged at bootstrap.

This design decision is motivated by the device management policy (§3.3): when a device is lost or the app is reinstalled, all contacts must be manually re-added regardless. Dropping long-term keys eliminates the exposure of a stable device identifier to the server (§4.4) and removes the risk that a long-term key compromise retroactively breaks all historical epoch keys.

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

// Derive initial bootstrap routing tokens from SK
// AliceToBob token (Alice posts, Bob polls)
T_AB_0 = HKDF-SHA-256(IKM  = SK,
                       salt = <absent>,
                       info = "Where-v1-InitToken-AB")[0:16]
// Note: HKDF with salt=<absent> uses HMAC-SHA-256 with a zero-filled key per RFC 5869, which is semantically equivalent to salt = ByteArray(32).
// BobToAlice token (Bob posts, Alice polls)
T_BA_0 = HKDF-SHA-256(IKM  = SK,
                       salt = <absent>,
                       info = "Where-v1-InitToken-BA")[0:16]
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
4. Derives `alice_fp`, `bob_fp`, `T_AB_0`, `T_BA_0` using the same formulas above.
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

## 5. Ratchet Design for Bidirectional Sessions with Streaming Data

### 5.0 Architectural Trade-offs vs. Signal Double Ratchet

This protocol uses a hybrid ratchet designed for **one-way streaming** at the application layer (Alice sends continuously; Bob is a passive receiver), but the underlying cryptographic session is **bidirectional**. This supports mutual post-compromise security (PCS) and allows for two-way communication (e.g., control messages or future messaging features).

The ephemeral-only bootstrap removes any static DH contributor, making PCS guarantees symmetric:

| Property | Signal Double Ratchet | This Protocol |
|---|---|---|
| Forward secrecy | Per message | Per message |
| Post-compromise security | Per round-trip (both parties contribute fresh DH material) | Asynchronous via One-Time Pre-Keys (OPKs); both contributions always ephemeral |
| Bob's DH contribution | On every reply | Via periodic `PreKeyBundle` (ephemeral OPK) |
| Long-term key compromise | Limited to current session's secret material | N/A — no long-term keys exist |
| Routing token rotation | N/A | Atomic: Bob switches immediately on receiving `EpochRotation`; Alice switches on receiving `RatchetAck` |

Because both Alice's rotation key (`new_ek_pub`) and Bob's pre-key (`OPK`) are ephemeral and deleted after use, there is no static DH input that retroactively breaks historical rotation keys on compromise.

### 5.1 The Signal Double Ratchet — Brief Recap

The Double Ratchet algorithm ([Signal spec](https://signal.org/docs/specifications/doubleratchet/)) combines two mechanisms:

1. **Symmetric-key ratchet (KDF chain):** Given a chain key `CK`, each message derives `(CK', MK)` where `CK'` replaces `CK` for the next message and `MK` is the per-message encryption key. This is a one-way operation (forward secrecy: deleting `CK` makes `MK` irrecoverable).

2. **Diffie-Hellman ratchet:** Each party sends a fresh DH public key with each message. When a new DH public key is received, both parties compute a new DH output and use it to re-derive the root key and chain keys. This provides post-compromise security: if chain keys leak, the next DH ratchet step heals the session.

**The core tension for Where:** The DH ratchet step requires bidirectional communication — Alice sends her DH ratchet key, Bob responds with his, and the exchange drives derivation of new chain keys for **both directions**. In a messaging app, every reply naturally carries a new DH ratchet key. In a location app, Alice broadcasts continuously and Bob never "replies" with location — he is a passive consumer of Alice's location.

### 5.2 The Problem with Naive Double-Ratchet for Streaming

If we naively apply the Double Ratchet to Alice's location stream:

- Alice advances her **sending chain** (symmetric ratchet) with every location update: `(CK', MK_n) = KDF_CK(CK)`. This gives forward secrecy at per-message granularity. If her chain key leaks at message 100, messages 1–99 are protected.
- Alice also needs to advance the **DH ratchet** periodically to achieve post-compromise security. But this requires Bob to send back a new DH public key so Alice can compute the new shared DH secret. Bob is a passive receiver of Alice's location; he has no natural trigger to send anything.

**Consequences of no DH ratchet advancement:**
- If Alice's current sending chain key is compromised, all future messages in that chain are recoverable until the root key is refreshed.
- The DH ratchet step (which provides PCS) never fires.

### 5.3 Ratchet Advancement Strategy: Asynchronous OPKs and Mutual DH Contributions

To achieve Post-Compromise Security (PCS) without requiring Bob to be online when Alice rotates keys, the protocol uses **One-Time Pre-Keys (OPKs)** posted by Bob to the shared mailbox, and a two-step DH exchange triggered by Alice's rotation.

While the location stream is one-way, the **cryptographic session maintains independent send and receive chains**. Each full DH ratchet rotation (one Alice contribution + one Bob contribution) refreshes the root key twice and updates the chain keys for both directions.

**1. Pre-Key Delivery (Bob):**
Bob periodically generates a batch of fresh ephemeral X25519 keypairs (e.g., 20 OPKs) and posts the public keys to the shared mailbox as a `PreKeyBundle` (§9.3).

**2. DH Rotation Attempt (Alice, on every send):**
Alice includes an `EpochRotation` message alongside every location update she sends, as long as a DH rotation is pending (i.e., she has a cached OPK and has not yet received a `RatchetAck`). She continues posting to the **old `send_token`** and continues resending the same `EpochRotation` until Bob acks it. She does NOT switch tokens unilaterally.

Specifically, when Alice initiates a DH rotation:
1. She pops an OPK from her cache and computes `dh_out = X25519(new_ek_A.priv, Bob.OPK.pub)`.
2. She derives `(new_root_key, new_send_CK) = KDF_RK(root_key, dh_out)`.
3. She derives new routing tokens from the new root key.
4. She AEAD-encrypts the rotation payload under `K_rot` (see §8.3) and posts it alongside her location update on the **old `send_token`**.
5. She stores the pending new state but continues using the old state until acked.

**3. Bob Receives and Acks (on EpochRotation only):**
When Bob polls and gets a batch containing an `EpochRotation`:
1. Bob decrypts it and computes new keys + new tokens.
2. Bob **immediately** switches his `recvToken` to the new value — no dual-polling needed, since Alice is still posting to the old token until she gets the ack.
3. Bob sends a `RatchetAck` as a POST on his **old** `sendToken` (a separate small message, independent of whether Bob is currently sharing his own location).
4. **Bob MUST delete the OPK private key immediately after use.**

Bob sends a `RatchetAck` only when processing an `EpochRotation`, not on every receive. Batches containing only location updates require no response from Bob.

**4. Alice Commits (on receiving RatchetAck):**
When Alice polls Bob's channel and receives a `RatchetAck` covering the rotation:
1. She atomically commits: `send_token = new_send_token`, `recv_token = new_recv_token`, `root_key = new_root_key`, etc.
2. She stops resending the `EpochRotation`.

**Why no dual-polling window:**
- Bob switches `recvToken` to `T_new` immediately (step 3 above).
- Alice switches `sendToken` to `T_new` only after getting the ack.
- Between Bob's switch and Alice's ack: Bob polls `T_new` (empty — Alice still posts to `T_old`), and Alice posts to `T_old` (Bob is no longer polling it but the messages queue up).
- This is a brief gap, not a dual-polling window: Bob polls exactly **one** token at a time. Once Alice gets the ack and switches, Bob's `T_new` starts receiving messages.

#### 5.3.1 Offline and Failure Modes

**Bob Offline:** Alice sends `EpochRotation` alongside every location update. When Bob comes back online, he decrypts the rotation from the queued messages, immediately switches `recvToken`, and sends the ack. Alice sees the ack on her next poll and switches `sendToken`.

**Post-rotation message delivery delay:** Due to the "no dual-polling window" design, Bob will receive Alice's first post-commit message on the *next scheduled polling cycle*, not immediately after processing the `EpochRotation` (even if Alice has already committed). This introduces a delay of up to one full polling interval for the first message after rotation. For typical polling cadences (e.g., 5 minutes), this is an expected and acceptable trade-off for protocol simplicity and avoiding race conditions.

**OPK Depletion:** If Alice has no OPKs for Bob, she SHOULD continue broadcasting on the symmetric ratchet (per-message FS maintained) and SHOULD NOT rotate the DH ratchet until a new bundle is received.

**Lost RatchetAck:** After processing an `EpochRotation`, Bob immediately switches his `recvToken` to `T_AB_new` and will no longer poll `T_AB_old`. Alice's retried `EpochRotation` messages (sent on `T_AB_old`) are therefore never seen by Bob again — idempotent re-processing is not possible. Instead, Bob stores the `ackCt` and pre-rotation `sendToken` as a `PendingAck` and re-posts the ack on every poll cycle until Alice commits. Alice is still polling `T_BA_old` (Bob's pre-rotation sendToken, which is her `recvToken` before commit), so she will eventually receive the re-posted ack. Once Alice commits and begins sending on `T_AB_new`, Bob decrypts her first message on the new token and clears the `PendingAck`.

### 5.4 Forward Secrecy Granularity vs. Overhead Analysis

| Property | Value |
|---|---|
| Forward secrecy granularity | Per message (every `KDF_CK` step) |
| Post-compromise security | Per DH ratchet step (ack-triggered) |
| Extra messages per Alice send | 1 `EpochRotation` alongside location (until acked) |
| Extra messages per Bob receive | 1 `RatchetAck` POST only when an `EpochRotation` was in the batch |
| Dual-polling window | None (Bob polls one token at a time) |

### 5.5 Message Key Deletion Policy

Forward secrecy is only as strong as the message key deletion discipline:

- Message keys `MK_n` MUST be deleted from memory immediately after encrypting/decrypting the corresponding frame.
- Chain keys `CK` MUST be deleted from memory after deriving the next step.
- Root keys MUST be overwritten immediately after deriving new chain keys.
- Ephemeral DH private keys (`EK_A.priv`, `EK_B.priv`, OPK private keys) MUST be deleted after computing the shared secret.
- On Android, keys live in a `SecureRandom`-backed in-memory structure; `Arrays.fill(key, 0)` before GC. On iOS, `Data` is zeroed explicitly before dealloc.
- No message keys or chain keys are persisted to disk. If the app is killed and restarted, the session restarts from the last persisted state (root key + chain keys + routing tokens). The root key is stored in the platform keychain (Android Keystore / iOS Secure Enclave-backed Keychain).

**Keychain backup and state-rollback risk:** Platform keychains may be backed up (iCloud Keychain on iOS, Google Play Backup on Android). If a backup is restored to a different device or is compromised, the attacker gains access to the stored root key and current chain key, and can re-derive message keys from the backed-up epoch forward — until the next DH ratchet step heals the session.

Mandatory mitigations:
- **iOS:** Mark all session-state keychain items with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. This attribute excludes the item from iCloud Backup.
- **Android:** Store session state in `EncryptedSharedPreferences` backed by a Keystore key created with `setIsStrongBoxBacked(true)` and `allowBackup=false` in the manifest.
- **Both:** On detecting a fresh install or that session state is missing/invalid (e.g., root key absent), invalidate the session and initiate re-keying with all affected friends rather than accepting a potentially stale backup.

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
   - Encrypt: `CT_i = ChaCha20-Poly1305(key=MK_i, plaintext=loc, aad=encode(alice_fp_i, bob_fp_i, seq_i))` where `alice_fp_i = SHA-256(EK_A_i.pub)` and `bob_fp_i = SHA-256(EK_B_i.pub)` — the session fingerprints for friendship i (see §8.3).
   - Send `(send_token_i, CT_i, seq_i)` to the server.
3. The server routes each `(send_token_i, CT_i)` frame to the corresponding mailbox.

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
   - `POST /inbox/{token}`: Clients push an encrypted payload into the mailbox.
   - `GET /inbox/{token}`: Clients poll for and drain all available payloads in the mailbox.
3. **Server Obliviousness:** The server does not know the sender or recipient identity—only the opaque routing token.

### 7.2 Invariant: Indistinguishable Responses

To prevent the server from learning whether a routing token corresponds to a real relationship, the following invariant is mandatory:

**The server MUST return an identical response (HTTP 200 OK with `[]`) for all token queries where no messages are pending, regardless of whether the token has ever been "registered" or used.**

There is no "create mailbox" or "register token" step. Mailboxes exist implicitly upon the first `POST`. A `GET` for a non-existent token is indistinguishable from a `GET` for an empty real mailbox.

### 7.3 Metadata Exposure and Traffic Analysis

The server can still observe the timing and frequency of `POST` and `GET` requests for specific tokens. IP correlation can be used to infer relationships over time.

### 7.4 Mitigations

- **Payload padding (mandatory):** All payloads MUST be padded to a fixed length (512 bytes recommended) before encryption. 256 bytes is insufficient: a JSON location payload plus GCM overhead already approaches ~150 bytes, leaving little headroom for variable-length fields. 512 bytes provides comfortable clearance while remaining a small fixed multiple of a cache line.

### 7.4.1 Polling Strategy

To prevent timing-based social-graph inference, Bob MUST poll at a **constant rate** regardless of whether messages are expected. Polling more frequently when a location update is expected, or less frequently when offline, creates a timing side-channel the server can exploit to infer when friends are actively sharing.

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
| One-Time Pre-Key (Bob) | X25519 (`OPK`) | 256-bit | Ephemeral, generated in batches; deleted after DH rotation step |
| Root KDF | HKDF-SHA-256 | 256-bit output | Inputs: DH output + current root key |
| Chain KDF | HKDF-SHA-256 | — | Advancing symmetric ratchet |
| Message encryption | ChaCha20-Poly1305 | 256-bit | Per-message key; deleted after use |
| Message authentication | ChaCha20-Poly1305 tag | 128-bit | Included in AEAD output; covers AAD |
| Key exchange KDF | HKDF-SHA-256 | — | `info = "Where-v1-KeyExchange"` (initial SK) |
| Initial routing tokens | HKDF-SHA-256 | 16-byte output each | `T_AB_0 = HKDF(SK, salt=absent, info="Where-v1-InitToken-AB")[0:16]`; `T_BA_0` uses `info="Where-v1-InitToken-BA"` |
| Discovery token | HKDF-SHA-256 | 16-byte output | `ikm = discovery_secret` (32-byte random, from QR payload), `salt = 0x00*32`, `info = "Where-v1-Discovery"` (§4.2) |
| Bundle auth key | HKDF-SHA-256 | 32-byte output | `K_bundle = HKDF(SK, salt=0, info="Where-v1-BundleAuth")`; for PreKeyBundle HMAC |
| Rotation auth key | HKDF-SHA-256 | 32-byte output | `K_rot = HKDF(root_key, salt=0, info="Where-v1-EpochRotation")`; for EpochRotation AEAD |
| Ack auth key | HKDF-SHA-256 | 32-byte output | `K_ack = HKDF(new_root_key, salt=0, info="Where-v1-RatchetAck")`; for RatchetAck AEAD |

### 8.2 Session State Per Friend-Pair

Each friendship has **two independent symmetric ratchets** (Send and Receive). While location data typically flows only in one direction, the protocol supports bidirectional messaging, and both ratchets are refreshed during the two-step DH rotation.

Alice and Bob each maintain, for each friendship session:
```
SessionState {
  root_key:        [32]byte   // current root key
  send_chain_key:  [32]byte   // CK for outgoing messages (e.g. Alice→Bob)
  recv_chain_key:  [32]byte   // CK for incoming messages (e.g. Bob→Alice)
  send_token:      [16]byte   // token this client posts to
  recv_token:      [16]byte   // token this client polls from
  send_seq:        uint64     // monotonically increasing counter
  recv_seq:        uint64     // highest received seq (for replay rejection)
  alice_ek_pub:    [32]byte   // bootstrap public key EK_A.pub (for safety number)
  bob_ek_pub:      [32]byte   // bootstrap public key EK_B.pub (for safety number)
  alice_fp:        [32]byte   // SHA-256(EK_A.pub) — stable for session lifetime
  bob_fp:          [32]byte   // SHA-256(EK_B.pub) — stable for session lifetime
  k_bundle:        [32]byte   // HKDF(SK, "Where-v1-BundleAuth") — for verifying PreKeyBundle MACs
}
```

Note: `my_ek_priv` (Alice's rotation ephemeral private key) lives only in memory until the DH `dh_out` is computed, then is immediately deleted. There is no epoch counter; DH ratchet advancement is driven by receiving a `RatchetAck`, not by time or message count.

### 8.3 Ratchet Step Functions

**KDF_RK (Diffie-Hellman ratchet step):**
```
(new_root_key, new_chain_key) = HKDF-SHA-256(
    salt = current_root_key,
    ikm  = X25519(ek_priv, ek_pub),
    info = "Where-v1-RatchetStep"
)
```
Output: 64 bytes split as `[0:32] = newRootKey`, `[32:64] = newChainKey`.

A full DH rotation involves two steps to advance both directions (mutual PFS):

1. **Alice initiates** (Step 1):
   `dh_out = X25519(aliceNewEk.priv, Bob.OPK.pub)`
   `(rootKey1, sendChainKey_AB) = KDF_RK(rootKey0, dh_out)`
2. **Bob responds** (Step 2):
   `dh_out = X25519(bobNewEk.priv, Alice.EK_new.pub)`
   `(rootKey2, sendChainKey_BA) = KDF_RK(rootKey1, dh_out)`

Both parties re-derive their routing tokens from the final `rootKey2`.

**The Two-Step DH Rotation:**

A full DH rotation consists of two steps to ensure mutual PFS and refresh both symmetric chains.

1.  **Step 1 (Alice's Contribution):**
    Alice uses a fresh ephemeral key (`alice_new_ek`) and one of Bob's pre-keys (`bob_opk`).
    ```
    (root_key_1, send_chain_key_AB) = KDF_RK(root_key, alice_new_ek_priv, bob_opk_pub)
    ```
    This refreshes the root key and Alice's sending chain (Bob's receiving chain).

2.  **Step 2 (Bob's Contribution):**
    When Bob processes Alice's rotation, he generates a fresh ephemeral key (`bob_new_ek`) and includes it in his `RatchetAck`.
    ```
    (root_key_2, send_chain_key_BA) = KDF_RK(root_key_1, bob_new_ek_priv, alice_new_ek_pub)
    ```
    This refreshes the root key again and Bob's sending chain (Alice's receiving chain).

After Step 2, both parties have refreshed the root key twice and updated both directional chains.

**Routing Token Derivation:**

After each DH ratchet step, new routing tokens MUST be derived from the new root key:
```
new_send_token = HKDF-SHA-256(
    salt = new_root_key,
    ikm  = 0x00...00,
    info = "Where-v1-RoutingToken" || sender_fp || recipient_fp
)[0:16]
```
Direction is encoded implicitly via the `sender_fp || recipient_fp` ordering in the info field.

**Token Transition Protocol (ack-triggered, no dual-polling):**

1. Alice computes new state (new root key, chain keys, routing tokens) from the DH step and stores it as **pending**.
2. Alice sends an `EpochRotation` message **alongside every location update** on the **old `send_token`**, until she receives a `RatchetAck`. She does NOT switch tokens unilaterally.
3. Bob, upon receiving an `EpochRotation`, decrypts it and derives his new state. He **immediately switches** his `recv_token` to the new value and sends a `RatchetAck` as a POST on his `send_token`.
4. Alice, upon receiving a `RatchetAck` that covers the rotation, **atomically commits** the pending state: new root key, chain keys, send/recv tokens.

**Why there is no dual-polling window:** Alice holds the old `send_token` until she gets the ack (step 4). Bob switches `recv_token` immediately on processing the rotation (step 3). Between steps 3 and 4, Bob polls the new `recv_token` (empty — Alice still posts to old) and Alice posts to the old `send_token` (Bob no longer polls it, but the server queues the messages). Once Alice commits in step 4 and switches to the new `send_token`, Bob starts seeing her messages again. At no point does Bob poll two tokens simultaneously.

**KDF_CK (symmetric ratchet step):**
```
(new_chain_key || message_key || message_nonce) = HKDF-SHA-256(
    ikm  = current_chain_key,
    salt = <absent>,
    info = "Where-v1-MsgStep"
)[0:76]
// new_chain_key   = bytes  0–31 (32 bytes)
// message_key     = bytes 32–63 (32 bytes)
// message_nonce   = bytes 64–75 (12 bytes)
```

**Control message authentication:**

Because there are no long-term signing keys in this protocol, `EpochRotation` and `RatchetAck` control messages are authenticated using ChaCha20-Poly1305 keyed from the current session root key. This provides equivalent authentication for a closed two-party session: only a party with access to the root key can produce or verify a valid ciphertext.

*EpochRotation encryption (Alice):*
```
K_rot = HKDF-SHA-256(salt = 0x00...00,
                      ikm  = current_root_key,
                      info = "Where-v1-EpochRotation")[0:32]
nonce = send_seq_be8 || 0x00...00                // seq (8 bytes) || 4 zero bytes = 12 bytes
ct    = ChaCha20-Poly1305(key=K_rot, nonce=nonce,
                    plaintext=rotation_payload_json,
                    aad=alice_fp || bob_fp || send_token)
```

The `send_token` in the AAD cryptographically binds the ciphertext to the specific mailbox, preventing replay to a different token.

*RatchetAck encryption (Bob):*
```
K_ack = HKDF-SHA-256(salt = 0x00...00,
                      ikm  = new_root_key,
                      info = "Where-v1-RatchetAck")[0:32]
nonce = acked_seq_be8 || 0x00...00
ct    = ChaCha20-Poly1305(key=K_ack, nonce=nonce,
                    plaintext=ack_payload_json,
                    aad=alice_fp || bob_fp || send_token)
```

where `send_token` is the mailbox token on which the `RatchetAck` is posted (Bob's send token). `new_root_key` is the post-rotation root key.

*PreKeyBundle authentication (Bob):*
```
K_bundle = HKDF-SHA-256(ikm  = SK,
                         salt = 0x00...00,
                         info = "Where-v1-BundleAuth")[0:32]
mac = HMAC-SHA-256(key=K_bundle,
                   data = v_be4 || send_token || canonical_keys_blob)
```

where `canonical_keys_blob` is the array of `(opk_id_be4 || opk_pub_32bytes)` tuples, length-prefixed with a 4-byte big-endian count.

**Message encryption:**
```
// Nonce is derived deterministically from KDF_CK above
aad   = "Where-v1-Location" || version (4 bytes, BE uint32 = 1)
      || alice_fp (32 bytes, SHA-256(EK_A.pub))
      || bob_fp   (32 bytes, SHA-256(EK_B.pub))
      || seq   (8 bytes, BE uint64)
(ciphertext, tag) = ChaCha20-Poly1305(key=message_key, nonce=message_nonce,
                                  plaintext=loc_json_padded, aad=aad)
```

The `alice_fp` and `bob_fp` in the AAD are the session-scoped fingerprints derived from the bootstrap ephemeral public keys. They are stable for the lifetime of a session and are known to both parties but not transmitted in location frames (they are opaque to the server).

**Note on nonces:** Because `message_key` is unique per message (derived from the ratchet chain), nonce reuse across messages in the normal flow is not a concern. However, if keychain state is restored to an earlier epoch (e.g., backup restoration; see §5.5), the same root key may re-derive the same message key. To eliminate the risk of a (Key, Nonce) collision in such scenarios, this protocol **requires** deterministic nonces derived from the chain state via `KDF_CK`. Implementations MUST NOT use random nonces for location encryption.

### 8.3.1 Ordering, Replay, and Chain Advancement

Each `EncryptedLocation` frame carries a `seq` counter. Recipients enforce:

1. **Replay rejection:** Any frame with `seq <= max_seq_received` is dropped immediately.
2. **Maximum gap (MAX_GAP):** To prevent Denial of Service (DoS) attacks, recipients MUST enforce a maximum gap for chain advancement. If `incoming_seq - max_seq_received > MAX_GAP`, the frame MUST be dropped before any cryptographic work (HKDF iterations) is performed. The recommended `MAX_GAP` is 1024.
3. **Chain advancement for gaps (mandatory):** The symmetric ratchet is a linear hash chain. If Alice sends `seq = N` and Bob has only received up to `seq = N-2` (message `N-1` was dropped), Bob MUST advance the chain `(N - current_seq)` steps to reach the key for `seq = N`. The intermediate key (for the dropped `N-1`) is derived and immediately discarded — it is never used for decryption. Formally: if `incoming_seq > current_seq + 1`, call `KDF_CK` exactly `(incoming_seq - current_seq)` times, retaining only the final `message_key` and `new_chain_key`. Implementations that do not handle this will have their decryption state permanently corrupted by a single dropped frame — an extremely common event in mobile networking.
4. **Out-of-order handling:** A frame with `seq < max_seq_received` but not yet seen cannot be decrypted without the intermediate chain state (which was discarded). Drop it silently. Skipped-message buffering (retaining forward-derived keys for a bounded window) is a possible future extension if real-world loss rates justify it.

Policy (A) above (drop past-seq frames; advance chain for future-seq frames) requires tracking only a single `max_seq_received` (uint64) — O(1) space.

**Note on sequence counter overflow:** The `seq` counter is a `uint64`. While practically impossible to reach at normal data rates, the implementation MUST ensure `seq` never wraps. If `seq` reaches `UINT64_MAX`, the session MUST be invalidated and the friendship re-keyed via a fresh Key Exchange.

### 8.4 Ratchet Advancement Policy

1. Alice advances the **symmetric ratchet** on every location update (every ~30 seconds).
2. Alice attempts a **DH ratchet step** on every location send, as long as:
   - She has at least one cached OPK for Bob, **and**
   - A DH rotation is not already pending (i.e., she has not yet received a `RatchetAck` for the current rotation).
3. While a rotation is pending, Alice includes an `EpochRotation` message alongside every location update she sends, on the **old `send_token`**. She does not advance to the new token until acked.
4. Bob sends a `RatchetAck` only when an `EpochRotation` is present in the batch — a separate POST on Bob's **old** `send_token`. Batches containing only location updates require no response.
5. When Alice receives a `RatchetAck`, she atomically commits the pending rotation state (new root key, chain keys, routing tokens).
6. Bob periodically uploads new `PreKeyBundle` messages to ensure Alice has a supply of OPKs.

If Alice has no cached OPKs for Bob, she continues broadcasting on the symmetric ratchet (per-message FS maintained) with no DH rotation until a new bundle is received.

---

## 9. Wire Format

All messages are JSON-encoded. Every message MUST include a top-level `"v"` field set to the current protocol version (currently `1`). This enables recipients to reject messages from incompatible future versions.

### 9.1 Location Update (Alice → Server → Bob)

```json
{
  "v": 1,
  "type": "Post",
  "token": "<send_token_T>",
  "payload": {
    "type": "EncryptedLocation",
    "seq":   "1337",
    "ct":     "<base64, ChaCha20-Poly1305 ciphertext + 16-byte tag>"
  }
}
```

**Note on the `nonce` field:** The nonce is deterministically derived via `KDF_CK` (§8.3); both sides compute it independently from chain state. It is therefore **not transmitted** in the wire format.

**Note on `ek_pub` absence:** `ek_pub` is confined to the infrequent `EpochRotation` messages (AEAD-wrapped), limiting ratchet-key leakage to one event per DH rotation rather than every location frame.

**Note:** `seq` is encoded as a decimal string to avoid IEEE-754 precision loss in JavaScript clients. Native clients MAY parse it as `uint64`; JS clients MUST treat it as a string.

**AAD (authenticated, not encrypted):**
```
aad = "Where-v1-Location" (18 bytes, UTF-8)
    || version      (4 bytes, big-endian uint32, currently 1)
    || alice_fp     (32 bytes, SHA-256(EK_A.pub) — Alice's session fingerprint)
    || bob_fp       (32 bytes, SHA-256(EK_B.pub) — Bob's session fingerprint)
    || seq   (8 bytes, big-endian uint64)
```

**Plaintext (before encryption):**
```json
{
  "lat": 37.7749,
  "lng": -122.4194,
  "acc": 15.0,
  "ts":  1711152000
}
```

### 9.2 Poll Request (Bob → Server)

```json
{
  "v": 1,
  "type": "Poll",
  "token": "<recv_token_T>"
}
```

### 9.3 KeyExchangeInit, PreKeyBundle, EpochRotation, and RatchetAck

**KeyExchangeInit** (Bob → Alice, posted to discovery token):
```json
{
  "v": 1,
  "type": "KeyExchangeInit",
  "token":            "<base64, T_AB_0>",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "suggested_name":   "Bob",
  "key_confirmation": "<base64, HMAC-SHA-256(SK, 'Where-v1-Confirm' || EK_A.pub || EK_B.pub)>"
}
```

Alice MUST verify `key_confirmation` before accepting the session. Abort and discard if verification fails.

**PreKeyBundle** (Bob → Alice, periodically posted to top up Alice's OPK cache):
```json
{
  "v": 1,
  "type": "Post",
  "token": "<send_token_T>",
  "payload": {
    "type": "PreKeyBundle",
    "keys": [
      {"id": 101, "pub": "<base64_opk1_pub>"},
      {"id": 102, "pub": "<base64_opk2_pub>"}
    ],
    "mac": "<base64, HMAC-SHA-256(K_bundle, v_be4 || send_token || canonical_keys_blob)>"
  }
}
```

where `K_bundle = HKDF(SK, salt=0, info="Where-v1-BundleAuth")[0:32]` and `canonical_keys_blob = count_be4 || (opk_id1_be4 || opk_pub1) || ...`

Alice MUST verify the MAC using her cached `K_bundle` before storing any OPKs.

**EpochRotation** (Alice → Bob, sent alongside every location update while a DH rotation is pending):
```json
{
  "v": 1,
  "type": "Post",
  "token": "<old_send_token_T>",
  "payload": {
    "type": "EpochRotation",
    "ct": "<base64, ChaCha20-Poly1305(key=K_rot, nonce=seq_be8||zeros4, aad=alice_fp||bob_fp||send_token, plaintext=rotation_inner_json)>"
  }
}
```

where the inner plaintext (before encryption) is:
```json
{
  "opk_id":     101,
  "new_ek_pub": "<base64, Alice's new X25519 ephemeral public key>"
}
```

and `K_rot = HKDF(current_root_key, salt=0, info="Where-v1-EpochRotation")[0:32]`. The nonce is `send_seq_be8 || 0x00...00` (12 bytes); `send_seq` is the sequence number of the accompanying location update, ensuring nonce uniqueness.

Bob MUST decrypt using `K_rot` derived from his current root key. If decryption fails (bad key or corrupted), discard the message — do NOT advance the ratchet. If decryption succeeds, Bob immediately switches his `recv_token` to the new value and sends a `RatchetAck`.

**RatchetAck** (Bob → Alice, sent on Bob's `send_token` after every successful receive):
```json
{
  "v": 1,
  "type": "Post",
  "token": "<bob_send_token_T>",
  "payload": {
    "type": "RatchetAck",
    "ct": "<base64, ChaCha20-Poly1305(key=K_ack, nonce=acked_seq_be8||zeros4, aad=alice_fp||bob_fp||bob_send_token, plaintext=ack_inner_json)>"
  }
}
```

where the inner plaintext is:
```json
{
  "acked_seq": 1337
}
```

and `K_ack = HKDF(new_root_key, salt=0, info="Where-v1-RatchetAck")[0:32]` (using the post-rotation root key). Bob sends a `RatchetAck` only when processing an `EpochRotation`; `K_ack` is always derived from the post-rotation root key.

Alice MUST verify the `RatchetAck` using `K_ack` derived from the pending new root key. A successful decryption confirms Bob has the new keys; Alice atomically commits the pending rotation.

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

1. **POST /inbox/{token}:**
   - Push the payload into the corresponding queue.
   - Apply a TTL of 7 days. This aligns with the client re-pair timeout: if Bob has not polled within 7 days, the session will be abandoned on both sides regardless.

2. **GET /inbox/{token}:**
   - Drain and return all messages in the queue.
   - **Constant-Time Invariant:** The server MUST return an identical response (HTTP 200 OK with `[]`) for non-existent tokens. To prevent timing side-channels, the lookup logic must ensure that the time taken to respond for a "hit" (active token) versus a "miss" (empty/unknown token) is indistinguishable to an attacker.

The server exposes only the mailbox API (`POST /inbox/{token}` and `GET /inbox/{token}`).

### 10.3 What Stays the Same

- TLS termination (HTTPS).
- Best-effort delivery model.
- Horizontal scalability.

### 10.4 Server Cannot Decrypt or Link

With this design:
- The server has no knowledge of any session keys or identity keys.
- The server does not know the sender or recipient identity—only the opaque routing token.
- A full server compromise reveals only the timing and frequency of anonymous posts and polls. Social graph and content remain hidden.
- `EpochRotation` and `RatchetAck` payloads are AEAD-encrypted; the server cannot read the `new_ek_pub`, `opk_id`, or ratchet metadata even if it decodes the outer JSON envelope.

---

## 11. Cryptographic Primitives Summary

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Asymmetric key agreement | X25519 (ECDH) | Diffie-Hellman key exchange at bootstrap and each DH ratchet step | libsodium / Tink / CryptoKit |
| Symmetric encryption | ChaCha20-Poly1305 (IETF) | Encrypt location payloads and control messages (AEAD) | libsodium |
| Key derivation (KDF_RK) | HKDF-SHA-256 | Derive new root key and chain key from DH output | libsodium |
| Chain KDF (KDF_CK) | HKDF-SHA-256 | Advance symmetric ratchet; derive message key (32 B) and nonce (12 B) via single 76-byte HKDF expand | libsodium |
| Bundle/session auth | HMAC-SHA-256 | Authenticate `PreKeyBundle` and `KeyExchangeInit` key confirmation | libsodium |
| Hash / fingerprint | SHA-256 | Session fingerprints (`alice_fp`, `bob_fp`), safety number, discovery token | libsodium |
| Random number generation | OS CSPRNG | Ephemeral key generation | `SecureRandom` (Android) / `SecRandomCopyBytes` (iOS) |

**Library recommendations:**
- **Kotlin Multiplatform:** Use [ionspin/kotlin-multiplatform-libsodium](https://github.com/ionspin/kotlin-multiplatform-libsodium) for all cryptographic primitives (X25519, ChaCha20-Poly1305, SHA-256, HMAC-SHA-256). Libsodium provides a unified API across JVM, Android, and iOS, eliminating platform-specific implementation variance. All crypto operations are common-code `expect/actual` implementations.
- **Android / Kotlin:** Libsodium bindings use `libsodium.so` (statically linked). Store root keys in Android Keystore where supported; a Keystore-backed wrapper key can protect the master key material.
- **iOS / Swift:** Libsodium bindings use the native `libsodium` framework (iOS includes sodium.dylib). Key material persists in the Secure Enclave-backed Keychain. SwiftUI calls the KMP shared module for crypto operations.

---

## 12. Open Questions and Future Work

1. **Cross-Device Signing.** The protocol currently scopes identity to a single primary device. A future extension would allow the old device to sign the new pairing's Safety Number, allowing contacts to auto-migrate trust without re-adding the friend.

2. **Post-Quantum Cryptography.** Introducing CRYSTALS-Kyber or similar PQ-resistant key exchange into the ratchet to maintain confidentiality against future quantum adversaries.

3. **Multi-Device Support.** Full session synchronization across multiple devices (e.g., phone and tablet) is a complex challenge planned for future work.

4. **Session Expiry and Staleness Handling.** If Alice stops sharing (app uninstalled, account deleted, extended offline period), Bob's client continues polling indefinitely against a token that will never receive new messages. To provide UX signals, `FriendEntry.isStale` is a heuristic that returns true if a pending rotation has been unacknowledged for more than 2 days (`STALE_THRESHOLD_SECONDS`) or if no acks have been received for more than 7 days (`ACK_TIMEOUT_SECONDS`). Clients SHOULD surface a "no recent location" or "rotation stuck" warning to the user based on this flag.
