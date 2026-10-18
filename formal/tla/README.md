# WhereSync: a TLA+ model of delivery, epochs and acks

`WhereSync.tla` models two paired clients and the mailbox server at the level of spec §5.3–5.4 and
§8.3.1: sending, the transition frame on the old token (§5.4.1), polling one token (§5.4.2), batch
decryption with skipped keys and gap filling (§5.3, §8.3.1), the DH ratchet and token rotation,
send-side retirement of the transition frame (§5.4.3), acks and force-acks (§5.4.4), and crashes
between saving state and acking (§5.4.5). The server can lose, re-serve (duplicate) and expire
messages (TTL, as a step counter).

Crypto is abstracted away. A message is `(from, dh, seq, pn, ackDh, hk, token)` and a message
key is `<<dh, seq>>`, i.e. (epoch = chain, seq). The key, header-key and token assignments follow the
code line for line; the module header names the function each operator mirrors.

What lives on disk (`session`, `outbox`) and what lives only in memory (`inflight`, `toAck`,
`silent`, `posting`) are separate variables, and `Crash` drops the latter. Decrypting a batch and
saving the new session is one transaction in the code (`E2eeManager.processBatch` runs under the
store lock), so it is one step here.

The outbox is modelled too: it is persisted with the ratchet advance (`encryptAndAdvance`), drains
head first (`deliverInOrder`), and a crash between a PUT and the dequeue posts the frame again
(idempotent per msgId). Ordinary sends wait for an empty outbox; StoppedSharing (`SendUrgent`)
doesn't (#369, `StoppedSharingOutboxTest`). A failed PUT is a `Post` that doesn't happen, so the
frame stays queued (`OutboxFailurePropagationTest`).

## Properties

Safety (invariants):

| Name | Meaning |
|---|---|
| `NoDoubleAccept` | No `(epoch, seq)` is accepted twice, across crashes and server duplicates. |
| `SkippedWithinCap` | The skipped-key cache never exceeds `MAX_SKIPPED_KEYS` (`SkipCap`). |
| `RatchetSound` | Every DH step a receiver takes is one sub-step behind the sender's, so the derived keys match. |
| `AcksOnlyProcessed` | A non-forced ack never deletes a frame the receiver hasn't accepted (a rejected, unopenable or never-processed one). |
| `NoStrandedMessages` | While the server has lost or expired no unread frame: a client never polls only a retired token while unread messages wait on a newer one (the epoch's transition frame is still on its way to the polled token). |
| `NoSilentDrop` | Every frame sent has been accepted by the peer, or is still queued, on the server or in the peer's hands, or the server lost or expired it, or the peer acked it without accepting it. Nothing disappears from an outbox unsent. |
| `NoUnreadAcks` | While the server has lost or expired no unread frame, no client acks (deletes) a frame it hasn't accepted. Replays and duplicates are acked as processed; a force-ack only ever clears frames a loss or expiry made unprocessable. A protocol bug that leaves frames unopenable or rejected fails here, not only in liveness. |

Liveness (weak fairness on posting, dequeuing, polling, processing and acking; none on sending,
which is the user's choice, so the properties are checked whenever the clients stop sending;
crashes and duplicates allowed, no loss):

| Name | Meaning |
|---|---|
| `AllDelivered` | Every message sent is eventually accepted by the peer. |
| `EventuallySameEpoch` | Eventually each side knows every DH key the other has sent under. |

## Running

```bash
export TLA2TOOLS=/path/to/tla2tools.jar   # v1.7.4, as pinned in .github/workflows/tla.yml
formal/tla/run-tlc.sh WhereSyncSafety.cfg        # crashes, a duplicate, StoppedSharing       ~1.1M states, ~1 min
formal/tla/run-tlc.sh WhereSyncSafetyLoss.cfg    # a loss, a duplicate, a crash, 3 sends     ~3.9M states, ~4 min
formal/tla/run-tlc.sh WhereSyncSafetyReject.cfg  # two losses against a cap of 1: rejects, force-acks  ~0.3M, ~15 s
formal/tla/run-tlc.sh WhereSyncSafetyTTL.cfg     # TTL expiry and a crash                    ~0.7M states, ~30 s
formal/tla/run-tlc.sh WhereSyncSafetyDeep.cfg    # WhereSyncSafety with two crashes          ~2.8M states, ~2 min
formal/tla/run-tlc.sh WhereSyncLiveness.cfg      # liveness, with crashes and a duplicate
formal/tla/check-mutants.py                      # mutants and reachability probes
```

CI (`.github/workflows/tla.yml`) runs all six and `check-mutants.py` on PRs that touch the model,
the e2ee code or the spec, on main, and nightly. Across them: up to three sends per client
(seq ≤ 3; each side ratchets at least twice), up to two crashes, up to two lost messages, a
duplicated message, TTL expiry (of anything posted at clock 0), skipped-key
caps of 1 and 2, and force-ack after two silent polls. No single configuration has every fault:
together they multiply the state space past what a CI job can explore (all of them at once is
over 12M states and still growing).

`check-mutants.py` does two things:

- **Mutants.** It breaks one rule at a time and requires TLC to report the property that rule
  protects: the transition rule, the replay check, single use of skipped keys, the skipped-key
  cap, the ratchet's sub-steps, acking only what was processed, the bootstrap exception to
  CancelPut and header-key rotation (each both as a liveness and as a safety failure), dequeuing
  the posted frame by id, and recording the peer's new key. Some need a loss before the bug is
  reachable: with an honest server, outboxes drain in order and batches are sorted, so no gap
  and no unprocessable frame ever reaches a receiver.
- **Reachability probes.** For each behaviour a configuration claims to cover, it makes that
  behaviour trip an invariant and requires TLC to reach it in that configuration: a skipped key
  cached, a cached key used, the previous chain's gap filled, a frame rejected over the cap, a
  batch force-acked, a message expiring, a crash between saving and acking, both sides ratcheting
  twice, a duplicate served.

Modelled but not checked by any mutant: the empty-outbox gate on ordinary sends and the
force-ack threshold (no property depends on either).

Apalache was not needed: TLC covers these bounds in minutes. (The batch operators are `RECURSIVE`,
which Apalache doesn't support; it would need them rewritten as folds.)

## Counterexamples and findings

Each trace TLC finds becomes a regression test, then a fix in the code or the spec:

| Found by | Problem | Test | Fix |
|---|---|---|---|
| TLC, `AllDelivered` (and now `NoSilentDrop`) | Bob queues a frame before reading Alice's first; his first ratchet cancels it unsent. | `BootstrapOutboxCancelTest` | `ProtocolCore`: no CancelPut while `prevSendToken == sendToken` |
| Review of the model against the code | The outbox drained by `createdAt` (wall clock), so a clock stepped back reordered it; the model, and the transition rule, need FIFO. | `OutboxOrderTest` | `Outbox.sq`: order by `rowid` |

## Not modelled

Corrupted frames (spec §8.3.1(4) makes them leave the same state as a drop, which is modelled),
the 50-message GET window, skipped-key expiry (`MAX_KEY_AGE_MS`, the same 7 days as the TTL), the
handshake (both clients start paired), and more than one friend.

Observed but not a property: a skipped key kept across a DH ratchet can only be used within the
same batch, since later batches no longer hold the old epoch's header key (spec §5.3, "Scope").
