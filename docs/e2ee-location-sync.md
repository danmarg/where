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
- The precise GPS coordinates of every connected user at all times
- Who is connected and when
- The frequency of movement (indirectly, from update cadence)
- Implicit social graph: if user A has user B's UUID, the server can observe their co-movement patterns

**What a passive network eavesdropper learns in v1:**
- Everything above (TLS mitigates this if deployed, but the server itself is fully trusted)

The goal of v2 is to make the server cryptographically unable to read location payloads, even if it is fully compromised.

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

### 2.2 What This Protocol Protects Against

- **Server compromise revealing historical locations.** A server that is compromised at time T cannot decrypt location updates sent before the last ratchet epoch boundary (forward secrecy). It cannot decrypt updates after epoch boundaries it was not present for (post-compromise security, within limits; see §5.4).
- **Passive eavesdropping.** All location payloads are encrypted with ephemeral symmetric keys derived from a per-friend ratchet. A passive observer with access to ciphertext learns nothing about coordinates.
- **Replay attacks.** Each message carries a monotonically increasing sequence counter which is also authenticated (as AEAD additional data). The recipient rejects any frame with a counter it has already seen.
- **Ciphertext forgery.** AES-256-GCM authentication tags cover both the ciphertext and associated data (sender ID, epoch, sequence number). A server or attacker cannot modify a frame without detection.
- **Long-term key compromise of past sessions.** If an attacker steals a user's long-term identity key today, they cannot decrypt location history from before the current ratchet epoch because message keys are forward-deleted.

### 2.3 What This Protocol Does NOT Protect Against

- **Traffic analysis.** The server still sees packet timing, packet sizes, and connection metadata. An attacker who knows Alice sends ~30-second updates can correlate encrypted frames to Alice's movement patterns (e.g., update frequency drops when she stops moving). Location *frequency* is as sensitive as location *content* for some threat models.
- **A malicious friend.** If Bob is Alice's friend, Bob receives Alice's plaintext location after decryption on his device. Bob can log it, forward it, or otherwise misuse it. The protocol provides no cryptographic protection against a legitimate-but-adversarial recipient.
- **Device seizure or compromise.** If an attacker has physical access to Alice's device, they can read decrypted locations from memory or reconstruct keys from the device's persistent state. This is a device-security problem, not a protocol problem.
- **Metadata about the social graph.** The server knows which user IDs are simultaneously connected. If Alice and Bob are both connected and Alice's friend list contains Bob's UUID, the server can infer the friendship even without reading location data (see §7 for partial mitigations).
- **Denial of service.** This protocol does not protect against a server that drops or delays messages.
- **Quantum adversaries.** All DH operations here use X25519 (256-bit elliptic curve). A cryptographically relevant quantum computer running Shor's algorithm could break these. See §13.

---

## 3. Identity and Key Management — No PKI

### 3.1 The Proposal: Direct Public Key Sharing

The user proposes that each device generates a long-term identity key pair (Ed25519 for signing, X25519 for key agreement) and shares the public key directly with friends alongside the existing UUID, analogous to the current "share your UUID" flow. No central identity server, no certificate authorities.

**Arguments in favor:**

- **Appropriate for the use case.** Where is a small-group app (a user likely has 5–50 friends, not 5 million). The threat model is primarily the server operator or a network attacker, not a compromised friend. For this scale, direct key exchange is operationally tractable.
- **Eliminates a high-value target.** A centralized identity server that maps UUIDs to public keys becomes an extremely attractive target for attackers (and subpoenas). Distributing that mapping to individual devices removes the single point of failure.
- **Alignment with existing UX.** Users already exchange UUIDs out-of-band. Adding a public key to that exchange is a modest incremental change.
- **No third-party trust required.** Alice trusts Bob's public key because Bob gave it to her directly. There is no CA whose compromise would silently undermine all sessions.

**Arguments against:**

- **No revocation mechanism.** If Alice loses her device (or suspects her private key is compromised), she has no way to revoke trust in her old public key. She can generate a new key pair and re-share, but every existing friend must re-do the key exchange manually. There is no CRL, OCSP, or signed key-expiration. In practice, users will not re-exchange keys after a lost phone.
- **Key rotation is a UX and correctness problem.** If Bob gets a new phone and regenerates his key pair, Alice's app will present a "key changed" warning (assuming it even implements that check). Users routinely ignore such warnings (cf. HTTPS certificate errors). A user who taps through a key-change warning after Bob's key was silently replaced by an attacker has been silently MITMed.
- **TOFU is only as strong as the initial exchange.** Trust-on-First-Use means the very first time Alice receives Bob's public key, she accepts it unconditionally. If that initial exchange is intercepted (e.g., Eve intercepts a QR code over a shoulder, or intercepts the copy-pasted string), all future communication is compromised with no way for Alice to detect this. There is no retrospective verification.
- **No cross-device binding.** If Bob runs the app on both his phone and tablet, Alice must separately verify and store two different public keys. There is no protocol that says "these keys belong to the same person."
- **Binding of UUID to public key is unverifiable.** Nothing prevents Eve from constructing a fake `(uuid, publicKey)` pair and presenting it to Alice. Without a signed assertion from some trusted authority, Alice cannot know whether the public key she received truly belongs to the UUID's owner.

### 3.2 Recommended Pragmatic Middle Ground

Rather than requiring a full PKI or central identity server, adopt a lightweight **local key registry** with safety-net UX mirroring what Signal does:

1. **Each device generates a single long-term key pair at first launch.** The identity public key (Ed25519) is concatenated with the UUID and a short fingerprint (e.g., first 20 bytes of `SHA-256(publicKey)`, displayed as 5 groups of 4 hex characters). The full "friend invite" payload is `base64(uuid || publicKey || fingerprint)`.

2. **On first import of a friend's key, the app stores and pins it.** All subsequent location frames signed by a different key for the same UUID trigger a prominent, non-dismissable "Safety Number Changed" alert modeled on Signal's safety numbers feature.

3. **Safety number verification.** Two users can optionally compare a human-readable fingerprint (the 40-hex-character shortform of `SHA-256(alicePublicKey || bobPublicKey)`, sorted by UUID). If they match on both screens, the session is verified out-of-band. This is identical in spirit to Signal's safety numbers and PGP fingerprint verification.

4. **No re-use of identity keys across re-installs.** App uninstall/reinstall generates a new key pair and UUID, forcing all friends to re-do the exchange. This is a deliberate forcing function: it prevents silent key reuse and ties identity churn to the natural friction of reinstallation.

5. **Key rotation is manual and explicit.** There is no automated rotation of the long-term identity key. If a user suspects compromise, they rotate by reinstalling. This trades security-automation for implementation simplicity, appropriate for v2.

---

## 4. Key Exchange Flow

### 4.1 Prerequisites

Each device holds:
- `IK`: a long-term identity key pair (Ed25519 for signing; the same seed can derive an X25519 key pair for key agreement using RFC 8032 / libsodium's `crypto_sign_ed25519_sk_to_curve25519`)
- `EK`: an ephemeral X25519 key pair, generated fresh for each key exchange initiation
- `uuid`: the user's stable random UUID (existing v1 concept)

### 4.2 Option A: In-Person QR Code Exchange (Recommended for bootstrapping)

This is the preferred path for the initial trust establishment. It mirrors X3DH-lite for the two-party case.

**Setup:**

Alice opens "Add Friend" and displays a QR code encoding:
```
{
  "uuid": "alice-uuid",
  "ik_pub": base64(Alice.IK.pub),    // X25519 public key
  "sig_pub": base64(Alice.SigIK.pub), // Ed25519 public key (may be same material, different encoding)
  "fingerprint": hex(SHA-256(Alice.IK.pub)[0:10])
}
```

Bob scans the QR code.

**Key Agreement (X3DH-Lite):**

Because there is no prekey server, we use a simplified X3DH in which Bob is online. Bob generates an ephemeral key pair `EK_B`, computes:

```
DH1 = X25519(EK_B.priv, Alice.IK.pub)   // Bob's ephemeral × Alice's identity
DH2 = X25519(Bob.IK.priv, Alice.IK.pub) // Bob's identity × Alice's identity
SK  = HKDF-SHA-256(IKM = DH1 || DH2,
                    salt = 0x00...00,
                    info = "Where-v2-KeyExchange")
```

Bob then encrypts `SK` with `Alice.IK.pub` (using ECIES / `crypto_box` semantics) and transmits:
```json
{
  "type": "KeyExchangeInit",
  "from_uuid": "bob-uuid",
  "ik_pub": base64(Bob.IK.pub),
  "ek_pub": base64(EK_B.pub),
  "sig": base64(Ed25519Sign(Bob.SigIK.priv, ik_pub || ek_pub))
}
```

This message is sent over the WebSocket server. The server sees only Bob's and Alice's UUIDs and the encrypted blob.

Alice receives the `KeyExchangeInit`, verifies Bob's signature over `(ik_pub || ek_pub)`, recomputes the same DH operations to derive `SK`, and the session is bootstrapped.

**What the server learns:** Bob's UUID, Alice's UUID, two X25519 public keys, an Ed25519 signature, and the ciphertext of `SK`. It learns that these two users initiated a key exchange. It does not learn `SK`.

### 4.3 Option B: Out-of-Band Copy-Paste (Existing UUID Flow Extension)

Users share a base64-encoded blob through any out-of-band channel (iMessage, Signal, in-person). The blob is:
```
where://add?uuid=<uuid>&ik=<base64_ik_pub>&fp=<fingerprint>
```

This is less secure than Option A because the transmission channel (iMessage, etc.) is not guaranteed to be authenticated. However, it matches the existing UX and is appropriate when in-person exchange is not practical.

The key agreement proceeds identically to Option A, with the difference that the QR code scanning step is replaced by a deep link tap.

**Risk:** If the out-of-band channel is compromised (e.g., Eve intercepts the iMessage before Bob sees it), Eve can substitute her own public key. Bob will then share his location with Eve, not Alice. This is the classic TOFU MITM. Encourage users who care about strong security to verify fingerprints in person.

### 4.4 What the Server Learns from Key Exchange

- Two UUIDs were involved in a key exchange at a given timestamp
- Both public keys (they are intentionally public material)
- Nothing about the resulting shared secret `SK`

The server cannot derive `SK` without one of the parties' private keys. This satisfies the requirement that the server be unable to decrypt location payloads.

---

## 5. Ratchet Design for Streaming Location Data

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
  "type": "EpochRotation",
  "new_ek_pub": base64(Alice.NewEK.pub),
  "epoch": 42,
  "sig": base64(Sign(Alice.SigIK.priv, new_ek_pub || epoch))
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
  "type": "RatchetAck",
  "to_uuid": "alice-uuid",
  "new_ek_pub": base64(Bob.NewEK.pub),
  "epoch_seen": 41,
  "sig": base64(Sign(Bob.SigIK.priv, new_ek_pub || epoch_seen))
}
```

Alice, upon receiving Bob's `RatchetAck`:
1. Computes `dh_out = X25519(Alice.CurrentEK.priv, Bob.NewEK.pub)`.
2. Derives `(new_root_key, new_send_CK) = HKDF(root_key, dh_out, "Where-v2-RatchetStep")`.
3. Generates a fresh `Alice.NewEK` for the next step.
4. Sends the next location update with her new `EK.pub` included.

This restores the full Double Ratchet security guarantee: both parties contribute fresh DH material, so compromise of either party's ephemeral keys is healed within `R` minutes.

**Tradeoffs:**
- Adds 1 uplink message per 10 minutes from Bob. At `R = 10 min`, this is ~144 messages/day per friendship pair — trivial overhead.
- Bob must remain connected (or buffer the `RatchetAck` for delivery when reconnected).
- If Bob is offline for an extended period (days), Alice cannot advance the DH ratchet. A time-based fallback (Strategy 1) should fire if no `RatchetAck` is received within `2R` minutes — sacrificing PCS but maintaining forward secrecy.

### 5.4 Forward Secrecy Granularity vs. Overhead Analysis

| Strategy | FS Granularity | PCS | Extra Messages/day (10 friends) | Notes |
|---|---|---|---|---|
| Per-message symmetric only | Per message | None | 0 | No DH ratchet; chain key compromise = full future exposure |
| Time-based (T=5 min) | Per message | Per epoch (one-sided) | ~288 EpochRotation messages | Bob's IK compromise breaks PCS |
| Message-count (K=20) | Per message | Per 20 messages | ~288 EpochRotation messages | Same weakness |
| Hybrid RatchetAck (R=10 min) | Per message | Per 10 minutes (bilateral) | ~288 location + 144 RatchetAck | Full Double Ratchet; recommended |

### 5.5 Message Key Deletion Policy

Forward secrecy is only as strong as the message key deletion discipline:

- Message keys `MK_n` MUST be deleted from memory immediately after encrypting/decrypting the corresponding frame.
- Chain keys `CK` MUST be deleted from memory after deriving the next step.
- Root keys MUST be overwritten immediately after deriving new chain keys.
- Ephemeral DH private keys MUST be deleted after computing the shared secret.
- On Android, keys live in a `SecureRandom`-backed in-memory structure; `Arrays.fill(key, 0)` before GC. On iOS, `Data` is zeroed explicitly before dealloc.
- No message keys or chain keys are persisted to disk. If the app is killed and restarted, the session restarts from the last stored epoch state (root key + current EK). The root key is stored in the platform keychain (Android Keystore / iOS Secure Enclave-backed Keychain).

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

### 6.3 Handling Friend Add/Remove

**Adding a friend:** When Alice adds Bob as a friend, she runs the key exchange (§4) and initializes a new ratchet session seeded from the resulting `SK`. There is no effect on other friends' sessions.

**Removing a friend:** Alice removes the ratchet session state for Bob. Since she was encrypting separately for each friend, Bob's removal immediately stops the flow of ciphertext addressed to him. He cannot recover future location updates.

**Important:** Removing Bob does not provide cryptographic protection against Bob's having cached past location updates. If Bob logged Alice's decrypted locations before being removed, there is no technical mechanism to prevent that. This is a property of the application layer (consent model), not the cryptographic protocol.

---

## 7. Server's Role and Obliviousness

### 7.1 What the Server Must Know to Route Messages

At minimum, the server needs:
- The **destination UUID** for each frame, to route it to the correct WebSocket connection
- Whether the destination UUID is currently connected

The server does NOT need to know:
- The sender's UUID (beyond what is implicit in the WebSocket connection)
- The plaintext location
- The epoch or ratchet state

### 7.2 Routing Model

Each encrypted frame sent by Alice contains:
```
{ "to": "bob-uuid", "payload": base64(ciphertext) }
```

The server routes `payload` to the connection registered under `"bob-uuid"`. It does not open, inspect, or store the payload. In the ideal case, the server is a dumb forwarder that knows only `(from_connection, to_uuid)`.

### 7.3 Metadata Exposure

Even with fully opaque payloads, the server can observe:

- **Social graph:** Alice's WebSocket connection sends messages addressed to B, C, and D — revealing that A, B, C, D are mutually connected. This is structural metadata, not content, but it is sensitive.
- **Movement inference from update frequency:** If Alice sends an encrypted update every 30 seconds while walking and slows to every 5 minutes when stationary (due to Android's `PRIORITY_BALANCED_POWER_ACCURACY` coalescing updates), the server can infer Alice's movement state from message timing alone.
- **Presence:** The server knows when any given UUID is online.

### 7.4 Partial Mitigations

These are incremental improvements, not panaceas:

- **Obfuscated recipient IDs:** Instead of sending `"to": "bob-uuid"` in plaintext, send `"to": HMAC-SHA-256(routing_secret, "bob-uuid")[0:16]` where `routing_secret` is derived from the shared session key. The server still needs to route, so it must either know the mapping or receive a routable identifier. One approach: Alice registers an ephemeral "inbox token" for Bob during key exchange; only Alice knows this maps to Bob. The server indexes by inbox token, not UUID.
- **Cover traffic:** Clients send a fixed-rate heartbeat (encrypted random bytes addressed to `/dev/null`) to mask the location-update cadence. This defeats timing-based inference but increases battery and bandwidth usage.
- **Padding:** All location payloads are padded to a fixed length (e.g., 256 bytes) before encryption to mask payload size variations.

Cover traffic and padding are recommended for a threat model that includes a curious server operator. They are optional for v2 but should be on the roadmap.

---

## 8. Concrete Protocol Recommendation

### 8.1 Key Types and Operations

| Purpose | Algorithm | Key size | Notes |
|---|---|---|---|
| Long-term identity (signing) | Ed25519 | 256-bit | One per device, stored in Keychain/Keystore |
| Long-term identity (DH) | X25519 | 256-bit | Derived from Ed25519 seed via `ed25519_sk_to_curve25519` |
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
  recv_chain_key:  [32]byte   // CK for B→Alice direction (for RatchetAck decryption)
  send_seq:        uint64     // monotonically increasing per-message counter
  recv_seq:        uint64     // highest received seq from B
  epoch:           uint32     // increments on each DH ratchet step
  my_ek_priv:      [32]byte   // Alice's current ephemeral X25519 private key
  my_ek_pub:       [32]byte   // Alice's current ephemeral X25519 public key
  their_ek_pub:    [32]byte   // Bob's last known ephemeral public key
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

**KDF_CK (symmetric ratchet step):**
```
new_chain_key = HMAC-SHA-256(key=chain_key, data=0x01)
message_key   = HMAC-SHA-256(key=chain_key, data=0x02)
```
This is identical to the Signal spec's KDF_CK construction.

**Message encryption:**
```
nonce = random 12 bytes (96-bit, generated per message — NOT counter-based,
        to avoid nonce reuse after ratchet state restoration from keychain)
aad   = encode_aad(sender_uuid, recipient_uuid, epoch, seq)
(ciphertext, tag) = AES-256-GCM(key=message_key, nonce=nonce,
                                  plaintext=loc_json, aad=aad)
```

Note on nonce: Because `message_key` is unique per message (derived from the ratchet), nonce reuse across messages is not a concern — each message has a distinct key. A random 96-bit nonce provides a comfortable margin. However, if state recovery from persistent keychain is ever implemented (e.g., restoring from a root key), deterministic nonce assignment from `seq` is safer. For v2, random nonces are used.

### 8.4 Ratchet Advancement Policy

Per §5.3 Strategy 3 (Hybrid):

1. Alice advances the **symmetric ratchet** on every location update (every ~30 seconds).
2. Alice advances the **DH ratchet** when she receives a `RatchetAck` from Bob containing a new `ek_pub`.
3. If Alice has not received a `RatchetAck` from Bob within 20 minutes (i.e., `2R` for `R = 10 min`), she falls back to a **time-based DH ratchet step** using Bob's last known `their_ek_pub` (no new DH input from Bob, but the epoch key refreshes Alice's side). This degrades PCS but maintains FS.
4. `EpochRotation` messages are sent at the start of each DH ratchet step to notify Bob of Alice's new `ek_pub`.

---

## 9. Wire Format

All WebSocket messages are JSON-encoded binary frames. Message types are discriminated by a `"type"` field, consistent with the existing `classDiscriminator = "type"` pattern in the Kotlin codebase.

### 9.1 Location Update (Alice → Server → Bob)

```json
{
  "type": "EncryptedLocation",
  "from": "alice-uuid",
  "to":   "bob-uuid",
  "epoch": 42,
  "seq":   1337,
  "ek_pub": "<base64 of Alice's current ephemeral X25519 public key>",
  "nonce":  "<base64, 12 bytes>",
  "ct":     "<base64, AES-256-GCM ciphertext + 16-byte tag>"
}
```

**AAD (authenticated, not encrypted):**
```
aad = alice-uuid || bob-uuid || epoch (4 bytes, big-endian) || seq (8 bytes, big-endian)
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

Total wire size per update per friend: ~350–400 bytes (JSON overhead + base64 expansion + padding). With 20 friends, ~8 KB per 30-second update cycle — well within LTE budget.

### 9.2 Epoch Rotation (Alice → Server → Bob)

```json
{
  "type": "EpochRotation",
  "from":     "alice-uuid",
  "to":       "bob-uuid",
  "epoch":    43,
  "new_ek_pub": "<base64>",
  "sig":      "<base64, Ed25519 signature over (from || to || epoch || new_ek_pub)>"
}
```

Bob verifies `sig` against Alice's stored `sig_pub` before updating `their_ek_pub` and performing the KDF_RK step.

### 9.3 Ratchet Acknowledgment (Bob → Server → Alice)

```json
{
  "type": "RatchetAck",
  "from":        "bob-uuid",
  "to":          "alice-uuid",
  "epoch_seen":  42,
  "new_ek_pub":  "<base64>",
  "sig":         "<base64, Ed25519 signature over (from || to || epoch_seen || new_ek_pub)>"
}
```

Alice verifies `sig` against Bob's stored `sig_pub`, then performs a KDF_RK step.

### 9.4 Key Exchange Init (Bob → Server → Alice)

```json
{
  "type":    "KeyExchangeInit",
  "from":    "bob-uuid",
  "to":      "alice-uuid",
  "ik_pub":  "<base64, Bob's X25519 identity public key>",
  "ek_pub":  "<base64, Bob's X25519 ephemeral public key>",
  "sig_pub": "<base64, Bob's Ed25519 signing public key>",
  "sig":     "<base64, Ed25519 signature over (ik_pub || ek_pub || sig_pub)>"
}
```

---

## 10. Server Changes

### 10.1 What Changes

| Component | v1 Behavior | v2 Change |
|---|---|---|
| `/ws?userId=<uuid>` endpoint | Stores and broadcasts plaintext `UserLocation` | Routes opaque `EncryptedLocation` frames to destination UUID; no parsing of payload |
| In-memory location store (`ConcurrentHashMap<UUID, UserLocation>`) | Stores all locations in plaintext | **Removed entirely.** Server no longer stores location state. It is a stateless message router. |
| `/locations` REST endpoint | Returns all user locations | **Removed** (exposes plaintext state; not needed in v2) |
| `/health` endpoint | Returns `"ok"` | Unchanged |
| WebSocket fan-out logic | Broadcasts full location map to all clients | Routes each frame to its specific `to` UUID; O(1) lookup instead of O(N) broadcast |

### 10.2 Routing Table

The server maintains only:
```kotlin
val connections: ConcurrentHashMap<UUID, DefaultWebSocketServerSession>
```

On receipt of any `EncryptedLocation`, `EpochRotation`, `RatchetAck`, or `KeyExchangeInit`:
1. Extract `"to"` UUID from the JSON envelope (no decryption required).
2. Look up the destination in `connections`.
3. If found: forward the raw frame verbatim.
4. If not found: buffer for delivery when the destination connects (optional; otherwise drop and let the client retry on reconnect).

The server never reads any field other than `"type"` and `"to"`.

### 10.3 What Stays the Same

- Ktor WebSocket server infrastructure (`ktor-server-websockets`)
- UUID-based connection registration
- TLS termination (HTTPS/WSS)
- Reconnection handling
- Horizontal scalability model (if adding pub/sub backing like Redis, the routing key is still UUID)

### 10.4 Server Cannot Decrypt

With this design:
- The server has no knowledge of any session key, chain key, message key, or DH private key.
- All ciphertext is created client-side and arrives at the server already encrypted.
- The server's only function is routing.
- A full server compromise exposes: social graph (who is connected to whom), connection timestamps, and ciphertext blobs that cannot be decrypted without client-side key material.

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
- v1 clients gracefully degrade (they connect but receive no recognizable location updates).

**Phase 3 — v1 EOL:**
- Server drops connections from clients that send `LocationBroadcast` frames.

### 11.3 Key Exchange Bootstrap During Migration

When a v2 client connects and discovers that a friend has not yet performed key exchange, it:
1. Sends a `KeyExchangeInit` message to the friend's UUID.
2. Falls back to showing "pending encryption setup" in the UI until the friend upgrades and responds.
3. Does NOT fall back to sending plaintext location to unexchanged friends.

This ensures that v2 clients never silently downgrade to plaintext.

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

1. **Post-quantum migration.** X25519 is broken by Shor's algorithm on a cryptographically relevant quantum computer. Signal has already deployed their Sparse Post-Quantum Ratchet (SPQR) using CRYSTALS-Kyber for quantum-resistant ratchet steps. Where should plan a similar upgrade path: introduce a `pq_ek_pub` field in `EpochRotation` carrying a Kyber encapsulation, and combine the classical X25519 and Kyber shared secrets via `HKDF(X25519_out || Kyber_out)`. This is additive and backward-compatible.

2. **Multi-device support.** If a user runs Where on both a phone and tablet, both devices need access to the same identity key, or separate identity keys need to be linked. This is a hard problem (Signal solves it with a sealed-sender device-linking protocol). Deferred to post-v2.

3. **Server-side message buffering.** If Bob is offline when Alice sends a location update, the message is dropped. Buffering encrypted messages server-side for offline delivery is operationally straightforward (the server never needs to decrypt them) but raises storage and key-freshness concerns (a buffered location from 12 hours ago may not be useful). A short TTL (e.g., 5 minutes) on buffered messages is recommended.

4. **Location data minimization.** Even with E2EE, clients receive precise GPS coordinates. A future feature could allow users to configure "fuzzing" — encrypting a location degraded to, e.g., neighborhood precision — so that even a legitimate friend who is untrusted at fine granularity cannot determine exact position.

5. **Key transparency.** For stronger protection against MITM at key exchange time, a lightweight key transparency log (similar to [CONIKS](https://coniks.cs.princeton.edu/) or Apple's iCloud Key Transparency) could allow users to verify that the key stored for their UUID has not been silently changed by a server-side adversary. This is ambitious for v2 but would meaningfully close the gap on the TOFU weaknesses described in §3.1.

6. **Ratchet state persistence and recovery.** Currently, root keys are stored in the platform keychain; if a user's keychain is wiped (e.g., factory reset), session state is lost and key exchange must be re-run. This is acceptable but creates friction. A sealed backup of session state (encrypted to the user's identity key and stored encrypted in the cloud) could enable recovery without trusting the server.
