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

- **Server compromise revealing historical locations.** *Forward secrecy (per-message):* deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others. *Post-compromise security (per DH ratchet step):* the DH ratchet step refreshing the root key and symmetric chains limits how long a leaked chain key remains exploitable.
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing sequence counter which is also authenticated (as AEAD additional data for the body, and encrypted within the header envelope). The recipient rejects any frame with a counter it has already seen within the same DH epoch. Across-epoch replay protection utilizes a sliding window of the most recent 10 DH public keys; replays from older epochs will trigger a speculative ratchet but always fail final AEAD authentication.
- **Ciphertext forgery.** ChaCha20-Poly1305 authentication tags cover both the ciphertext and associated data. In version 1, metadata (DH public key and sequence number) is sealed within an encrypted envelope, preventing a malicious server from reading or correlating them across token rotations.
- **Ratchet hijacking.** All messages are AEAD-encrypted under keys derived from the current session root key and symmetric chains. An attacker without session state or header keys cannot forge or inject valid messages.
- **Key mismatch at bootstrap.** The `key_confirmation` field in `KeyExchangeInit` (§4.2) detects corruption or bit-flips in `EK_B.pub` in transit. It does NOT authenticate the origin of the QR code or defeat an active MITM who can intercept and substitute the initial ephemeral key material. Trust establishment relies on TOFU + Safety Number verification (§3.4).

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server sees packet timing, packet sizes, and connection metadata regardless of payload encryption. For a location app, timing is nearly as sensitive as content: update intervals increasing from 30 s (moving) to 5 min (stationary) reveal movement state; silence reveals offline/stopped status; synchronized update timing across multiple users suggests co-location. A metadata-analyzing server can infer movement patterns from timing alone, independent of content. Mitigations (cover traffic, padding) are discussed in §7.4; they add overhead and battery cost and are not a panacea. This is elevated here as a top-level limitation for threat models that include a compromised or curious server.
- **Initial TOFU impersonation.** The first key exchange (§4) uses an ephemeral key delivered out-of-band (QR or link). If an attacker intercepts or replaces Alice's QR before Bob scans it, Bob will establish a session with the attacker rather than Alice. The `key_confirmation` in `KeyExchangeInit` (§4.2) ensures Bob derived the correct `SK` from the `EK_A.pub` he scanned — but it does not authenticate Alice's QR as originating from Alice rather than an attacker who substituted a different key. Safety number verification (§3.4) is the primary mitigation for this residual TOFU risk.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server never sees user IDs or UUIDs — routing tokens are opaque and random-looking. However, the server observes which IP addresses `POST` to and `GET` from each token. If Alice's IP consistently posts to token T and Bob's IP consistently polls T, the server can infer they share a friendship, even without decrypting any payload. Timing correlation reinforces this: correlated activity (Alice posts, Bob polls seconds later) on the same token is a strong signal. See §7 for partial mitigations (constant-rate polling, dummy tokens).
- **Compromised backups revealing future epochs.** Because the current implementation persists the active ratchet private key (`localDhPriv`) to ensure session stability across app restarts (§5.5), an attacker who recovers a device backup (e.g., iCloud, Google Play Cloud Backup) can perform all future DH ratchet steps until the session is rotated out-of-band. See §5.6 for a detailed analysis of compromise consequences and self-healing (PCS).
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
- **Calculation:** `HKDF-SHA-256(ikm=SHA-256(lower_EK.pub || higher_EK.pub), salt=null, info="Where-v1-SafetyNumber", length=60)`.
- The result is displayed as 12 groups of 5 decimal digits (consistent with §8.3 format).
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
- Once Alice retrieves and processes the `KeyExchangeInit`, she switches to polling `recv_token` for all subsequent messages.
- The discovery token is single-use and ephemeral: implementations MUST discard it after `aliceProcessInit` completes.

### 4.3 Option B: Out-of-Band (URI / Manual)

For situations where QR scanning is impossible (e.g., remote setup over a secure chat), Alice can encode the setup payload as a URI or a JSON string.

**Format:**
```
where://invite?q=<Base64-JSON>
```

The payload is identical to the QR content defined in §4.2. Alice shares this URI via a secure out-of-band channel. Bob clicks the link or imports the string, and the process continues exactly as it would for a QR scan (polling the discovery mailbox).

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
K_confirm = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-Confirm", length=32)

key_confirmation = HMAC-SHA-256(key  = K_confirm,
                                 data = "Where-v1-Confirm" || EK_A.pub || EK_B.pub)
```

Both parties initialize their Double Ratchet state (§8.2) seeded with a root key derived from `SK`. Alice and Bob expand `SK` over 128 bytes to obtain initial chain keys, the starting root key, and the initial header key:

```
(chain_key_0 || chain_key_1 || root_key_0 || header_key_0) = HKDF-SHA-256(
    ikm  = SK,
    salt = null,
    info = "Where-v1-KeyExchange",
    length = 128
)
// Split as: [0:32] = chain_key_0, [32:64] = chain_key_1, [64:96] = root_key_0, [96:128] = header_key_0.
```

- **Alice:** Uses `send_chain = chain_key_0`, `recv_chain = chain_key_1`.
- **Bob:** Uses `send_chain = chain_key_1`, `recv_chain = chain_key_0`.
- **Root Key:** Both start with `root_key = root_key_0`.

Initial routing tokens are also derived from `SK`:

```
T_AB_0 = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-RoutingToken" || alice_fp || bob_fp, length=16)
T_BA_0 = HKDF-SHA-256(ikm=SK, salt=null, info="Where-v1-RoutingToken" || bob_fp || alice_fp, length=16)
```

Bob transmits:
```json
{
  "type":             "KeyExchangeInit",
  "token":            "<base64, T_AB_0>",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "suggested_name":   "Bob",
  "key_confirmation": "<base64, key_confirmation>"
}
```

Bob POSTs this to `POST /inbox/{hex(discovery_token_A)}`.

**Alice's processing:**

Alice receives the `KeyExchangeInit` and:

1. Derives `SK = X25519(Alice.EK_A.priv, Bob.EK_B.pub)`.
2. Recomputes the expected `key_confirmation`.
3. **Aborts and discards** if the MAC does not match — this indicates `EK_B.pub` was corrupted or substituted in transit.
4. Derives `alice_fp`, `bob_fp`, `T_AB_0`, `T_BA_0` using the same formulas above.
5. **Deletes `EK_A.priv` immediately.**
6. Prompts user to name Bob (pre-filled with `suggested_name` from `KeyExchangeInit`).
7. Stores the session.
   - **Bootstrapping the Ratchet:** Alice's initial `remoteDhPub` is set to `EK_B.pub`. This ensures her next outgoing message triggers a DH ratchet step on Bob's side, breaking the bootstrap deadlock. If Bob never sends a message, Alice's ratchet remains in this initial epoch; the periodic Keepalive mechanism (§5.3) is used to ensure eventual rotation even in asymmetric usage.

Bob **deletes `EK_B.priv` immediately** after posting the `KeyExchangeInit`.

---

## 5. Ratchet Design for Bidirectional Sessions

This protocol uses a standard **Double Ratchet** session per friendship. This provides both per-message forward secrecy and post-compromise security (PCS).

### 5.1 The Double Ratchet

The Double Ratchet algorithm ([Signal spec](https://signal.org/docs/specifications/doubleratchet/)) combines two mechanisms:

1.  **Symmetric-key ratchet (KDF chain):** Each message advances a hash chain. Given a chain key `CK`, each message derives `(CK', MK, nonce)` where `CK'` replaces `CK` for the next message, `MK` is the per-message encryption key, and `nonce` is derived deterministically from the same KDF step. Deleting old keys provides forward secrecy.

2.  **Diffie-Hellman ratchet:** Each message carries the sender's current DH ratchet public key. When a new DH public key is received from the peer, both parties perform a DH calculation to refresh the root key and re-initialize the symmetric chains. This provides post-compromise security: if chain keys leak, the next DH ratchet step heals the session.

### 5.2 Unified Messaging Model

In this model, there is no distinction between "Alice sharing with Bob" and "Bob sharing with Alice" at the cryptographic layer. Every friendship is a single bidirectional communication channel.

Each peer sends messages to the other's inbox. A message is either:
-   A **Location Update**: contains coordinates and accuracy.
-   A **Keepalive**: contains an empty payload.

Both message types use the same unified wire format and both carry the sender's current DH ratchet public key in the header.

### 5.3 Gap-Filling and Multi-Epoch Reliability

To handle out-of-order delivery across DH ratchet steps, the receiver maintains a **skipped message key cache** (§5.5). When a receiver observes a gap in sequence numbers, they derive the missing keys and store them for future use.

**Out-of-Order DH Epoch Transitions:**
If Alice ratchets her DH key from `dh_1` to `dh_2`, Bob may receive `Msg(dh_2, seq=1)` before he receives `Msg(dh_1, seq=last)`.
1.  **Speculative Ratchet:** Bob moves to the new DH epoch upon receiving `Msg(dh_2)`.
2.  **Decryption:** Bob decrypts the payload of `Msg(dh_2)` to extract the **encrypted `pn` field** (§7.4).
3.  **Gap Filling:** The `pn` field tells Bob exactly how many messages Alice sent in epoch `dh_1`. Bob goes back to the old chain, derives all remaining keys up to `pn`, and stores them in the cache.
4.  **Historical Delivery:** When `Msg(dh_1, seq=last)` eventually arrives, Bob retrieves the key from the cache, verifies the AAD (using the `pn` stored in the cache entry), and decrypts.

This ensures that gaps are filled deterministically even when the metadata needed for gap calculation is hidden behind the AEAD boundary.

### 5.4 Routing Token Rotation and Reliability

Routing tokens are derived from the current root key. Whenever the DH ratchet advances and a new root key is derived, new routing tokens are computed.

**The Mailbox Synchronization Challenge:**
In an anonymous mailbox model, a client polling token `T_old` will never see a message posted to `T_new`. If Alice switches her send token to `T_new` immediately upon deriving a new root key, Bob (who is still polling `T_old`) will never retrieve the message containing the new DH public key Alice used to derive `T_new`.

**Solution: One-Message Lag for Send Tokens:**
1.  When a DH ratchet step occurs, Alice derives the new root key and the corresponding `send_token_new`.
2.  Alice marks the new token as **pending** (`isSendTokenPending = true`).
3.  Alice prepares her next message using the new DH epoch keys, but **posts it to the old token** (`send_token_prev`).
    *   **Fresh Keepalive Transition:** To ensure the receiver has a valid, non-replay message waiting for them as soon as they switch tokens, Alice immediately follows the transition message with a **fresh Keepalive** encrypted under the new epoch keys and posted to `send_token_new`. This keepalive is gated on `sharingEnabled` to preserve the periodic PCS property even when active location sharing is toggled off (§5.3).
4.  Once both messages are successfully handled, Alice switches to `send_token_new` exclusively.
5.  Bob retrieves the message from the old token, processes the new DH key (extracting the hidden `pn` for gap filling), and immediately switches his receive token to `recv_token_new`.

**Receiver Policy:**
A receiver switches their **receive token** immediately upon receiving a message with a new `dh_pub`. Messages are retrieved in arrival order from the server. If an old-epoch message arrives at the server *after* a new-epoch message has been retrieved and processed, the receiver has already switched tokens and the old message will be missed. This is an acceptable tradeoff for protocol simplicity.

### 5.5 Message Key Deletion Policy

Forward secrecy is only as strong as the message key deletion discipline:
### 5.5 Storage and Memory Hygiene

To maximize forward secrecy, implementations should adhere to the following hygiene rules:

1.  **Zero-after-use:** All temporary buffers used for KDF inputs (`DH_out`, `SK`, `RK`, `CK_n`) MUST be explicitly zeroed immediately after the derived key is computed.
2.  **Delete-after-use:** Each message key `MK_n` MUST be deleted (zeroed) from the `skipped_message_keys` cache immediately after successful decryption.
3.  **Persistence Policy:** 
    - Full `SessionState` (including `localDhPriv`) is persisted to local storage to ensure session stability across app restarts and crashes.
    - **Initial State Hygiene:** Bob's initial ephemeral private key (`ekB.priv`) is copied into `localDhPriv` in the `SessionState` to enable the first DH ratchet step when Alice responds. The original buffer is zeroed immediately after session initialization.
    - To mitigate backup-recovery risks, this state MUST be stored using device-local, backup-excluded security controls (e.g., `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` on iOS, `KeyStore`-backed encryption with `allowBackup=false` on Android).
    - **Sequence Safety:** If the sequence number `sendSeq` reaches its 64-bit maximum (`Long.MAX_VALUE`), the session is considered "bricked" (`SessionBrickedException`) and no further messages can be sent. This prevents nonce reuse. A full out-of-band session reset is required.
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
   - Encrypt: `CT_i = ChaCha20-Poly1305(key=MK_i, plaintext=loc, aad=buildAad(alice_fp_i, bob_fp_i, seq_i, dh_pub_i))` where `alice_fp_i` and `bob_fp_i` are the session fingerprints for friendship i (see §8.3).
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

#### 7.4.1 Metadata Obfuscation: Hidden Chain Length (pn)

Standard Double Ratchet protocols leak the length of the previous symmetric chain (`pn`) in the unencrypted header. This allows a server to observe activity patterns (e.g., "Alice sent 142 messages in her last 30-minute epoch").

To mitigate this, Where moves `pn` into the **encrypted payload**:
1. **Encrypted Envelope:** The `MessagePlaintext` JSON object includes a top-level `pn` field.
2. **Post-Decryption Extraction:** The receiver first ratchets DH, derives the message key, decrypts the payload, and *then* reads `pn` to perform historical gap-filling of the old chain (§5.3).
3. **Cache Storage:** When storing skipped keys for out-of-order delivery, the receiver stores the active `pn` alongside the message key: `(MK_n, Nonce_n, PN_n)`. This ensures that if the message arrives later, the AAD can be re-verified against the correct historical `pn`.

This removes the last piece of unauthenticated chain metadata from the wire format, leaving only `dh_pub` and `seq` visible to the server.

#### 7.4.2 Payload padding

- **Payload padding (mandatory):** All payloads MUST be padded to a fixed length (512 bytes recommended) before encryption. 256 bytes is insufficient: a JSON location payload plus GCM overhead already approaches ~150 bytes, leaving little headroom for variable-length fields. 512 bytes provides comfortable clearance while remaining a small fixed multiple of a cache line.

#### 7.4.3 Polling Strategy

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
  root_key:           [32]byte   // current root key
  send_chain_key:     [32]byte   // CK for outgoing messages
  recv_chain_key:     [32]byte   // CK for incoming messages
  send_token:         [16]byte   // token this client posts to
  recv_token:         [16]byte   // token this client polls from
  prev_send_token:    [16]byte   // one-message lookback token
  is_token_pending:   bool       // true if next message must use prev_send_token
  send_seq:           uint64     // outgoing monotone counter
  recv_seq:           uint64     // highest received seq
  local_dh_pub:       [32]byte   // current local ratchet public key
  remote_dh_pub:      [32]byte   // last received remote ratchet public key
  alice_ek_pub:       [32]byte   // bootstrap public key EK_A.pub (stable)
  bob_ek_pub:         [32]byte   // bootstrap public key EK_B.pub (stable)
  alice_fp:           [32]byte   // SHA-256(EK_A.pub) (stable)
  bob_fp:             [32]byte   // SHA-256(EK_B.pub) (stable)
}
```

### 8.3 Ratchet Step Functions

**KDF_RK (Diffie-Hellman ratchet step):**
```
(new_root_key, new_chain_key) = HKDF-SHA-256(
    salt = current_root_key,
    ikm  = X25519(dh_priv, dh_pub),
    info = "Where-v1-RatchetStep",
    length = 64
)
```
Output: 64 bytes split as `[0:32] = new_root_key`, `[32:64] = new_chain_key`.

**Routing Token Derivation:**

After each DH ratchet step, new routing tokens MUST be derived from the new root key:
```
new_token = HKDF-SHA-256(
    salt = root_key,
    ikm  = <absent>,
    info = "Where-v1-RoutingToken" || sender_fp || recipient_fp,
    length = 16
)
```
Direction is encoded explicitly via the `sender_fp || recipient_fp` ordering in the info field.

**KDF_CK (symmetric ratchet step):**
```
(new_chain_key || message_key || message_nonce) = HKDF-SHA-256(
    ikm  = current_chain_key,
    salt = null,
    info = "Where-v1-MsgStep",
    length = 76
)
// Split as: [0:32] = next_chain_key, [32:64] = message_key, [64:76] = message_nonce.
```

```

The `dh_pub` is included in the AAD to cryptographically bind the message to the current DH epoch.

### 8.3.1 Ordering, Replay, and Chain Advancement

Each message frame carries a `seq` counter. Recipients enforce:

1.  **Replay rejection:** Any frame with `seq <= max_seq_received` (within the same DH epoch) is dropped, EXCEPT if the key for that sequence number is present in the **skipped message key cache**.
2.  **Maximum gap (MAX_GAP):** recipients MUST enforce a maximum gap (default 2000) for chain advancement to prevent resource exhaustion attacks.
3.  **OutOfOrder Support:** If a message is skipped (e.g., recipient receives seq=10 after seq=8), the recipient advances the symmetric ratchet to seq=10 and stores the intermediate message keys in a bounded cache (100 entries).
4.  **Transactional Commitment:** The receiving state (receiving chain, root key, skipped keys) MUST only be updated if the message AEAD authentication succeeds.
5.  **Epoch Transition:** When a message with a new `dh_pub` is received, the `seq` counter resets to 0. All skipped message keys belonging to epochs older than the *previous* valid epoch MUST be cleared.
6.  **Across-Epoch Replay:** Recipients MUST keep track of recently seen `dh_pub` keys (epoch identifiers) and reject any message for an epoch that has already been superseded.


---

## 9. Wire Format

All messages are JSON-encoded. Every message MUST include a top-level `"v"` field set to the current protocol version (currently `1`). This enables recipients to reject messages from incompatible future versions.

### 9.1 Encrypted Location Frame

The mailbox payload for a standard location update is a JSON object with `type: "EncryptedMessage"`. Metadata (`dh_pub`, `seq`) is hidden within an encrypted `envelope` to prevent server correlation.

```json
{
  "type": "EncryptedMessage",
  "v": 1,
  "envelope": "base64(...)",
  "ct": "base64(...)"
}
```

#### 9.1.1 Envelope Structure

The `envelope` is a 77-byte binary blob consisting of:
1. **Nonce (12 bytes)**: A random nonce used for header encryption.
2. **Encrypted Header (65 bytes)**: ChaCha20-Poly1305 ciphertext of the metadata.

**Header Plaintext (49 bytes):**
- `PROTOCOL_VERSION` (1 byte, `0x01`)
- `dh_pub` (32 bytes)
- `seq` (8 bytes, big-endian Long)
- `pn` (8 bytes, big-endian Long)

The header is encrypted using the current `header_key` derived from the DH ratchet.

#### 9.1.2 Ciphertext (ct)

The `ct` field contains the ChaCha20-Poly1305 ciphertext of the location payload, plus a 16-byte authentication tag.

**AAD for Ciphertext:**
- `AAD_PREFIX` ("Where-v1-Message")
- `PROTOCOL_VERSION` (4 bytes, `0x01`)
- `sender_fp` (32 bytes)
- `recipient_fp` (32 bytes)
- `seq` (8 bytes)
- `dh_pub` (32 bytes)

Note that even though `seq` and `dh_pub` are hidden from the server in the envelope, the client uses the *decrypted* values to verify the body AAD, ensuring the body and header are cryptographically bound together.

**Plaintext (before encryption):**
The plaintext is a JSON object. It MUST be padded to exactly 512 bytes before encryption.

For a **Location Update**:
```json
{
  "lat": 37.7749,
  "lng": -122.4194,
  "acc": 15.0,
  "ts":  1711152000,
  "precision": "FINE"
}
```
*   `precision` (string, optional): One of `"FINE"` (exact) or `"COARSE"` (rounded to ~1.1km). If absent, defaults to `"FINE"`.

For a **Keepalive**:
```json
{}
```

### 9.2 Poll Request (Bob → Server)

```json
{
  "v": 1,
  "type": "Poll",
  "token": "<recv_token_T>"
}
```

### 9.3 KeyExchangeInit

**KeyExchangeInit** (Bob → Alice, posted to discovery token):
```json
{
  "v": 1,
  "type": "KeyExchangeInit",
  "token":            "<base64, T_AB_0>",
  "ek_pub":           "<base64, Bob's X25519 ephemeral public key>",
  "suggested_name":   "Bob",
  "key_confirmation": "<base64, key_confirmation>"
}
```

Alice MUST verify `key_confirmation` before accepting the session. Abort and discard if verification fails.

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
- Location and keepalive messages are padded to the same fixed size (512 bytes), so the server cannot distinguish them based on message length.

---

## 11. Cryptographic Primitives Summary

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Asymmetric key agreement | X25519 (ECDH) | Diffie-Hellman key exchange at bootstrap and each DH ratchet step | libsodium / Tink / CryptoKit |
| Symmetric encryption | ChaCha20-Poly1305 (IETF) | Encrypt location payloads and control messages (AEAD) | libsodium |
| Key derivation (KDF_RK) | HKDF-SHA-256 | Derive new root key and chain key from DH output | libsodium |
| Chain KDF (KDF_CK) | HKDF-SHA-256 | Advance symmetric ratchet; derive message key (32 B) and nonce (12 B) via single 76-byte HKDF expand | libsodium |
| Session auth | HMAC-SHA-256 | Authenticate `KeyExchangeInit` key confirmation | libsodium |
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

4. **Session Expiry and Staleness Handling.** If Alice stops sharing (app uninstalled, account deleted, extended offline period), Bob's client continues polling indefinitely against a token that will never receive new messages. To provide UX signals, `FriendEntry.isStale` is a heuristic that returns true if no messages have been received for more than 7 days (`ACK_TIMEOUT_SECONDS`). Clients SHOULD surface a "no recent location" warning to the user based on this flag.
