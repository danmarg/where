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

- [x] Won't fix at the protocol layer. A withholding server is indistinguishable
      from the sender going offline or staying stationary. The authenticated `ts`
      ensures the receiver always displays an honest last-seen time — no fabricated
      location or timestamp is possible. The stationary-flag edge case ("here since X"
      displayed indefinitely) is WAI: it is indistinguishable from genuine stationarity
      and the timestamp remains authentic. Added a note to §2.3.

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

- [x] §5.3 step 2: fixed — pn is in the encrypted header, not body payload.
- [x] §8.3.1(5): clarified — counter resets to 0, first message is seq=1, initial recv_msg_num=0.
- [x] §4.2: fixed EK_A.pub → EK_B.pub in observer sentence.
- [x] §9: documented all three PROTOCOL_VERSION encodings (JSON int, 1-byte header, 4-byte AAD) with rationale.
- [x] §3.4: defined formatSafetyNumber algorithm inline (5-byte chunks, mod 100,000, zero-pad); §4.4 back-references §3.4.
- [x] §4.4: specified full header-key schedule across bootstrap and DH ratchet steps.
- [x] §9.1.2: added max plaintext (511 bytes) and de-padding rule.
- [x] §7.4.2: "GCM overhead" → "Poly1305 tag (16 bytes)"; CLAUDE.md/AGENTS.md AES-256-GCM → ChaCha20-Poly1305.
- [x] Cross-references: ToC updated to 13 sections; §2.3 quantum → §13; §5.7.2 §7.2 → §10.2; §12.5 → §13.2; §3.4 formatSafetyNumber self-contained.
