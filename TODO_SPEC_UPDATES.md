# TODO: Spec & Implementation Updates from E2EE Design Doc Review

Source: review of `docs/e2ee-location-sync.md` (2026-06-12).

## 1. Remove plaintext `token` field from `KeyExchangeInit` (breaking — two-stage rollout)

The plaintext `T_AB_0` in `KeyExchangeInit` lets the server link the discovery
mailbox to the session's first-epoch routing token, defeating the stated purpose
of `discovery_secret` (§4.2). The field is redundant: Alice derives `T_AB_0`
independently and must use her derived value regardless.

- [x] **Stage 1 (client tolerance):** In the `aliceProcessInit` path, parse
      `token` as optional and ignore it entirely (do not conditionally verify).
      Only the inviter/receiver side checks this field; sender untouched.
- [x] **Stage 1 (spec):** Removed `token` from §4.4 (step 7) and §9.3 entirely
      (single implementation — no deprecation period needed in the spec).
- [ ] **Stage 2:** Stop sending the field in `KeyExchangeInit` (`bobProcessQr`
      and `processScannedQr`).
- No protocol version bump needed: removing an ignored optional field is not a
  wire break once stage 1 is deployed. Failure mode during version skew is
  benign — pairing is a live, interactive event; a failed pairing is visible
  and retriable, with no persisted-session corruption.

## 2. Correct the "private keys deleted immediately" claims (doc only)

§3.1, §4.1, §4.4 claim `EK_B.priv` is deleted immediately after `SK`
derivation, but §5.5 (correctly) copies it into `localDhPriv`, where it
persists until Bob's first DH ratchet completes. Required — Bob could not
otherwise process Alice's eager-ratchet message.

- [x] Fix §3.1, §4.1, §4.4 to state that Bob's bootstrap private key survives
      as `localDhPriv` until his first ratchet.
- [x] Add a note on the security implication: during that window, a device or
      backup compromise plus the public QR payload allows reconstruction of `SK`.

## 3. Resolve the `prev_recv_token` contradiction (doc only — pick one)

§5.4.2 says the receiver polls exactly one token (no `prev_recv_token`
polling); §9.2 says the receiver MUST also poll `prev_recv_token` during epoch
transition; §8.2 `SessionState` carries `prev_recv_token`.

- [x] Determine which behavior the implementation actually has.
      (Single-token polling per §5.4.2; `prevRecvToken` does not exist in SessionState
      or polling logic; §9.2 and §8.2 were wrong.)
- [x] Make §5.4.2, §8.2, and §9.2 agree. Note metadata impact if two-token
      polling is the real behavior (doubles per-friend poll fingerprint).
      (Removed `prev_recv_token` from §8.2 SessionState; rewrote §9.2 to match §5.4.2;
      noted the metadata benefit of single-token design.)

## 4. Reconcile the two KDF_CK definitions (doc only)

§8.3: `MK = HMAC(CK, 0x01)`, `CK' = HMAC(CK, 0x02)`, nonce via separate HKDF.
§11 table: single 76-byte HKDF expand for message key + nonce. These produce
different bytes.

- [x] Check the implementation, fix whichever section is wrong.
      (§8.3 is correct: HMAC for MK/CK', separate HKDF for nonce. Fixed §11 table
      and the incorrect file-header comment in Ratchet.kt.)

## 5. Specify discovery-mailbox multiple-responder behavior (doc, maybe impl)

First-responder-wins is currently implicit; a hijacker racing Bob causes
legitimate Bob to fail *silently* (he believes pairing succeeded). Note also
that the server controls GET ordering ("up to 50 from the front of the
queue"), i.e., a malicious server picks which `KeyExchangeInit` Alice sees
first.

- [x] Specify behavior when multiple `KeyExchangeInit` messages are present in
      the discovery mailbox: process all of them (process-all, not first-responder-
      wins), surface a count to Alice, prompt Safety Number verification per session.
      This eliminates silent displacement and aligns with issue #233.
- [x] Specified behavior differs from implementation (current: first-responder-wins).
      Impl work tracked in https://github.com/danmarg/where/issues/233.

## 6. Reconcile MAX_GAP (10,000) vs skipped-key cache (1,000) (doc, maybe impl)

A near-MAX_GAP jump derives up to 10,000 skipped keys into a 1,000-entry
cache; 90% are evicted immediately and those messages are silently lost.

- [x] Removed MAX_GAP constant; same-epoch gap limit is now MAX_SKIPPED_KEYS (1,000)
      at both the coarse check and the cache pre-check. Updated §8.3.1 and Session.kt.
- [x] Noted DoS bound (≤1,000 HMACs/frame, not 10,000) and cross-epoch silent-loss
      behavior in §8.3.1.

## 7. Document residual FS cost of failed-body key caching (doc only)

§8.3.1(4) deviation caches `MK_n` for body-failed messages; a malicious server
can trigger this at will, keeping keys alive until age-based eviction. §5.5
rule 2 doesn't cover the never-used case.

- [x] Removed the body-fail seq-key caching entirely (no robustness benefit;
      server can drop instead). §8.3.1(4) now requires advancing state on body-fail
      but explicitly forbids caching the key. Test updated accordingly.

## 8. Specify handling of header-undecryptable frames (doc, maybe impl)

Frames failing `tryDecryptHeader` are "dropped" but never DELETEd; they are
re-fetched every poll for up to 7 days and can starve the 50-message GET window.
Also tension with §5.4.4 "duplicates MUST be ACKable": a duplicate transition
message arriving after ratchet is header-undecryptable.

- [x] Specified in §5.4.4: header-undecryptable frames are not immediately deleted
      (can't distinguish garbage from a future-epoch message); starvation is bounded
      by force-ACK after MAX_SILENT_DROP_RETRIES consecutive failed polls (~2.5 min).
      Addressed the post-ratchet duplicate tension.

## 9. Soften §12.3 cross-epoch correlation claim (doc only)

"Impossible for the server to correlate messages across epochs" is overstated:
the transition message posts to the old token, and the receiver's IP switches
polling targets in adjacent cycles. Align with §2.3/§7.3 admissions.

- [x] Reworded §12.3: token rotation prevents content-layer correlation only;
      same client IP polling T_old then T_new remains a metadata linkage (§2.3/§7.3).
- [ ] Optional: decorrelate the receiver's polling switch from T_old to T_new
      in time (jitter, or switch on the next regular cycle) to weaken the
      deterministic linkage.

## 11. Freshness lower-bound: stale-pinning by a withholding server (NEW — HIGH)

§2.3 treats message dropping as DoS, but for a location app withholding is an
*integrity* attack: GET is non-destructive and the server controls delivery.
Once Bob has processed up to seq=N and DELETEd, a malicious server simply never
serves seq>N. Bob's UI shows an authentic-but-stale location with a
trustworthy-looking authenticated `ts` — a confident false belief ("she's still
here"), not a visible gap. The only staleness signal is `isStale` at 7 days
(§13.4), useless against minutes-to-hours pinning. `seq` gives ordering but no
wall-clock anchor.

- [ ] Receiver-side (no wire change): the protocol already mandates regular
      Keepalives (§5.7.2), so the client knows the expected cadence. Enforce a
      max-age on the last authenticated `ts` and surface "no fresh update in
      >X minutes" prominently; treat a stalled seq against expected cadence as
      suspicious, not benign.
- [ ] Optional wire-level strengthening (plaintext-schema only, rides §5.7.3
      forward-compat): sender includes an `expected_next_interval` /
      heartbeat-commitment field so the receiver's max-age is sender-declared
      rather than client-guessed.
- [ ] Document the attack in §2.2/§2.3 (currently mis-filed under DoS).

## 12. Safety number does not authenticate the rest of the invite payload (NEW)

The safety number covers only the two `ek_pub`s. `suggested_name` (and the
redundant `fingerprint`) in the QR/link are not bound to it: an attacker who
tampers the invite's name but leaves `ek_pub` intact gets no keys, yet
pre-seeds Bob's naming dialog with an attacker-chosen label while safety-number
verification still passes. Severity is capped by §3.2 (name is only a pre-fill
the user confirms), but the doc implies more coverage than exists.

- [ ] State explicitly in §3.4 that safety-number verification authenticates
      keys only, not the displayed/suggested name.
- [ ] Consider folding a hash of the full invite payload into the
      safety-number/confirmation transcript (wire-compatible for the QR;
      check `key_confirmation` implications).
- [ ] Drop the redundant `fingerprint` field from the invite payload, or
      document it as a non-security convenience (it is derivable from `ek_pub`
      and provides no integrity an adversary can't recompute).

## 10. Minor spec corrections (doc only)

- [ ] §5.3 step 2: `prev_chain_len` is in the encrypted *header* (§9.1.1), not
      the body payload; fix wording (header must be read before body keys can
      be derived).
- [ ] §8.3.1(5) vs §5.4.1: msg_num "resets to 0" vs first message is `seq == 1`.
      Pick one convention; state initial `recv_msg_num`.
- [ ] §4.2: observer sees `EK_B.pub` (not `EK_A.pub`) in `KeyExchangeInit`.
- [ ] §9.1.1 vs §9.1.2 vs §9 top: PROTOCOL_VERSION is 1 byte in header, 4 bytes
      in body AAD, plus a JSON `"v": 1` — three encodings of one logical field.
      Nail down a single canonical byte layout.
- [ ] §3.4 / §4.4: `formatSafetyNumber` is unspecified. 60 bytes rendered as
      "12 groups of 5 decimal digits" doesn't define the reduction (e.g.,
      5-byte chunks mod 10^5, a la Signal) — the encoding determines the real
      comparison strength. Define it.
- [ ] §4.4 / §8.3: specify the full header-key schedule, not just the initial
      assignment. Assign direction for the initial shared `next_header_key`
      (send-next vs recv-next, given Alice's eager ratchet), AND state how the
      single `new_header_key` emitted by each KDF_RK step rolls into the
      current/next header-key pair per epoch. As written, a reasonable
      implementer can produce a schedule that cannot decrypt out-of-order
      new-epoch headers, breaking the §5.3 reliability property.
- [ ] §9.1.2: state max plaintext is 511 bytes (≥1 pad byte always required)
      and the de-padding rule (scan back to last 0x80; reject if absent).
- [ ] §7.4.2: "GCM overhead" → ChaCha20-Poly1305. Also fix stale "AES-256-GCM"
      claim in CLAUDE.md's E2EE summary.
- [ ] Fix stale cross-references: ToC lists 12 sections (doc has 13); §2.3 /
      §12.5 quantum pointers; §5.7.2 cites §7.2 for the 7-day window (it's
      §10.2/§13.4); §3.4 cites "§8.3 format" for safety-number rendering.
