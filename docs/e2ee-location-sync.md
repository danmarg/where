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
- **No defense against fake accounts.** This design does not prevent a motivated attacker from creating multiple accounts with different UUIDs, each impersonating a different friend. Account identity verification (e.g., phone number or email binding) is a higher-layer concern outside the scope of this protocol. Applications using this protocol should implement such checks to reduce per-app TOFU risks.

### 3.2 Recommended Pragmatic Middle Ground

Rather than requiring a full PKI or central identity server, adopt a lightweight **local key registry** with safety-net UX mirroring what Signal does:

1. **Each device generates a single long-term key pair at first launch.** The identity public key (Ed25519) is concatenated with the UUID and a short fingerprint (e.g., first 20 bytes of `SHA-256(publicKey)`, displayed as 5 groups of 4 hex characters). The full "friend invite" payload is `base64(uuid || publicKey || fingerprint)`.

2. **On first import of a friend's key, the app stores and pins it.** All subsequent location frames signed by a different key for the same UUID trigger a prominent, non-dismissable "Safety Number Changed" alert modeled on Signal's safety numbers feature.

3. **Safety number verification.** Two users can optionally compare a human-readable fingerprint (the 40-hex-character shortform of `SHA-256(alicePublicKey || bobPublicKey)`, sorted by UUID). If they match on both screens, the session is verified out-of-band. This is identical in spirit to Signal's safety numbers and PGP fingerprint verification.

4. **No re-use of identity keys across re-installs.** App uninstall/reinstall generates a new key pair and UUID, forcing all friends to re-do the exchange. This is a deliberate forcing function: it prevents silent key reuse and ties identity churn to the natural friction of reinstallation.

5. **Key rotation is manual and explicit.** There is no automated rotation of the long-term identity key. If a user suspects compromise, they rotate by reinstalling. This trades security-automation for implementation simplicity, appropriate for v2.

**Implementation note — key derivation domain separation:** The identity key pair uses a single seed: the Ed25519 signing key derives the X25519 DH key via libsodium's `crypto_sign_ed25519_sk_to_curve25519`. This function implements RFC 8032 and correctly domain-separates the signing and DH contexts. Implementations MUST use this specific conversion function — not a raw key copy — to avoid key-confusion attacks between signing and key-agreement operations.

**Lost-device recovery flow:** When a user loses their device, the recovery path is: (1) reinstall the app to generate a new identity key pair and UUID; (2) re-share the new invite code with each friend out-of-band; (3) each friend re-imports the code, triggering a "Safety Number Changed" alert they must explicitly acknowledge. There is no automated revocation in v2. User-facing guidance must describe this flow explicitly so users know what to do after a lost or stolen device. In-app revocation without reinstalling is deferred to future work.

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
  "type":    "KeyExchangeInit",
  "from":    "bob-uuid",
  "to":      "alice-uuid",
  "ik_pub":  "<base64, Bob's X25519 identity public key>",
  "ek_pub":  "<base64, Bob's X25519 ephemeral public key>",
  "sig_pub": "<base64, Bob's Ed25519 signing public key>",
  "sig":     "<base64, Ed25519 signature over (ik_pub || ek_pub || sig_pub)>"
}
```

This message is sent over the WebSocket server. The server sees only Bob's and Alice's UUIDs and the public key material.

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

Future versions should prepend a protocol version/context string to `signed_data` for cross-protocol domain separation.

Alice then recomputes the same DH operations to derive `SK`, and the session is bootstrapped.

**What the server learns:** Bob's UUID, Alice's UUID, two X25519 public keys, an Ed25519 signing key, a signature, and the ciphertext of `SK`. It learns that these two users initiated a key exchange. It does not learn `SK`.

**Forward secrecy trade-off:** This protocol omits Signal's signed prekey and one-time prekey (present in full X3DH). Because both DH inputs in `DH2 = X25519(Bob.IK.priv, Alice.IK.pub)` are long-term keys, a future compromise of Bob's `IK.priv` allows an attacker to retroactively compute `SK` for every session Bob has established. This is a deliberate simplification: the exchange is synchronous (Bob is online), eliminating the need for a prekey server. For threat models requiring stronger forward secrecy of the initial handshake, full X3DH with a prekey server is deferred to future work (§13).

### 4.3 Option B: Out-of-Band Copy-Paste (Existing UUID Flow Extension)

Users share a base64-encoded blob through any out-of-band channel (iMessage, Signal, in-person). The blob is:
```
where://add?uuid=<uuid>&ik=<base64_ik_pub>&fp=<fingerprint>
```

This is less secure than Option A because the transmission channel (iMessage, etc.) is not guaranteed to be authenticated. However, it matches the existing UX and is appropriate when in-person exchange is not practical.

The key agreement proceeds identically to Option A, with the difference that the QR code scanning step is replaced by a deep link tap.

**Risk:** If the out-of-band channel is compromised (e.g., Eve intercepts the iMessage before Bob sees it), Eve can substitute her own public key. Bob will then share his location with Eve, not Alice. This is the classic TOFU MITM — and for a location app the impact is high: Eve accumulates a continuous time-series of movement history.

Option B is appropriate when in-person exchange is impractical. However, the app MUST display a prominent, hard-to-dismiss prompt encouraging fingerprint verification after the friend is added via this path. This is not optional guidance: users who skip it unknowingly accept the impersonation risk. The prompt should show the safety number and explain what to do with it.

### 4.4 What the Server Learns from Key Exchange

- Two UUIDs were involved in a key exchange at a given timestamp
- Both public keys (they are intentionally public material)
- Nothing about the resulting shared secret `SK`

The server cannot derive `SK` without one of the parties' private keys. This satisfies the requirement that the server be unable to decrypt location payloads.

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
4. Sends the next location update with her new `EK.pub` included.

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

These are incremental improvements, not panaceas. They are listed in decreasing order of implementation priority:

- **Payload padding (mandatory in v2):** All location payloads MUST be padded to a fixed length before encryption to mask payload size variations. Pad the plaintext JSON to a fixed block size (e.g., 256 bytes) with zero bytes; the first two bytes of the decrypted plaintext encode the true payload length as a big-endian uint16. This is cheap and eliminates size-based traffic analysis at negligible cost (~50 bytes of overhead per message).

- **Obfuscated recipient IDs / inbox tokens (recommended for v2):** Instead of routing to `"to": "bob-uuid"` in plaintext, clients SHOULD establish a per-friend ephemeral **inbox token** during key exchange. Alice includes a randomly generated 128-bit token in her `KeyExchangeInit`; Bob registers it as his inbox address for Alice's messages. The server indexes WebSocket connections by inbox token. Only Alice knows which token maps to Bob's UUID. Tokens rotate each epoch for forward unlinkability. **Without this mitigation, the server can directly reconstruct the full social graph from routing headers.** Inbox tokens are the recommended baseline for v2; the server changes required are minimal (swap UUID lookup for token lookup in the routing table).

- **Cover traffic (optional, deferred):** Clients may send a fixed-rate stream of encrypted random bytes to a server sink to mask update cadence. At a 10-second cover interval (sufficient to hide 30-second real updates), overhead is roughly 3× baseline: ~8 KB/30 s becomes ~24 KB/30 s for a 20-friend session. Battery impact on mobile is non-trivial. Cover traffic is deferred to a future release; if implemented, it must be opt-in with explicit user warnings about data and battery cost.

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
new_chain_key  = HMAC-SHA-256(key=chain_key, data=0x01)
message_key    = HMAC-SHA-256(key=chain_key, data=0x02)
message_nonce  = HMAC-SHA-256(key=chain_key, data=0x03)[0:12]  // optional; see nonce note below
```
The first two outputs are identical to the Signal spec's KDF_CK construction. The optional third output provides a deterministic nonce for implementations that choose to forego random nonce generation (see §8.3 note on nonces).

**Message encryption:**
```
nonce = random 12 bytes (96-bit, generated per message)
aad   = "Where-v2-Location" || encode_aad(sender_uuid, recipient_uuid, epoch, seq)
(ciphertext, tag) = AES-256-GCM(key=message_key, nonce=nonce,
                                  plaintext=loc_json_padded, aad=aad)
```

The `"Where-v2-Location"` prefix provides domain separation from other protocol contexts (e.g., `EpochRotation`, future protocol versions), preventing cross-context AAD collisions or forgery attempts.

**Note on nonces:** Because `message_key` is unique per message (derived from the ratchet chain), nonce reuse across messages in the normal flow is not a concern — each message has a distinct key. A random 96-bit nonce provides comfortable margin. However, if keychain state is restored to an earlier epoch (e.g., backup restoration; see §5.5), the same root key may re-derive the same message key, and a random nonce could theoretically collide. This risk is mitigated by the mandatory keychain backup controls in §5.5 (device-only storage). As a hardening measure, implementations SHOULD derive nonces deterministically from the chain state:

```
// Third KDF_CK output alongside message_key:
message_nonce = HMAC-SHA-256(key=chain_key, data=0x03)[0:12]
```

This guarantees nonce uniqueness within a session even across state rollbacks, at negligible cost. v2 MAY use random nonces if the §5.5 backup controls are enforced; deterministic nonces are the safer long-term choice.

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
aad = "Where-v2-Location" (18 bytes, UTF-8)
    || alice-uuid (16 bytes, raw UUID bytes)
    || bob-uuid   (16 bytes, raw UUID bytes)
    || epoch      (4 bytes, big-endian uint32)
    || seq        (8 bytes, big-endian uint64)
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

**Delivery guarantees:** The server provides best-effort, at-most-once delivery. If the destination UUID is not connected at the time a frame arrives, the frame is dropped (or held in a short-lived buffer, per §13.3). There are no ACKs, retransmissions, or delivery receipts at the server layer. For location updates this is acceptable: stale coordinates are not useful, and the sending client will emit a fresh update on its next cycle. Clients MUST NOT assume delivery and MUST NOT rely on server buffering for reliable state synchronisation.

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

1. **Post-quantum migration.** X25519 is broken by Shor's algorithm on a cryptographically relevant quantum computer. Signal has already deployed their Sparse Post-Quantum Ratchet (SPQR) using CRYSTALS-Kyber for quantum-resistant ratchet steps. Where should plan a similar upgrade path: introduce a `pq_ek_pub` field in `EpochRotation` carrying a Kyber encapsulation, and combine the classical X25519 and Kyber shared secrets via `HKDF(X25519_out || Kyber_out)`. This is additive and backward-compatible.

2. **Multi-device support.** If a user runs Where on both a phone and tablet, both devices need access to the same identity key, or separate identity keys need to be linked. This is a hard problem (Signal solves it with a sealed-sender device-linking protocol). Deferred to post-v2.

3. **Server-side message buffering.** If Bob is offline when Alice sends a location update, the message is dropped. Buffering encrypted messages server-side for offline delivery is operationally straightforward (the server never needs to decrypt them) but raises storage and key-freshness concerns (a buffered location from 12 hours ago may not be useful). A short TTL (e.g., 5 minutes) on buffered messages is recommended.

4. **Location precision control.** *(Decision required — see note below.)* Even with E2EE, recipients receive full GPS precision. Allowing users to degrade precision before encryption (e.g., to neighbourhood granularity) would prevent even a legitimate friend from learning exact position. Degradation happens on the sender's device before encryption, so the ciphertext contains only the degraded coordinate. The reviewer recommends this be v2 scope, not future work, because it is a core privacy control. **Decision needed: is this in scope for v2?**

5. **Key transparency / signed directory.** *(Decision required — see note below.)* The TOFU weakness described in §3.1 is the highest-value attack surface for a location app. Two options:
   - **Option A — Lightweight signed directory:** The server signs `(UUID, IK.pub, timestamp)` tuples with a long-lived server signing key. Clients cache and verify these signatures; if the server provides a different `IK.pub` for the same UUID, the client detects the fork. Simpler than full KT; adds one server-side table and a signing step. Does not prevent the server from equivocating to different clients simultaneously, but makes equivocation auditable if clients compare notes.
   - **Option B — Full key transparency (CONIKS / Apple iCloud KT):** Verifiable append-only log of `(UUID, IK.pub)` bindings. Clients verify log proofs; server cannot silently substitute keys. More complex; likely v3 scope.
   - **Option C — Document as known limitation:** Keep current TOFU + safety numbers approach, document the impersonation risk prominently, and defer both options. This is the current v2 baseline.
   **Decision needed: Option A, B, or C?** Option A has modest implementation cost and meaningfully raises the bar for server-side impersonation.

6. **Ratchet state persistence and recovery.** Currently, root keys are stored in the platform keychain; if a user's keychain is wiped (e.g., factory reset), session state is lost and key exchange must be re-run. This is acceptable but creates friction. A sealed backup of session state (encrypted to the user's identity key and stored encrypted in the cloud) could enable recovery without trusting the server. Any such backup mechanism must be careful not to reintroduce the state-rollback risk described in §5.5.
