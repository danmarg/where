# WhereSync: a TLA+ model of delivery, epochs and acks

`WhereSync.tla` models two paired clients and the mailbox server at the level of spec §5.3–5.4 and
§8.3.1: sending, the transition frame on the old token (§5.4.1), polling one token (§5.4.2), batch
decryption with skipped keys and gap filling (§5.3, §8.3.1), the DH ratchet and token rotation,
send-side retirement of the transition frame (§5.4.3), acks and force-acks (§5.4.4), and crashes
between saving state and acking (§5.4.5). The server can lose, re-serve (duplicate) and expire
messages (TTL, as a step counter).

Crypto is abstracted away. A message is `(from, dh, seq, pn, ackDh, hk, token)`. The key, header-key
and token assignments follow the code line for line; the module header names the function each
operator mirrors.

### Identity

A frame has three identities in the code, and the model keeps two of them apart:

| Code | Model | Used for |
|---|---|---|
| message key: (DH public key of the sending chain, seq) | `KeyOf(m) == <<dh, seq>>` | what the receiver accepts, caches and must not accept twice (`accepted`, `skipped`, `NoDoubleAccept`, `lostKeys`, `ackedUnread`) |
| `msgId`: sha256 of the ciphertext (sha256 of `ekPub` for a key-exchange init), the outbox primary key and the server's PUT-idempotency and ack id | `Id(m) == <<from, dh, seq>>` | outbox dequeue, server dedup, acks and `PendingFrame` |
| mailbox token | `m.token` | where the frame waits |

`KeyOf` is already unique across directions: each DH name belongs to one side (Alice's chains are
1, 3, 5, …; Bob's are -2, 2, 4, …), as each DH public key does in the code, which is what lets
`accepted`, `lostKeys` and `ackedUnread` drop the sender. `Id` adds it anyway, redundantly but
harmlessly. Exactly one msgId per (sender, chain, seq) is an assumption: a message key encrypts
once, so the ciphertext and its hash, as an honest sender computes it, differ per frame; a frame
is never re-encrypted; and `Outbox.sq` inserts with plain INSERT, so a repeated msgId fails the
send instead of replacing (and re-queuing) a frame (`OutboxOrderTest`).

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
| `NoSilentDrop` | Per frame: every frame sent and not yet accepted is still deliverable. It is pending (in the sender's outbox, on the server, or in the peer's hands, matched by `Id`) on a mailbox the peer will read: the one it polls, or a newer one whose epoch's transition frame is itself pending on the one it polls. The only exceptions are the frame's own loss, expiry or unread ack, or its transition frame's (the peer then never reaches that mailbox, §5.4.5). A frame dropped from an outbox, or left on a mailbox the peer has moved past, fails here; a loss elsewhere excuses nothing, short of the full-cache case under *Known limits*. |
| `ForceAckOnlyUnprocessable` | A force-ack never deletes a frame that, opened against the receiver's state at that poll, would have been processed (delivered, or acked as a replay). With the next invariant, a force-ack only clears frames a loss or expiry made unprocessable. |
| `CachedKeysUsable` | Every cached message key belongs to a frame sealed under a header key the receiver still holds, so a later batch can reach it. Found the keys kept for frames no later batch could open (below). |
| `NoUnreadAcks` | While the server has lost or expired no unread frame, no client acks (deletes) a frame it hasn't accepted. Replays and duplicates are acked as processed; a force-ack only ever clears frames a loss or expiry made unprocessable. A protocol bug that leaves frames unopenable or rejected fails here, not only in liveness. |

Liveness (weak fairness on posting, dequeuing, polling, processing and acking; none on sending,
which is the user's choice, so the properties are checked whenever the clients stop sending;
crashes and duplicates allowed, no loss):

| Name | Meaning |
|---|---|
| `AllDelivered` | Every message sent is eventually accepted by the peer. |
| `EventuallyKnowsSentKeys` | Eventually each side holds, as the peer's DH key, the key of the last frame the peer actually sent. Keys a side has generated but not yet sent under are not required: convergence is about announced keys. |

Fairness is the standard weak fairness on each of those actions, except that Process's is written
out by hand (`WFProcess`): TLC evaluates `ENABLED Process(c)` by running the whole batch decryption
in every state, which made the liveness check about 100 times slower on TLC 1.7.4. The comment
above `FairnessWF` in the module proves the two equal: Process's only guard is `inflight[c] # {}`,
its other conjuncts always have a successor value, and every Process step changes `inflight`, so
`ENABLED <<Process(c)>>_vars` is exactly `inflight[c] # {}`. Two small configurations check it
mechanically as well: `WhereSyncFairness` runs the standard formulation (`SpecWF`) and checks it
implies `WFProcess`, `AllDelivered` and `EventuallyKnowsSentKeys`; `WhereSyncFairnessConverse` runs
`Spec` and checks it implies `WF_vars(Process(c))`.

## Running

```bash
export TLA2TOOLS=/path/to/tla2tools.jar   # v1.7.4, as pinned in .github/workflows/tla.yml
formal/tla/run-tlc.sh WhereSyncSafety.cfg        # crashes, a duplicate, StoppedSharing       ~1.1M states, ~1 min
formal/tla/run-tlc.sh WhereSyncSafetyLoss.cfg    # a loss, a duplicate, a crash, 3 sends     ~3.9M states, ~4 min
formal/tla/run-tlc.sh WhereSyncSafetyReject.cfg  # two losses against a cap of 1: rejects, force-acks  ~0.3M, ~15 s
formal/tla/run-tlc.sh WhereSyncSafetyTTL.cfg     # TTL expiry and a crash                    ~0.7M states, ~30 s
formal/tla/run-tlc.sh WhereSyncSafetyDeep.cfg    # WhereSyncSafety with two crashes          ~2.8M states, ~2 min
formal/tla/run-tlc.sh WhereSyncLiveness.cfg      # liveness, with crashes and a duplicate     ~1.1M states, ~3 min
formal/tla/run-tlc.sh WhereSyncFairness.cfg      # standard WF on Process => WFProcess, liveness   ~2K states, ~3 min
formal/tla/run-tlc.sh WhereSyncFairnessConverse.cfg  # WFProcess => standard WF on Process   ~2K states, ~3 min
formal/tla/check-mutants.py                      # mutants and reachability probes
```

CI (`.github/workflows/tla.yml`) runs all eight and `check-mutants.py` on PRs that touch the model,
the shared module's code or schema, the server or the spec, on main, and nightly. Across them: up to three sends per client
(seq ≤ 3; each side ratchets at least twice), up to two crashes, up to two lost messages, a
duplicated message, TTL expiry (of anything posted at clock 0), skipped-key
caps of 1 and 2, and force-ack after two silent polls. No single configuration has every fault:
together they multiply the state space past what a CI job can explore (all of them at once is
over 12M states and still growing).

`check-mutants.py` does two things:

- **Mutants.** It breaks one rule at a time and requires TLC to report the property that rule
  protects: the transition rule (against `NoStrandedMessages` alone, and against the per-frame
  `NoSilentDrop`), the replay check, single use of skipped keys, the skipped-key
  cap, the ratchet's sub-steps, acking only what was processed, the bootstrap exception to
  CancelPut and header-key rotation (each both as a liveness and as a safety failure), keeping
  unusable cached keys, dropping usable ones (all of them, or the bootstrap seq-1 key), dequeuing
  the posted frame by id, recording the peer's new key, force-acking only a batch that processed
  nothing, and dropping the fairness on processing.
  The last shows the hand-written fairness condition below is needed; the other liveness mutants
  still failing with it in place show it isn't too strong. Some need a loss before the bug is
  reachable: with an honest server, outboxes drain in order and batches are sorted, so no gap
  and no unprocessable frame ever reaches a receiver.
- **Reachability probes.** For each behaviour a configuration claims to cover, it makes that
  behaviour trip an invariant and requires TLC to reach it in that configuration: a skipped key
  cached, a cached key used, the previous chain's gap filled, a frame rejected over the cap, a
  batch force-acked, a message expiring, a crash between saving and acking, both sides ratcheting
  twice, a duplicate served.

Known limits. Once lost frames have filled the skipped-key cache, a batch can reject a frame of the
peer's current epoch over the cap and still accept a later epoch's transition frame (which drops the
old keys and moves the receive token), stranding the rejected frame on the old mailbox. That is a
loss's knock-on, but not the lost frame itself, so `NoSilentDrop` as written would flag it; the
checked bounds don't reach it (by hand it needs `SkipCap` 2 and three losses; not run), and in the
code the cap is 1000 keys. The stranded frame is typically one location update, superseded by the
next.

Modelled but not checked by any mutant: the empty-outbox gate on ordinary sends, the force-ack
threshold (no property depends on either), and keying the silent-poll counter by token. That last
one is unobservable here: a receive token only changes in a Process that processed something, which
resets the counter, so a per-friend counter behaves the same. Nor is one case the model can't
create: dropping a cached key for the current epoch's seq 1. An epoch's later frames go to the new
mailbox, so the receiver never reads one before the transition frame on the mailbox it polls first
(`SkippedKeyPruningTest` covers it, as a server could misroute frames).

Apalache was not needed: TLC covers these bounds in minutes. (The batch operators are `RECURSIVE`,
which Apalache doesn't support; it would need them rewritten as folds.)

## Counterexamples and findings

Each trace TLC finds becomes a regression test, then a fix in the code or the spec:

| Found by | Problem | Test | Fix |
|---|---|---|---|
| TLC, `AllDelivered` (and now `NoSilentDrop`) | Bob queues a frame before reading Alice's first; his first ratchet cancels it unsent. | `BootstrapOutboxCancelTest` | `ProtocolCore`: no CancelPut while `prevSendToken == sendToken` |
| TLC, `CachedKeysUsable` | A lost frame's key, filled in at the next ratchet, was kept although no later batch could open that frame (its header key was gone): useless to the receiver, a liability after a device compromise. | `SkippedKeyPruningTest` | `E2eeProtocol.dropUnusableSkippedKeys` after each batch |
| Review of the model against the code | The outbox drained by `createdAt` (wall clock), so a clock stepped back reordered it; the model, and the transition rule, need FIFO. | `OutboxOrderTest` | `Outbox.sq`: order by `rowid` |

## Assumptions, and the code that has to keep them

Each is something the model takes as given; a change to the code named breaks the model's claims
unless the model changes with it (which is why CI runs on changes there too).

| Assumption | Code |
|---|---|
| Decrypt, save the session, delete CancelPut's outbox rows and remove a consumed invite are one transaction; mailbox I/O comes after | `ProtocolCore` (atomicity contract), `E2eeManager.processBatch` / `processKeyExchangeInit`, `E2eeStore.withFriendAndMetadataLock`; `AtomicityTest` |
| A client polls only its saved receive token, and processes a batch only against the token it came from | `LocationClient.pollFriend`, `E2eeManager.processBatch` (stale-mailbox check), `ProtocolCore` `Receive(mailbox, …)` |
| The outbox is FIFO per friend, drains head first, and dequeues by msgId only after a successful PUT | `Outbox.sq` (`ORDER BY rowid`, plain INSERT), `LocationClient.processOutbox` / `deliverInOrder`; `OutboxOrderTest`, `OutboxFailurePropagationTest` |
| Ordinary sends wait for an empty outbox; StoppedSharing doesn't | `LocationClient.sendLocation` / `sendStoppedSharing`; `StoppedSharingOutboxTest` |
| One msgId per frame; PUT is idempotent per (token, msgId) | `EncryptedMessagePayload.msgId`, `Server.kt` `post` (`receivedIds`) and the DynamoDB store's conditional put |
| A poll returns every frame on the token, and acks delete by msgId | `Server.kt` GET / DELETE `/inbox/{token}`; the 50-frame GET window is not modelled (below) |
| Force-ack after `MAX_SILENT_DROP_RETRIES` polls of one token that processed nothing, counted in memory per friend and token | `LocationClient` `silentDropRetries`; the model's `ForceAckAfter` is 2, not 5 |
| The server keeps a frame `TTL` and may lose, expire or re-serve it; nothing else | `Server.kt` `MAILBOX_TTL_MS` (7 days) |

## Not modelled

Corrupted frames (spec §8.3.1(4) makes them leave the same state as a drop, which is modelled),
the 50-message GET window, skipped-key expiry (`MAX_KEY_AGE_MS`, the same 7 days as the TTL), the
handshake (both clients start paired), and more than one friend.

