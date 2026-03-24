# End-to-End Encrypted Location Sync — Design Document

**Status:** Draft
**Target version:** Where v2
**Authors:** Engineering
**Date:** 2026-03-23

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
11. [Migration Path: v1 → v2](#11-migration-path-v1--v2)
12. [Cryptographic Primitives Summary](#12-cryptographic-primitives-summary)
13. [Open Questions and Future Work](#13-open-questions-and-future-work)

---

## 1. Background and Motivation

### 1.1 Current Architecture (v1)

In v1, each client connects to a Ktor WebSocket server at `/ws?userId=<uuid>`. When a client sends a location update, the server fans out the full user-location map to all connected clients as a JSON `WsMessage.LocationBroadcast`. Each client filters the received list to show only users in its local friends list.

**What the server learns in v1:**
- The precise GPS coordinates of every connected user at all times.
- Who is connected and when (presence).
- The frequency of movement (indirectly, from update cadence).
- The full social graph: the server observes exactly which users are sharing with whom.

The goal of v2 is to make the server cryptographically unable to read location payloads and unable to reconstruct the social graph, even if it is fully compromised.

### 1.2 Scope

This document covers:
- Key establishment between peers with no central identity server
- A ratchet-based forward secrecy scheme adapted for continuous one-way location broadcasting
- Group fan-out (one sender, N recipients)
- The resulting wire format and Ktor server changes

This document does **not** cover:
- Post-quantum cryptography (deferred; see Section 13)
- Multi-device support (deferred)
- Server-side persistence encryption

---

## 2. Threat Model

### 2.1 Attacker Capabilities

| Attacker | Assumed Capabilities |
|---|---|
| Passive network attacker | Can observe all WebSocket frames (mitigated by TLS, included for defense-in-depth) |
| Compromised server | Has full access to the server process, memory, database, and all received ciphertext |
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

- **Server compromise revealing historical locations.** *Forward secrecy (per-message):* deleting each message key `MK_n` immediately after use ensures that compromise of one key does not expose others. *Post-compromise security (epoch-level):* the next DH ratchet step with fresh material limits how long a leaked chain key remains exploitable. However, this is **bounded PCS, not true PCS**: because Bob's DH contribution derives from his long-term identity key `IK.priv`, a future compromise of Bob's `IK.priv` allows retroactive recomputation of all historical epoch keys. See §5.0 and §5.4 for a precise characterisation of these limits.
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing sequence counter which is also authenticated (as AEAD additional data). The recipient rejects any frame with a counter it has already seen.
- **Ciphertext forgery.** AES-256-GCM authentication tags cover both the ciphertext and associated data (sender ID, epoch, sequence number). A server or attacker cannot modify a frame without detection.
- **Long-term key compromise of past sessions.** If an attacker steals a user's long-term identity key today, they cannot decrypt location history from before the current ratchet epoch because message keys are forward-deleted.

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server sees packet timing, packet sizes, and connection metadata regardless of payload encryption. For a location app, timing is nearly as sensitive as content: update intervals increasing from 30 s (moving) to 5 min (stationary) reveal movement state; silence reveals offline/stopped status; synchronized update timing across multiple users suggests co-location. A metadata-analyzing server can infer movement patterns from timing alone, independent of content. Mitigations (cover traffic, padding) are discussed in §7.4; they add overhead and battery cost and are not a panacea. This is elevated here as a top-level limitation for threat models that include a compromised or curious server.
- **Initial TOFU impersonation.** The first key exchange (§4) is accepted unconditionally. If an attacker intercepts Bob's invite before Alice scans it and substitutes their own key, Alice will share her location with the attacker, not Bob. Safety number verification (§3.2) can detect this after the fact, but only if Alice and Bob compare numbers out-of-band — and by then the attacker may have accumulated a full movement history. See §3.1 for the unmitigated risk and §3.2 for the v2 mitigation.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server knows which user IDs are simultaneously connected. If Alice and Bob are both connected and Alice's friend list contains Bob's UUID, the server can infer the friendship even without reading location data (see §7 for partial mitigations).
- **Denial of service.** This protocol does not protect against a server that drops or delays messages.
- **Quantum adversaries.** All DH operations here use X25519 (256-bit elliptic curve). A cryptographically relevant quantum computer running Shor's algorithm could break these. See §13.

---

## 3. Identity and Key Management — Key as Identity

### 3.1 The Model: No Server-Mediated Key Discovery

The core architectural change in v2 is that the long-term identity public key **is** the user's identity. There are no UUIDs in the protocol. There is no central registry where a user can query for a key. Instead, users exchange their keys directly via an "invite" payload (QR code or link).

Because the server is never in the key distribution path, the entire "key transparency" problem is eliminated. Security is bounded by the authenticity of the initial exchange channel.

### 3.2 Naming and Local Aliases

To avoid requiring users to manage public keys in the UI, v2 implements local aliases:
1. **Invite Payload:** An invite contains `{ik_pub, sig_pub, suggested_name: "Alice", fingerprint}`.
2. **Local Import:** When a user imports an invite, the app pre-fills the "suggested_name" but allows the user to rename it locally before confirming.
3. **Local Storage:** The name is a purely local alias. It is never sent to the server and never shared back with the other party after the initial exchange.

### 3.3 Multi-Device and Migration

1. **One Primary Device per Person (v1):** v1 scopes identity to a single primary device (typically a phone).
2. **Device Migration / Lost Device:** When a user gets a new phone or loses their device, they generate a new identity key pair. This is a manual "identity reset":
   - The user reinstalls the app and re-adds all contacts via new invite links.
   - Contacts see a "Safety Number Changed" or "Identity Migrated" notice.
3. **Future (v2):** Cross-device signing (the old device signs the new device's `IK.pub`) is the intended upgrade path to allow contacts to auto-migrate trust.
4. **No Cloud Backup:** Encrypted cloud backup of the identity private key is explicitly excluded, as it reintroduces a third-party trust dependency.

### 3.4 Trust Establishment and Safety Numbers

V2 uses **Trust-on-First-Use (TOFU)** with local key pinning.

**Safety Numbers:** Two users can optionally verify their connection by comparing a safety number fingerprint.
- **Calculation:** `SHA-256(local_IK.pub || remote_IK.pub)`, where the two public keys are concatenated in lexicographical order (sorted by key bytes).
- The result is displayed as a human-readable 40-character hex string or QR code.

**Risk:** If the invite link (Option B, §4.3) is intercepted over an unauthenticated channel (e.g., SMS), an attacker can substitute their own key. Fingerprint verification is the primary countermeasure.

**Key-Change Alert:** Whenever a `KeyExchangeInit` is received for a friend whose `IK.pub` is already pinned locally, the app MUST display a prominent "Safety Number Changed" warning before accepting the new key. The app MUST NOT silently update the pinned key. The user must explicitly confirm the change (ideally after comparing the new safety number out-of-band). This alert fires on: (1) device migration — the friend reinstalled the app and generated a new identity; (2) potential MITM — an attacker is attempting to substitute a different key. The alert text should distinguish "friend migrated their device" (expected) from "identity changed unexpectedly" (suspicious) where possible.

---

## 4. Key Exchange Flow

### 4.1 Prerequisites

Each device holds:
- `IK`: a long-term X25519 key pair for Diffie-Hellman key agreement
- `SigIK`: a long-term Ed25519 key pair for signing — generated **independently** from `IK` with a separate seed. Do **not** derive `IK` from the Ed25519 seed via `crypto_sign_ed25519_sk_to_curve25519` or any shared-seed mechanism; using the same seed for signing and key agreement enables cross-protocol attacks.
- `EK`: an ephemeral X25519 key pair, generated fresh for each key exchange initiation

### 4.2 Option A: In-Person QR Code Exchange (Recommended for bootstrapping)

This is the preferred path for the initial trust establishment. It mirrors X3DH-lite for the two-party case.

**Setup:**

Alice opens "Add Friend" and generates a fresh ephemeral key pair `EK_A`. She then displays a QR code encoding:
```
{
  "ik_pub": base64(Alice.IK.pub),    // X25519 public key
  "ek_pub": base64(Alice.EK_A.pub),  // X25519 ephemeral key
  "sig_pub": base64(Alice.SigIK.pub), // Ed25519 public key
  "suggested_name": "Alice",
  "fingerprint": hex(SHA-256(Alice.IK.pub)[0:10])
}
```

Bob scans the QR code.

**Key Agreement and Routing Token Derivation:**

Bob generates an ephemeral key pair `EK_B`, and computes a 3-term Diffie-Hellman exchange:

```
// DH1: Alice's Identity x Bob's Ephemeral
DH1 = X25519(Bob.EK_B.priv, Alice.IK.pub)
// DH2: Alice's Ephemeral x Bob's Identity
DH2 = X25519(Bob.IK.priv, Alice.EK_A.pub)
// DH3: Alice's Ephemeral x Bob's Ephemeral
DH3 = X25519(Bob.EK_B.priv, Alice.EK_A.pub)

SK  = HKDF-SHA-256(IKM = DH1 || DH2 || DH3,
                    salt = 0x00...00,
                    info = "Where-v2-KeyExchange")

// Derive the initial bootstrap routing token from SK.
// After the first ratchet step, the token rotates per epoch — see §8.3 KDF_RK.
T_AB_0 = HKDF-SHA-256(IKM = SK,
                       salt = 0x00...00,
                       info = "Where-v2-RoutingToken")[0:16]
```

Bob then encrypts `SK` with `Alice.IK.pub` (using ECIES / `crypto_box` semantics) and transmits:
```json
{
  "type":    "KeyExchangeInit",
  "token":   "<base64, first-time bootstrap token T_AB_0>",
  "ik_pub":  "<base64, Bob's X25519 identity public key>",
  "ek_pub":  "<base64, Bob's X25519 ephemeral public key>",
  "sig_pub": "<base64, Bob's Ed25519 signing public key>",
  "sig":     "<base64, Ed25519 signature over (ik_pub || ek_pub || sig_pub)>"
}
```

This message is posted to the server's mailbox API. The server sees only the opaque token and the public key material. It does not know who the sender or recipient is.

Alice receives the `KeyExchangeInit` and:

**Signature verification (mandatory — abort on failure):**
```
sig_bytes     = Base64Decode(sig_field)
ik_pub_bytes  = Base64Decode(ik_pub_field)
ek_pub_bytes  = Base64Decode(ek_pub_field)
sig_pub_bytes = Base64Decode(sig_pub_field)
signed_data   = ik_pub_bytes || ek_pub_bytes || sig_pub_bytes
if not Ed25519Verify(signed_data, sig_bytes, sig_pub_bytes):
    abort — discard message, do not store any key material
```

Alice then recomputes the same DH operations (using her `IK_A.priv` and `EK_A.priv`) to derive `SK` and `T_AB_0`, and the session is bootstrapped. Alice MUST delete `EK_A.priv` immediately after this derivation.

### 4.3 Option B: Out-of-Band Copy-Paste (Existing Flow Extension)

Users share a link through any out-of-band channel (iMessage, Signal, in-person). The link is:
```
where://add?ik=<base64_ik_pub>&sig_pub=<base64_sig_pub>&fp=<fingerprint>&name=<name>
```

The key agreement proceeds identically to Option A. The app MUST display a prominent prompt encouraging fingerprint verification after the friend is added via this path.

### 4.4 What the Server Learns from Key Exchange

- A message was posted to and polled from an opaque 16-byte token.
- Both public keys (they are intentionally public material).
- Nothing about the resulting shared secret `SK` or the identities of the participants.

The server cannot derive `SK` or link the routing token to a real identity without one of the parties' private keys.

---

## 5. Ratchet Design for One-Way Streaming Location Data

### 5.0 Architectural Trade-offs vs. Signal Double Ratchet

This protocol uses a hybrid ratchet designed for **one-way streaming** (Alice sends continuously; Bob is a passive receiver), not bidirectional messaging. This asymmetry produces weaker security guarantees than Signal's Double Ratchet:

| Property | Signal Double Ratchet | This Protocol |
|---|---|---|
| Forward secrecy | Per message | Per message |
| Post-compromise security | Per round-trip (both parties contribute fresh DH material) | Per `RatchetAck` interval (≤10 min if Bob is online; degrades if offline — see §5.3.1) |
| Bob's DH contribution | On every reply | Only via periodic `RatchetAck` |
| Long-term identity key compromise | Limited to current session's secret material | Retroactively breaks all historical epoch keys (Bob's `IK.priv` never changes) |

For a location app these trade-offs are acceptable: location data is time-sensitive, Bob's passive-receiver role is fundamental to the architecture, and users tolerate offline periods. The protocol's ratchet is not a drop-in Double Ratchet and should not be described as such.

### 5.1 The Signal Double Ratchet — Brief Recap

The Double Ratchet algorithm ([Signal spec](https://signal.org/docs/specifications/doubleratchet/)) combines two mechanisms:

1. **Symmetric-key ratchet (KDF chain):** Given a chain key `CK`, each message derives `(CK', MK)` where `CK'` replaces `CK` for the next message and `MK` is the per-message encryption key. This is a one-way operation (forward secrecy: deleting `CK` makes `MK` irrecoverable).

2. **Diffie-Hellman ratchet:** Each party sends a fresh DH public key with each message. When a new DH public key is received, both parties compute a new DH output and use it to re-derive the root key and chain keys. This provides post-compromise security: if chain keys leak, the next DH ratchet step heals the session.

**The core tension for Where:** The DH ratchet step requires bidirectional communication — Alice sends her DH ratchet key, Bob responds with his, and the exchange drives derivation of new chain keys. In a messaging app, every reply naturally carries a new DH ratchet key. In a location app, Alice broadcasts continuously and Bob never "replies" — he is a passive consumer of Alice's location.

### 5.2 The Problem with Naive Double-Ratchet for Streaming

If we naively apply the Double Ratchet to Alice's location stream:

- Alice advances her **sending chain** (symmetric ratchet) with every location update: `(CK', MK_n) = KDF_CK(CK)`. This gives forward secrecy at per-message granularity. If her chain key leaks at message 100, messages 1–99 are protected.
- Alice also needs to advance the **DH ratchet** periodically to achieve post-compromise security. But this requires Bob to send back a new DH public key so Alice can compute the new shared DH secret. Bob is a passive receiver; he has no natural trigger to send anything.

**Consequences of no DH ratchet advancement:**
- If Alice's current sending chain key is compromised, all future messages in that chain epoch are recoverable until the root key is refreshed.
- The DH ratchet step (which provides PCS) never fires.

### 5.3 Ratchet Advancement Strategies

#### Strategy 1: Time-Based Epoch Rotation

Alice's symmetric ratchet advances per-message. Additionally, every `T` minutes, Alice generates a fresh ephemeral DH key pair, and the new epoch is announced as a special `EpochRotation` message:

```json
{
  "type":       "EpochRotation",
  "from":       "alice-uuid",
  "to":         "bob-uuid",
  "epoch":      43,
  "new_ek_pub": "<base64, Alice's new X25519 ephemeral public key>",
  "sig":        "<base64, Ed25519 signature over (from || to || epoch || new_ek_pub)>"
}
```

When Bob receives an `EpochRotation`:
1. He computes `new_root_input = X25519(Bob.IK.priv, Alice.NewEK.pub)`.
2. `(new_root_key, new_CK) = HKDF(old_root_key, new_root_input, "Where-v2-Epoch")`.
3. He discards the old chain state and updates to `new_CK`.

Bob sends nothing back. Alice uses `new_CK` to seed the next epoch's symmetric ratchet.

**Tradeoffs:**
- Simple; no protocol round-trip required.
- `T = 5 minutes` is a reasonable default at 30-second update intervals (10 messages per epoch). Shorter epochs give finer forward secrecy granularity but increase bandwidth and computation.
- PCS granularity is bounded by `T`: if Alice's chain key leaks, an attacker can decrypt at most `T` minutes of location history before the next epoch heals the session.
- **Weakness:** Bob's DH private key never changes. If Bob's `IK.priv` is compromised, the attacker can recompute all epoch keys from Alice's `EpochRotation` messages. Bob's DH contribution is static.

#### Strategy 2: Message-Count-Based Epoch Rotation

Advance the DH ratchet every `K` location updates rather than every `T` minutes.

**Tradeoffs:**
- Tied to data rate, not wall time. Consistent forward secrecy density regardless of movement frequency.
- At 30-second intervals with `K = 20`, epochs rotate roughly every 10 minutes.
- Same structural weakness as Strategy 1 regarding Bob's static private key.

#### Strategy 3: Hybrid — Time + Bob Acknowledgments (Recommended)

The cleanest solution is to allow Bob to periodically reply on the channel, even though he is receiving-only for location data.

**Bob sends a "ratchet reply" message every `R` minutes (e.g., R = 10):**

```json
{
  "type":       "RatchetAck",
  "from":       "bob-uuid",
  "to":         "alice-uuid",
  "epoch_seen": 41,
  "new_ek_pub": "<base64, Bob's new X25519 ephemeral public key>",
  "sig":        "<base64, Ed25519 signature over (from || to || epoch_seen || new_ek_pub)>"
}
```

Alice, upon receiving Bob's `RatchetAck`:
1. Computes `dh_out = X25519(Alice.CurrentEK.priv, Bob.NewEK.pub)`.
2. Derives `(new_root_key, new_send_CK) = HKDF(root_key, dh_out, "Where-v2-RatchetStep")`.
3. Generates a fresh `Alice.NewEK` for the next step.
4. Sends an `EpochRotation` message (see §9.3) carrying her new `EK.pub`. The `EK.pub` is confined to `EpochRotation` only and is **not** included in ordinary `EncryptedLocation` frames (see §9.1), which would expose key-transition timing to the server.

This restores the full Double Ratchet security guarantee: both parties contribute fresh DH material, so compromise of either party's ephemeral keys is healed within `R` minutes.

**Tradeoffs:**
- Adds 1 uplink message per 10 minutes from Bob. At `R = 10 min`, this is ~144 messages/day per friendship pair — trivial overhead.
- Bob must remain connected (or buffer the `RatchetAck` for delivery when reconnected).
- If Bob is offline for an extended period (days), Alice cannot advance the DH ratchet. A time-based fallback (Strategy 1) should fire if no `RatchetAck` is received within `2R` minutes — sacrificing PCS but maintaining forward secrecy.

#### 5.3.1 Offline and Failure Modes

**Offline Bob:** If Bob is offline, Alice receives no `RatchetAck`. After `2R` minutes (default: 20 min), Alice falls back to a time-based DH ratchet step using Bob's last known `their_ek_pub`. During the `2R`-minute offline window, the DH ratchet does not advance; only the per-message symmetric ratchet (forward secrecy) is active. PCS is not provided during this window.

**`RatchetAck` loss or delay:** If a `RatchetAck` is dropped, Alice will eventually time out and fall back. To accelerate recovery, Alice SHOULD retransmit her latest `EpochRotation` if no `RatchetAck` is received within `R` minutes (rather than waiting for the full `2R` timeout). A `RatchetAck` that arrives after Alice has already fallen back is still valid: Alice SHOULD apply it to advance the DH ratchet from the current state, even if out of the expected window.

**Device crash or restart:** Session state (root key, current EK) is reloaded from the platform keychain on restart. Bob's subsequent `RatchetAck` will use his new ephemeral key. Alice applies it, advances the DH ratchet, and the session recovers without re-keying.

**Summary of PCS guarantees in hybrid mode:**

| Scenario | PCS guarantee |
|---|---|
| Both online, `RatchetAck` delivered | True bilateral PCS within `R` minutes |
| Bob offline `< 2R` min | No PCS (symmetric FS only); DH ratchet stalls |
| Bob offline `> 2R` min | One-sided DH refresh (Alice's EK changes; Bob's does not) |
| Bob offline indefinitely | Per-message FS only; no PCS |

### 5.4 Forward Secrecy Granularity vs. Overhead Analysis

| Strategy | FS Granularity | PCS | Extra Messages/day (10 friends) | Notes |
|---|---|---|---|---|
| Per-message symmetric only | Per message | None | 0 | No DH ratchet; chain key compromise = full future exposure |
| Time-based (T=5 min) | Per message | One-sided DH refresh¹ | ~288 EpochRotation messages | Not true PCS; Bob's `IK.priv` compromise retroactively breaks all epochs |
| Message-count (K=20) | Per message | One-sided DH refresh¹ | ~288 EpochRotation messages | Same weakness as time-based |
| Hybrid RatchetAck (R=10 min) | Per message | Bilateral within R min² | ~288 location + 144 RatchetAck | Closest to Double Ratchet PCS; recommended |

¹ *One-sided DH refresh:* Alice contributes a new ephemeral key each epoch, but Bob's DH input remains his long-term `IK.priv`. If Bob's `IK.priv` is ever compromised (now or in the future), all historical epoch keys can be recomputed. This is not post-compromise security in the Signal sense.

² *Bilateral PCS:* Both Alice and Bob contribute fresh ephemeral material per `RatchetAck` cycle. Degrades to one-sided if Bob is offline for `> 2R` minutes; see §5.3.1.

### 5.5 Message Key Deletion Policy

Forward secrecy is only as strong as the message key deletion discipline:

- Message keys `MK_n` MUST be deleted from memory immediately after encrypting/decrypting the corresponding frame.
- Chain keys `CK` MUST be deleted from memory after deriving the next step.
- Root keys MUST be overwritten immediately after deriving new chain keys.
- Ephemeral DH private keys MUST be deleted after computing the shared secret.
- On Android, keys live in a `SecureRandom`-backed in-memory structure; `Arrays.fill(key, 0)` before GC. On iOS, `Data` is zeroed explicitly before dealloc.
- No message keys or chain keys are persisted to disk. If the app is killed and restarted, the session restarts from the last stored epoch state (root key + current EK). The root key is stored in the platform keychain (Android Keystore / iOS Secure Enclave-backed Keychain).

**Keychain backup and state-rollback risk:** Platform keychains may be backed up (iCloud Keychain on iOS, Google Play Backup on Android). If a backup is restored to a different device or is compromised, the attacker gains access to the stored root key and current chain key, and can re-derive message keys from the backed-up epoch forward — until the next DH ratchet step heals the session.

Mandatory mitigations:
- **iOS:** Mark all session-state keychain items with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. This attribute excludes the item from iCloud Backup.
- **Android:** Store session state in `EncryptedSharedPreferences` backed by a Keystore key created with `setIsStrongBoxBacked(true)` and `allowBackup=false` in the manifest.
- **Both:** On detecting a fresh install or that session state is missing/invalid (e.g., root key absent, epoch counter reset), invalidate the session and initiate re-keying with all affected friends rather than accepting a potentially stale backup.

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
   - Encrypt: `CT_i = AES-256-GCM(key=MK_i, plaintext=loc, aad=encode(alice_uuid, f_i_uuid, epoch_i, seq_i))`.
   - Send `(f_i_uuid, CT_i, epoch_i, seq_i)` to the server.
3. The server routes each `(f_i_uuid, CT_i)` frame to the corresponding connected client.

**Bandwidth:** For N friends, Alice sends N encrypted frames per location update. At 30-second intervals with 20 friends and ~100 bytes per ciphertext, this is ~2,000 bytes per update — entirely within mobile data budgets.

**Comparison to group key schemes:**

| Approach | Bandwidth | Blast radius on key compromise | Implementation complexity |
|---|---|---|---|
| Per-friend ratchet (recommended) | O(N) per update | Single friend | Low |
| Signal Sender Keys | O(1) send + O(N) distribute | All current group members | Medium |
| MLS (RFC 9420) | O(log N) tree ops | Single member | High |

Signal's Sender Keys protocol ([documented in their group messaging work](https://eprint.iacr.org/2023/1385.pdf)) would reduce Alice's outbound bandwidth to O(1) — she encrypts once and sends one ciphertext — but the server must then fan it out, and all members share a forward-ratcheting sender key. One compromised recipient can derive past messages from the shared chain. For Where's small group sizes (typically < 50 friends), O(N) per-friend encryption is the right tradeoff: simpler, stronger blast-radius isolation, no group-state synchronization needed.

MLS (RFC 9420) is the gold standard for large groups and achieves O(log N) key operations. It is also vastly more complex to implement correctly, requires tree state synchronization, and is overkill for groups of tens of users. It is noted here for completeness; it could be considered if Where scales to large ephemeral groups.

**Scalability note:** For N ≤ 50 friends, per-friend encryption is practical — deriving 50 message keys per location update is sub-millisecond on modern mobile hardware, and 20-friend sessions require ~8 KB per 30-second cycle. For N > 500, sender-key schemes become meaningfully more attractive on bandwidth and compute. The current design is optimized for small groups (typical 5–50 friends). If Where adds large group shares or public location broadcasts, a shared-key model at that layer should be evaluated separately.

### 6.3 Handling Friend Add/Remove

**Adding a friend:** When Alice adds Bob as a friend, she runs the key exchange (§4) and initializes a new ratchet session seeded from the resulting `SK`. There is no effect on other friends' sessions.

**Removing a friend:** Alice removes the ratchet session state for Bob. Since she was encrypting separately for each friend, Bob's removal immediately stops the flow of ciphertext addressed to him. He cannot recover future location updates.

**Important:** Removing Bob does not provide cryptographic protection against Bob's having cached past location updates. If Bob logged Alice's decrypted locations before being removed, there is no technical mechanism to prevent that. This is a property of the application layer (consent model), not the cryptographic protocol.

**Multi-device note:** This design does not address multi-device friend-list synchronisation (deferred to §13). Friend additions and removals are per-device: a user with two devices may have divergent friend lists, causing different friends to receive location from each device. This is a known v2 limitation; multi-device support with synchronised friend lists is planned for future work.

---

## 7. Server's Role and Obliviousness

### 7.1 The Mailbox Model

The server's role is strictly limited to acting as a stateless message router for anonymous mailboxes. It no longer uses WebSockets for push-based routing.

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

- **Payload padding (mandatory):** All payloads MUST be padded to a fixed length (e.g., 256 bytes) before encryption.
- **Tor / Onion Routing (Future):** Routing via Tor hides IP addresses, providing the strongest protection against traffic analysis.

### 7.4.1 Polling Strategy

To prevent timing-based social-graph inference, Bob MUST poll at a **constant rate** regardless of whether messages are expected. The recommended cadence is once per `R` seconds (default: `R = 10` seconds). Polling more frequently when a location update is expected, or less frequently when offline, creates a timing side-channel the server can exploit to infer when friends are actively sharing.

Bob polls for all of his friendship tokens in a fixed, shuffled order. The shuffle MUST be re-randomised on each poll cycle to prevent ordering-based inference. This, combined with the indistinguishable-response invariant (§7.2), means the server cannot distinguish "polling for a real active friendship" from "polling for a stale or never-used token".

### 7.5 Future: Dummy Token Polling

Because of the indistinguishable response invariant (§7.2), clients can implement **dummy token polling** as a future enhancement. A client polls for random "dummy" tokens alongside real ones. The server cannot distinguish real polls from noise. This significantly raises the bar for traffic analysis and requires no server-side changes to implement. Dummy polling is deferred until the user base is large enough to provide meaningful cover traffic.

---

## 8. Concrete Protocol Recommendation

### 8.1 Key Types and Operations

| Purpose | Algorithm | Key size | Notes |
|---|---|---|---|
| Long-term identity (signing) | Ed25519 (`SigIK`) | 256-bit | One per device, stored in Keychain/Keystore. Independent keypair — **not** derived from `IK`. |
| Long-term identity (DH) | X25519 (`IK`) | 256-bit | One per device. Generated independently from `SigIK` with a distinct seed. |
| Ephemeral DH | X25519 | 256-bit | Generated per key exchange; deleted after use |
| Root KDF | HKDF-SHA-256 | 256-bit output | Inputs: DH output + current root key |
| Chain KDF | HMAC-SHA-256 | — | Advancing symmetric ratchet |
| Message encryption | AES-256-GCM | 256-bit | Per-message key; deleted after use |
| Message authentication | AES-256-GCM tag | 128-bit | Included in AEAD output; covers AAD |
| Key exchange KDF | HKDF-SHA-256 | — | `info = "Where-v2-KeyExchange"` |

### 8.2 Session State Per Friend-Pair

Alice maintains, for each friend B:
```
SessionState {
  root_key:        [32]byte   // current root key
  send_chain_key:  [32]byte   // CK for Alice→B direction
  recv_chain_key:  [32]byte   // CK for B→Alice direction
  routing_token:   [16]byte   // T_AB derived during key exchange
  send_seq:        uint64     // monotonically increasing counter
  recv_seq:        uint64     // highest received seq
  epoch:           uint32     // increments on each DH step
  my_ek_priv:      [32]byte   // current ephemeral private key
  my_ek_pub:       [32]byte   // current ephemeral public key
  their_ek_pub:    [32]byte   // their last known ephemeral public key
}
```

### 8.3 Ratchet Step Functions

**KDF_RK (Diffie-Hellman ratchet step):**
```
(new_root_key, new_chain_key) = HKDF-SHA-256(
    salt = current_root_key,
    ikm  = X25519(my_ek_priv, their_ek_pub),
    info = "Where-v2-RatchetStep"
)
```
Output: 64 bytes split as `[0:32] = new_root_key`, `[32:64] = new_chain_key`.

After each DH ratchet step, the routing token for this friendship pair MUST be re-derived from the new root key:
```
new_routing_token = HKDF-SHA-256(
    salt = 0x00...00,
    ikm  = new_root_key,
    info = "Where-v2-RoutingToken"
)[0:16]
```
This ensures the routing token rotates with every epoch, preventing the server from correlating historical traffic for a friendship pair via a static token. The initial bootstrap token `T_AB_0` (§4.2) is replaced by the epoch-1 token after the first ratchet step completes. Implementations SHOULD discard `T_AB_0` after the first successful DH ratchet and never reuse it.

**Token Transition Protocol:**
When the routing token changes (e.g., Alice sends an `EpochRotation` to the *current* token containing her new `EK.pub`):
1. Alice begins posting all subsequent messages (including the `EpochRotation` if possible, or following frames) to the `new_routing_token`.
2. Bob, upon receiving the `EpochRotation` and deriving the `new_routing_token`, MUST immediately start polling both the `current` and `new` tokens.
3. Bob stops polling the `current` token only after he successfully receives at least one valid `EncryptedLocation` frame on the `new` token, or after a safety timeout of `2 * R` seconds.

**KDF_CK (symmetric ratchet step):**
```
new_chain_key  = HMAC-SHA-256(key=chain_key, data=0x01)
message_key    = HMAC-SHA-256(key=chain_key, data=0x02)
message_nonce  = HMAC-SHA-256(key=chain_key, data=0x03)[0:12] // Mandatory
```
The first two outputs are identical to the Signal spec's KDF_CK construction. The third output provides a deterministic nonce mandatory for v2 to protect against state-rollback attacks.

**Message encryption:**
```
// Nonce is derived deterministically from KDF_CK above
aad   = "Where-v2-Location" || sender_fp || recipient_fp || epoch || seq
(ciphertext, tag) = AES-256-GCM(key=message_key, nonce=message_nonce,
                                  plaintext=loc_json_padded, aad=aad)
```
Where `sender_fp` and `recipient_fp` are the first 8 bytes of `SHA-256(IK.pub)`. This replaces UUIDs in the authenticated data.

The `"Where-v2-Location"` prefix provides domain separation from other protocol contexts (e.g., `EpochRotation`, future protocol versions), preventing cross-context AAD collisions or forgery attempts.

**Note on nonces:** Because `message_key` is unique per message (derived from the ratchet chain), nonce reuse across messages in the normal flow is not a concern. However, if keychain state is restored to an earlier epoch (e.g., backup restoration; see §5.5), the same root key may re-derive the same message key. To eliminate the risk of a (Key, Nonce) collision in such scenarios, v2 **requires** deterministic nonces derived from the chain state via `KDF_CK`. Implementations MUST NOT use random nonces for location encryption.

### 8.3.1 Ordering and Replay Handling

Each `EncryptedLocation` frame carries a `seq` counter. Recipients enforce:

1. **Replay rejection:** Any frame whose `seq` is already in the set of observed sequence numbers is dropped immediately.
2. **Out-of-order handling:** A frame with `seq < max_seq_received` but not yet seen is a reordered delivery. Two approaches are viable:
   - **(A) Strict ordering (recommended for v2):** Reject any frame with `seq < max_seq_received`. Because message keys are forward-derived and the previous chain key is deleted, the recipient cannot retroactively derive `MK_n` for `n < current`. Strict ordering is therefore the natural policy; it may cause rare dropped updates on lossy connections, but location data has low tolerance for stale state anyway.
   - **(B) Skipped-message buffering (future):** Store message keys for a bounded window of `seq` values ahead of current and buffer out-of-order frames. Adds memory overhead and implementation complexity; deferred to a future revision if real-world out-of-order loss rates justify it.

For v2, policy (A) is used. Monitor server-side sequence gaps to calibrate whether policy (B) is needed.

### 8.4 Ratchet Advancement Policy

Per §5.3 Strategy 3 (Hybrid):

1. Alice advances the **symmetric ratchet** on every location update (every ~30 seconds).
2. Alice advances the **DH ratchet** when she receives a `RatchetAck` from Bob containing a new `ek_pub`.
3. If Alice has not received a `RatchetAck` from Bob within 20 minutes (i.e., `2R` for `R = 10 min`), she falls back to a **time-based DH ratchet step** using Bob's last known `their_ek_pub` (no new DH input from Bob, but the epoch key refreshes Alice's side). This degrades PCS but maintains FS.
4. `EpochRotation` messages are sent at the start of each DH ratchet step to notify Bob of Alice's new `ek_pub`.

---

## 9. Wire Format

All WebSocket messages are JSON-encoded binary frames.

### 9.1 Location Update (Alice → Server → Bob)

```json
{
  "type": "Post",
  "token": "<routing_token_T>",
  "payload": {
    "type": "EncryptedLocation",
    "epoch": 42,
    "seq":   "1337",
    "nonce":  "<base64, 12 bytes>",
    "ct":     "<base64, AES-256-GCM ciphertext + 16-byte tag>"
  }
}
```

**Note:** `ek_pub` is intentionally absent from `EncryptedLocation` frames. Including it in every frame would expose epoch-transition timing to the server. The current epoch's `ek_pub` is carried only in `EpochRotation` messages (§9.3); Bob uses the `epoch` field to look up the corresponding ratchet state.

**Note:** `seq` is encoded as a decimal string to avoid IEEE-754 precision loss in JavaScript clients (which lose integer precision above 2⁵³). Native clients MAY parse it as `uint64`; JS clients MUST treat it as a string and use a `BigInt` library for comparison.

**AAD (authenticated, not encrypted):**
```
aad = "Where-v2-Location" (18 bytes, UTF-8)
    || sender_fp   (8 bytes, first 8 bytes of SHA-256(sender IK.pub))
    || recipient_fp (8 bytes, first 8 bytes of SHA-256(recipient IK.pub))
    || epoch (4 bytes, big-endian uint32)
    || seq   (8 bytes, big-endian uint64)
```

Using identity fingerprints rather than the routing token in the AAD prevents the server (which knows the routing token) from using the AAD as a correlation handle. The fingerprints are not transmitted in the frame and are therefore opaque to the server.

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
  "type": "Poll",
  "token": "<routing_token_T>"
}
```

### 9.3 Epoch Rotation and RatchetAck

These follow the same `Post` envelope.

**EpochRotation** (Alice → Bob, sent when Alice advances the DH ratchet):
```json
{
  "type": "Post",
  "token": "<routing_token_T>",
  "payload": {
    "type": "EpochRotation",
    "epoch": 43,
    "new_ek_pub": "<base64, Alice's new X25519 ephemeral public key>",
    "sig": "<base64, Ed25519 signature over (epoch || new_ek_pub) using Alice's SigIK>"
  }
}
```

**RatchetAck** (Bob → Alice, sent every `R` minutes when Bob is online):
```json
{
  "type": "Post",
  "token": "<routing_token_T>",
  "payload": {
    "type": "RatchetAck",
    "epoch_seen": 41,
    "new_ek_pub": "<base64, Bob's new X25519 ephemeral public key>",
    "sig": "<base64, Ed25519 signature over (epoch_seen || new_ek_pub) using Bob's SigIK>"
  }
}
```

---

## 10. Server Changes

### 10.1 What Changes

| Component | v1 Behavior | v2 Change |
|---|---|---|
| Routing Model | Broadcasts locations by UUID | **Anonymous Mailboxes.** Routes opaque `EncryptedLocation` payloads by pairwise routing tokens (T). |
| `/ws?userId=<uuid>` | Authenticates/registers by UUID | **Registration-less.** Clients just poll for tokens; the server does not need to know the global UUID. |
| In-memory store | Plaintext `UserLocation` map | **Opaque Payload Buffer.** Brief TTL buffer of encrypted payloads indexed by T. |
| Metadata Exposure | Full social graph visible | **Obfuscated.** Routing tokens are pairwise and random-looking; social graph is hidden from the server. |

### 10.2 Routing Table

The server maintains an in-memory or persisted map of **mailboxes** indexed by 16-byte routing tokens:
```kotlin
val mailboxes: ConcurrentHashMap<RoutingToken, Queue<EncryptedMessage>>
```

1. **POST /inbox/{token}:**
   - Push the payload into the corresponding queue.
   - Apply a TTL of at least 30 minutes (minimum `2R = 20 min` to cover the offline RatchetAck fallback window; 30–60 min recommended in practice). The previous 5-minute TTL was too short: Bob misses all location updates if offline for more than 5 minutes.

2. **GET /inbox/{token}:**
   - Drain and return all messages in the queue.
   - **Constant-Time Invariant:** The server MUST return an identical response (HTTP 200 OK with `[]`) for non-existent tokens. To prevent timing side-channels, the lookup logic must ensure that the time taken to respond for a "hit" (active token) versus a "miss" (empty/unknown token) is indistinguishable to an attacker. This may require padding the response time to a fixed threshold or using constant-time map lookups.

The server no longer uses the `/ws` WebSocket endpoint or the `/locations` REST endpoint.

### 10.3 What Stays the Same

- TLS termination (HTTPS).
- Best-effort delivery model.
- Horizontal scalability via Redis if needed.

### 10.4 Server Cannot Decrypt or Link

With this design:
- The server has no knowledge of any session keys or identity keys.
- The server does not know the sender or recipient identity—only the opaque routing token.
- A full server compromise reveals only the timing and frequency of anonymous posts and polls. Social graph and content remain hidden.

---

## 11. Migration Path: v1 → v2

### 11.1 Compatibility

v1 and v2 clients cannot interoperate on the encrypted channel. A v1 client connected to a v2 server will receive `EncryptedLocation` frames it cannot decrypt; a v2 client connected to a v1 server will receive plaintext `LocationBroadcast` frames that do not match the expected format.

Migration is therefore a flag-day cut for the server, with a grace period for clients.

### 11.2 Phased Rollout

**Phase 1 — Server v2 deployed, clients v1 still in use:**
- Server v2 supports both the old `LocationBroadcast` message type and the new `EncryptedLocation` routing.
- Old v1 clients continue to work unchanged (server still fans out their plaintext broadcasts).
- New v2 clients send encrypted frames. v1 clients receive them and discard them (unknown message type).
- Users who both upgrade see encrypted location sharing. Mixed pairs see the v1 plaintext path as fallback.

**Phase 2 — v2 client achieves >90% install base:**
- Remove `LocationBroadcast` handling from server.
- Remove `/locations` REST endpoint.
- v1 clients gracefully degrade (they connect but receive no recognisable location updates). The v1 app should display a message such as: "Your friend has upgraded to encrypted location sharing. Update Where to view their location." This requires the v1 client to handle unknown message types gracefully rather than crashing. If the installed v1 client crashes on unknown types, a mandatory v1 patch adding graceful degradation must ship before the server-side cutover.

**Phase 3 — v1 EOL:**
- Server drops connections from clients that send `LocationBroadcast` frames.

### 11.3 Key Exchange Bootstrap During Migration

When a v2 client connects and discovers that a friend has not yet performed key exchange, it:
1. Sends a `KeyExchangeInit` message to the friend's UUID.
2. Falls back to showing "pending encryption setup" in the UI until the friend upgrades and responds.
3. Does NOT fall back to sending plaintext location to unexchanged friends.

This ensures that v2 clients never silently downgrade to plaintext.

**v1 → v2 upgrade key exchange bootstrap:** When a user upgrades from v1 to v2, the app generates a new identity key pair and UUID on first launch. It then sends `KeyExchangeInit` messages to all UUIDs in the existing friend list. v1 friends receive these messages on the v2 server but cannot process them (unknown message type) — the messages are effectively dropped. When a v1 friend subsequently upgrades to v2, their app generates its own identity key pair and responds to any pending `KeyExchangeInit` (or sends a new one); both sides then enter the encrypted protocol. Until that exchange completes, the upgrading user sees "pending encryption" for that friend and receives no location from them.

### 11.4 State Migration

- Existing friend UUIDs remain valid (they are stable identifiers).
- The migration adds an `ik_pub` and `sig_pub` to each friend record.
- On first v2 launch, the app generates the identity key pair and prompts the user to "re-share your code" with friends, triggering the key exchange flow for each friend.

---

## 12. Cryptographic Primitives Summary

| Primitive | Algorithm | Purpose | Library |
|---|---|---|---|
| Asymmetric key agreement | X25519 (ECDH) | Diffie-Hellman key exchange in ratchet and key establishment | libsodium / Tink |
| Digital signatures | Ed25519 | Sign `EpochRotation`, `RatchetAck`, `KeyExchangeInit` | libsodium / Tink |
| Symmetric encryption | AES-256-GCM | Encrypt location payloads (AEAD) | libsodium / JCA / CryptoKit |
| Key derivation (KDF_RK) | HKDF-SHA-256 | Derive new root key and chain key from DH output | BouncyCastle / CryptoKit |
| Chain KDF (KDF_CK) | HMAC-SHA-256 | Advance symmetric ratchet to produce message keys | JCA / CommonCrypto |
| Key exchange KDF | HKDF-SHA-256 | Derive session seed `SK` from X3DH-lite DH outputs | BouncyCastle / CryptoKit |
| Hash / fingerprint | SHA-256 | Public key fingerprints for safety number display | JCA / CryptoKit |
| Random number generation | OS CSPRNG | Nonce generation, ephemeral key generation | `SecureRandom` (Android) / `SecRandomCopyBytes` (iOS) |

**Library recommendations:**
- **Android / Kotlin:** Use [Google Tink](https://github.com/tink-crypto/tink-java) for high-level AES-GCM and HKDF. Use BouncyCastle or Tink's `Hkdf` for key derivation. Store root keys in Android Keystore where possible (note: Keystore does not support raw AES key import on all devices; wrapping with a Keystore-backed AES key is the practical approach).
- **iOS / Swift:** Use Apple's `CryptoKit` framework (`Curve25519.KeyAgreement`, `AES.GCM`, `HKDF`). Key material persists in the Secure Enclave-backed Keychain.
- **Kotlin Multiplatform shared module:** Data models and wire format encoding (`kotlinx.serialization`) remain in shared. Cryptographic operations are platform-specific `expect/actual` declarations — the KMP module should define `expect fun deriveRatchetStep(...)` with `actual` implementations calling Tink (Android) and CryptoKit via Swift interop (iOS).

---

## 13. Open Questions and Future Work

1. **Cross-Device Signing (v2).** While v1 uses a single primary device, v2 will introduce cross-device signing. The old device signs the new device's `IK.pub`, allowing contacts to auto-migrate trust.

2. **Post-Quantum Migration.** Introducing CRYSTALS-Kyber or similar PQ-resistant key exchange into the ratchet to maintain confidentiality against future quantum adversaries.

3. **Multi-Device Support.** Full identity synchronization across multiple devices (e.g., phone and tablet) is a complex challenge planned for future work.

4. **Location Precision Control.** Allowing the sender to degrade location precision (e.g., to neighborhood level) before encryption.

5. **Server-Side Message Buffering TTL Tuning.** The current default TTL is 30–60 minutes (§10.2). The optimal value balances offline tolerance against server memory footprint and should be informed by real-world usage data.
