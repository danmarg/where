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
| `AcksOnlyProcessed` | A non-forced ack deletes only messages whose effect is already saved. |
| `NoStrandedMessages` | While the server has lost nothing: a client never polls only a retired token while unread messages wait on a newer one (the epoch's transition frame is still on its way to the polled token). |

Liveness (weak fairness on sends, posts, polls, processing and acks; crashes and duplicates allowed, no loss):

| Name | Meaning |
|---|---|
| `AllDelivered` | Every message sent is eventually accepted by the peer. |
| `EventuallySameEpoch` | Eventually each side knows the DH key the other is sending under. |

## Running

```bash
export TLA2TOOLS=/path/to/tla2tools.jar   # v1.7.4, as pinned in .github/workflows/tla.yml
formal/tla/run-tlc.sh WhereSyncSafety.cfg       # crashes, a duplicate, StoppedSharing   ~1.1M states, ~1 min
formal/tla/run-tlc.sh WhereSyncSafetyLoss.cfg   # a loss, a duplicate, a crash, 3 sends    ~3.9M states, ~4 min
formal/tla/run-tlc.sh WhereSyncSafetyTTL.cfg    # TTL expiry and a crash                   ~0.7M states, ~30 s
formal/tla/run-tlc.sh WhereSyncLiveness.cfg     # liveness, with crashes and a duplicate   ~1.1M states, ~3 min
formal/tla/run-tlc.sh WhereSyncSafetyDeep.cfg   # WhereSyncSafety with two crashes        ~2.8M states, ~2 min
formal/tla/check-mutants.py                     # every property catches the bug it is for
```

CI (`.github/workflows/tla.yml`) runs all five and the mutants on PRs that touch the model, the
e2ee code or the spec, on main, and nightly. The bounds: up to three sends
per client (seq ≤ 3, so up to six DH ratchets and three epochs per sender), one or two crashes,
one lost, one duplicated and one expired message, a skipped-key cap of 2, and force-ack after two
silent polls. No single configuration has every fault: together they multiply the state space past
what a CI job can explore (all of them at once is over 12M states and still growing).

`check-mutants.py` breaks one rule at a time (the transition rule, the replay check, single use of
skipped keys, the skipped-key cap, the ratchet's sub-steps, acking only what was processed, the
bootstrap exception to CancelPut, header-key rotation, recording the peer's new key) and requires TLC to report the property that
rule protects. Some of them need a loss before the bug is reachable: with an honest server,
outboxes drain in order and batches are sorted, so no gap and no unprocessable frame ever reaches a
receiver.

Apalache was not needed: TLC covers these bounds in minutes. (The batch operators are `RECURSIVE`,
which Apalache doesn't support; it would need them rewritten as folds.)

## Counterexamples

Each trace TLC finds becomes a regression test, then a fix in the code or the spec:

| Property | Trace | Test | Fix |
|---|---|---|---|
| `AllDelivered` | Bob queues a frame before reading Alice's first; his first ratchet cancels it unsent. | `BootstrapOutboxCancelTest` | `ProtocolCore`: no CancelPut while `prevSendToken == sendToken` |

## Not modelled

Corrupted frames (spec §8.3.1(4) makes them leave the same state as a drop, which is modelled),
the 50-message GET window, skipped-key expiry (`MAX_KEY_AGE_MS`, the same 7 days as the TTL), the
handshake (both clients start paired), and more than one friend.
