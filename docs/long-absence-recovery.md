# Surviving a long absence

Status: plan, not implemented. Context: #481, PR #489. Reviewed by the advisor and Codex (see the end).

## Summary

A friendship breaks if one side's app doesn't run for more than a week (Android force-stop, iOS
swipe-away, a phone left off). With the local session intact, the cause is the server's 7-day
message TTL. (Losing the local database, a device reset or a device-binding mismatch still needs a
re-pair, by design.) The plan:

1. **Server message retention 7 → 90 days; dedup records stay at 7.** Absences up to 90 days
   survive with no protocol change, and the server's log of all traffic (`receivedIds`) stays at
   today's week.
2. **Client retention to match.** Constants that assume 7-day retention follow the new value, and
   a friend silent for nearly as long as retention (~88 days) is labelled "may need to re-pair".
3. **Keep the 7-day pause, plus one parting keepalive.** After 7 days with nothing from a friend,
   stop sending to them ("Friend inactive — sharing paused"), as today, except for one last
   keepalive if we've sent them nothing since that point. Without it, two friends who are both
   away for more than a week deadlock (found by `LongAbsenceTest`).
4. **Optional, separate:** send less often to a friend who has gone quiet, and send at once
   when they come back (freshness; saves only ~$0.01/day).
5. **Deferred:** surviving absences beyond retention (Appendix A).

What a user sees, by time since the last frame from a friend:

| Silence | Shown | Behaviour |
|---|---|---|
| < 7 days | normal | sharing as usual |
| 7–88 days | "Friend inactive — sharing paused" | we stop sending to them; recovery is automatic when their app next runs |
| ≥ ~88 days | "May need to re-pair" | still polled; clears by itself if a frame arrives |
| > 90 days | same | usually broken (rarely recoverable up to ~97 days) |

## Why sessions break today

Let Bob be away and Alice online. If nothing expired, Bob would come back, read Alice's backlog
in order and carry on. The client's 7-day pause (`FriendEntry.isStale`, `ACK_TIMEOUT_SECONDS`)
caps that backlog at ~2,000 frames, under the server's `MAX_QUEUE_DEPTH` (10,000) and fine for
`MAX_MESSAGES_PER_POLL`. It deletes nothing: the friend is still polled and the flag clears on
the next frame. So the survivable absence equals server message retention. Expiry shows up three
ways:

- **Lost transition frame (permanent desync).** Routing tokens rotate with the DH ratchet. The
  first frame of a new sending epoch (`seq == 1`) goes to the *previous* token (spec §5.4.1); it
  is the only way the reader learns the new token. Almost every absence starts with one in
  flight toward the absent side. The outbox drops it once the PUT succeeds
  (`LocationClient.deliverInOrder`), and the server expires it after 7 days. After that the
  token Bob polls stays empty. (`ExhaustiveScheduleTest` lists this as not modelled.)
- **Ratchet gap.** Once Alice's oldest frames have expired, the oldest one left can be more than
  `MAX_SKIPPED_KEYS` (1,000) ahead of Bob's `recvSeq`; `ProtocolGapException` (`Session.kt:219`),
  then a force-ACK, and every later frame is further ahead. Within retention there is no gap.
- **Both sides silent.** Alice pauses at day 7; by day 14 her frames have expired. Bob comes back
  to nothing, so Alice is inactive to him too, and neither sends.

Unaffected by longer retention: `MAX_KEY_AGE_MS` (7 days) ages skipped keys from when they were
stored, i.e. from Bob's processing, not from Alice's send (`Session.kt:116`).

## Design

### 1. Server retention: messages 90 days, dedup records 7 days

- Two TTLs instead of one `MAILBOX_TTL_MS`: messages (and their GSI) 90 days, `receivedIds`
  7 days, in both the Dynamo and in-memory stores. Message retention becomes one shared constant,
  `MAILBOX_RETENTION_SECONDS` in `ProtocolConstants.kt` (the server already depends on
  `:shared`), so server and client can't drift. The dedup TTL is server-only.
- **Why split them.** `receivedIds` keeps a row (token, msgId, `expiresAt`, which gives the post
  time to the second) for every post, even after it's read: the only durable record of all
  traffic, from which per-friendship activity timelines can be rebuilt by timing. The messages
  table deletes a row when it's read, so at 90 days it mostly holds traffic to absent friends.
  Request logs redact tokens and don't log IPs (`Server.kt:1049`); point-in-time recovery is off
  and there are no backups. So keeping `receivedIds` at 7 days keeps the traffic log at today's
  week while sessions survive 90 days. (It could go to 1 day; same trade, more often.)
- **Dedup shorter than the message is safe.**
  - A retry whose message row is still live fails the conditional put on the message key, so
    there's no duplicate, and then rewrites its dedup row (`Server.kt:645`).
  - A retry arriving more than 7 days after a message that was already read (the row is
    deleted) gets re-inserted. The reader drops it as a replay and ACKs it. If the reader has
    since moved to a new token, the copy sits unread until the TTL. That's noise, not a fault.
  - The TTL sweep's lag (up to 48 h) only makes dedup last longer. Add a test for the late retry.
- Rows posted before the deploy keep their 7-day `expiresAt`, so the change protects frames sent
  after it. No migration.
- Old clients benefit as soon as the server deploys: nothing about receiving changes.
- **Depth cache.** `depthCounts` isn't decremented when the TTL sweep deletes rows, so a cached
  count can stay too high after expiry and reject posts early (true today; longer retention
  makes it likelier). Give each entry a seed time and re-count it from the table once it's older
  than a day. Update the invariant comment (`Server.kt:721`) and the class doc (`Server.kt:476`).
- **In-memory store.** Its dedup set has no expiry (only a per-token size cap), so give each ID a
  7-day expiry, as Dynamo has.
- **Poll cost after a big backlog expires.** `drain()` pages through the GSI until it finds 50
  live rows, so for up to 48 h after a large backlog expires (before the sweep) a poll of that
  token reads the expired rows too: ≤ ~2,000 rows, ~400 RRU per poll. Only the returning reader
  polls such a token, so it's rare. Accepted.
- **Cost, measured 2026-11-22:** live data ~128 MB (messages 24 MB, GSI 24 MB, `receivedIds`
  80 MB), inside the free tier. At 90 days the unread messages (~3.7k/day) grow to ~330k items,
  about 0.55 GB with the GSI; `receivedIds` is unchanged. ~0.6 GB in all: still free (25 GB free
  tier), ~$0.31/GB-month beyond. Writes are unchanged; TTL deletes are free.
- **Privacy:** unread ciphertext stays up to 90 days instead of 7: same content and tokens,
  longer exposure to a later server breach combined with a device compromise (an unread frame is
  decryptable from the reader's state anyway). Only someone getting the database later is
  affected; live access sees traffic regardless of retention.

### 2. Client retention

- `ORPHANED_OUTBOX_RETENTION_MILLIS` (a removed friend's undelivered frames, currently tied to
  `ACK_TIMEOUT_SECONDS`) follows `MAILBOX_RETENTION_SECONDS`. Decouple it from the 7-day pause.
- **Expired friend (label only).** After `MAILBOX_RETENTION_SECONDS` minus 2 days (~88 days)
  without a frame, show "May need to re-pair" with Remove and Re-pair actions, instead of the
  inactive text. The label is a warning, so it has to come *before* the break. The session
  usually breaks ~90 days after last contact: our transition frame to them (§5.4.1) normally goes
  out within minutes of our last receipt and expires 90 days after that. The 2-day margin covers
  that delay and clock skew. Between ~90 and ~97 days a session can still recover if no
  transition was outstanding (our pre-pause frames live until about day 97). That is why the
  wording is "may", and why the label clears by itself if a frame arrives. Remaining gap: a peer
  that sent without polling first makes our `lastRecvTs` newer than our transition's post time,
  so the label comes late. Rare, accepted.
- **Derived, not stored, and polling continues.** Like `isStale`, it's computed from
  `lastRecvTs`, so it clears by itself and needs no migration. Polling never stops: no bound on
  the last useful post is enforced by the code (an outbox frame queued before the pause can be
  delivered days later, since outbox flushes aren't gated on `isStale`, and clocks jump), so
  giving up on a session could break one that would still recover. Cost: one GET per labelled
  friend per poll, at read prices (reads are ~$0.07/day in total today).
- **Cold start.** A returning device has an old `lastRecvTs` for everyone, so it shows the label
  until its first poll. Show it only once a poll of that friend has succeeded this session.
- Both platforms' friends lists (`FriendsSheet.kt`, `FriendsSheet.swift`) and the strings in
  `moko-resources` (en, de, es, …).

### 3. The 7-day pause (unchanged)

Keep `ACK_TIMEOUT_SECONDS` = 7 days and `isStale` as they are: no Locations, StoppedSharing or
keepalives to an inactive friend, still polled, the flag clears on the next frame, "Friend
inactive — sharing paused" in the UI. Rename the constant to `INACTIVITY_PAUSE_SECONDS` so it
isn't confused with retention. It doesn't block recovery within retention: Alice's frames from
before her pause stay on the server until about day 97, so a Bob back by day 90 reads them,
refreshes `lastRecvTs` and sends, and that ends Alice's pause. No one has to press "share".

**Parting keepalive.** That argument assumes Alice's app was running when the pause began. If
both apps were stopped across it (Bob leaves, Alice's app is also stopped from day 0 to day 30,
Bob is back at day 60), Alice comes back to find Bob already stale and sends nothing, and Bob
comes back to find nothing from her, so she's stale to him. Neither sends again, whatever the
retention. So a stale friend gets exactly one keepalive if we've sent them nothing since they
went stale (`lastSentTs < lastRecvTs + INACTIVITY_PAUSE_SECONDS`, in `pollFriend`). A client
that was running sends it just after the pause (or not at all, if its last regular send already
landed on the pause point); one that was away sends it on its first poll back. Either way the
friend finds a frame from us dated at or after the pause, and it lives a full retention. Cost:
one frame per pause. An older client doesn't do this, so a both-away pair with an old client
that comes back first can still deadlock.

### 4. Optional: less traffic to a quiet friend, quick resume

Independent of survival; ship separately if wanted. Measured 2026-11-22: ~3.7k posts/day (~4%)
go to tokens nobody reads, so the ladder saves ~$0.01/day. Worth it for the quick resume, not cost.

- **Ladder.** The per-friend send interval to a quiet friend (`now - lastRecvTs`) is 5 min for
  the first 6 h (today), then 1 h until the 7-day pause. It applies to Locations (the
  unresponsive-friend throttle) and keepalives (`weAreSilent`) alike, so a paused friend and an
  unresponsive one still look the same to a timing observer. Frames per absence ~2,000 → ~234,
  so writes to a dead device drop from ~288/day to ~24/day.
- **Resume.** When a frame arrives from a friend who had been quiet for more than 6 h, and we're
  sharing with them, send them a Location at once through the per-friend path (as for a manual
  unpause; outside `LocationSendScheduler`). Otherwise they'd see a location up to an hour old,
  or wait for our next heartbeat.
- Cost: a friend quiet for > 6 h sees a location up to 1 h old for one round trip.

## Implementation plan

Each step is a commit and leaves the build green.

**Phase 1: retention (items 1-3)** — implemented on branch `long-absence-retention`
(2026-11-22), uncommitted. Done: steps 3-6, and step 2's `LongAbsenceTest` (`shared/jvmTest`:
10/89-day absences sharing and not, the 7-day-TTL failure, past retention, both away, a failed
first poll, the backlog drained over several polls with a fresh location checked, one parting
keepalive). Not done: the late-outbox and return-order cases, and the TTL fault in
`ExhaustiveScheduleTest`. The label is text only (the existing Remove action is the way out).

Recovery within retention is conditional on each side's polls fetching and processing the
other's frames before they expire. A backlog over `MAX_MESSAGES_PER_POLL` just takes more polls;
a poll failure retries on the next one. The tests below cover both.

1. ~~Measure DynamoDB storage~~ Done 2026-11-22 (see 1. Cost).
2. Tests first: `LongAbsenceTest` (`shared/commonTest`), with two `LocationClient`s over an
   in-memory mailbox that applies the TTLs, and a fake clock. Cases (each also with a backlog
   spanning several polls, a failed poll on return, and asymmetric return order):
   - Bob away 10, 30 and 89 days, with Alice sharing and not sharing → resumes. Fails today past
     7 days.
   - Both away 60 days → resumes.
   - Away 88-89 days → labelled, then recovers on return and the label clears.
   - Away 100 days → labelled on both sides, still polled, not recoverable.
   - An outbox frame delivered late (Alice offline when the pause starts) → recovers if the
     peer returns within 90 days of that post, and the label clears.
   - Bob back on day 92-96 → usually broken: Alice's transition frame from day 0 has expired
     (the pre-pause frames are still there but sit behind a token Bob can no longer reach).
   Also teach `ExhaustiveScheduleTest` the TTL as a clock-jump fault, and drop TTL from its
   "not modelled" list.
3. Server: shared `MAILBOX_RETENTION_SECONDS` for messages; a separate 7-day dedup TTL for
   `receivedIds`; both stores, with an injectable clock. In-memory dedup gets per-ID expiry.
   The depth cache re-seeds entries older than a day. Tests:
   - TTLs applied.
   - A live-row retry is still deduped, including after its dedup row is gone.
   - A late retry after the message was ACKed and its dedup row expired is re-inserted (and
     dropped as a replay by the client).
   - A stale dedup row still suppresses a retry.
   - An expired message row awaiting the sweep is replaceable.
   - A cached depth recovers after expiry.
   Search the repo for other 7-day assumptions (server config, scripts, docs). Deploy the server.
4. Client: `ORPHANED_OUTBOX_RETENTION_MILLIS` follows `MAILBOX_RETENTION_SECONDS`; rename
   `ACK_TIMEOUT_SECONDS` → `INACTIVITY_PAUSE_SECONDS`.
5. Client expiry label: derived property on `FriendEntry`, shown after a successful poll this
   session; UI on both platforms, plus any other place the stale state is shown (search the
   `isStale` and `friend_inactive_warning` uses); strings in every `moko-resources` locale.
6. Docs:
   - Spec: §10.2 and the §10.1 table (message retention, dedup TTL); §5.4.4's "ages out
     (7 days)"; §5.7.2 (it says the 7-day window "tears down a peer's session entirely", which the
     code doesn't do; describe the pause and the label); §13 item 4; the §2.1.1 metadata threat
     model (what the server retains, for how long).
   - CLAUDE.md "Server state" ("retained for 7 days, aligning with the client re-pair timeout").
   - Release notes.
   - The golden transcript and `protocol_version` are unchanged.

**Phase 2: optional traffic changes (item 4)**

7. The ladder, as one `quietFriendSendInterval(silence)` in `LocationClient`, used by
   `isSendDueForUnresponsiveFriend` and the `weAreSilent` check; keep
   `AUTOMATED_KEEPALIVE_BACKSTOP_SECONDS` for the sharing case. Update the tests that pin the
   5-min cadence.
8. Resume send in `poll`, tested in `LocationClientTest`.
9. Spec §5.7.2 keepalive cadence; §7.4 timing note.

**Phase 3 (deferred):** Appendix A, only if absences longer than retention matter.

## What this doesn't fix

- Absences longer than retention (90 days): re-pair, as today after 7. (Between 90 and ~97 days
  it depends on whether a transition frame was outstanding toward the absent side; usually one was.)
- The app not running still means no updates while it's off. Only the session survives.
- Frames already on the server at deploy time keep their 7-day expiry.

## Appendix A: surviving absences beyond retention (deferred)

Designed and reviewed, but not planned. It adds a sender rule (spec §5.4.1/§5.4.3) in the most
fragile part of the protocol, two server changes, new storage, and some metadata cost: a
periodic re-post pattern, and a friendship that stays visibly active. It would also need the
ladder (so the gap stays under `MAX_SKIPPED_KEYS` after expiry) and a periodic probe keepalive to
inactive friends (so both sides never go silent for good, including a returning peer on an older
version).

### A.1 Keep the transition frame alive until the peer has read it

When the sender encrypts the `seq == 1` frame of an epoch, it also writes a copy (token +
payload, as in the outbox: ciphertext, no keys) to a new `TransitionKeep` table, one row per
friend, **in the same transaction** as `encryptAndAdvance` and the outbox insert. Writing it only
after the PUT would lose the frame to a crash between the outbox delete and the keep write. A
newer epoch's transition frame replaces the row. The row is dropped when the session's
`prevSendToken` moves past the kept token, in the receive transaction that commits the ratchet.

That is the same event on which `ProtocolCore` already emits `CancelPut` for the token
(send-side retirement, §5.4.3), and it does prove the peer read the frame: the peer makes a new
DH key only after reading our current one (§5.4.4), and the first frame of our epoch it can open
is the transition frame, since the later ones are on a token it can only derive from it. That
last step relies on the receiver polling a single token at a time (§5.4.2); two-token or
dummy-token polling (§7.5) would break this argument and need it revisited. Crossed
sends don't break this: frames the peer sent before reading ours carry its old DH key and don't
ratchet us. The converse, a peer that has read the frame but hasn't sent since, only costs
harmless re-PUTs (below).

Not kept for the responder's bootstrap epoch (`prevSendToken == sendToken`): there seq 1 isn't a
routing transition, all bootstrap frames share the token the initiator polls.

While the row exists, `pollFriend`'s outbox-recovery step re-PUTs it when its last PUT is older
than `TRANSITION_REFRESH_SECONDS` (3 days). A row is eligible only once its own outbox row is
gone: `lastPutAt` is null until then and is set in the same transaction as the outbox delete in
`deliverInOrder`. Otherwise a keep row written at encrypt time could post T ahead of an older
frame still queued for the same token (e.g. after a 429); the peer would ratchet past that token,
retirement would cancel the straggler, and the in-order delivery §5.4.3 relies on breaks (the
#369 shape if the straggler is a StoppedSharing). `msgId = sha256(ct)`, so it is the identical frame.
Because the check runs on every poll, a writer coming back from its own long absence re-PUTs on
its first poll. That on-return re-PUT is what gives correctness (it also covers both sides being
away); the 3-day refresh while running only keeps the frame continuously present, so a
returning reader never waits for the writer's next poll.

Server, two small changes (today a duplicate PUT is a no-op that extends nothing):

- **Dedup honours expiry** (needed for the on-return re-PUT). The dedup `GetItem` on
  `receivedIds` treats a row with `expiresAt <= now` as absent; the TTL sweep can lag up to 48 h.
  A re-PUT after full expiry then inserts afresh (the message-row put already allows overwriting
  an expired row).
- **Touch on duplicate** (needed for continuous presence). A PUT of a msgId whose message row is
  live extends `expiresAt` on it and its `receivedIds` row. Same msgId ⇒ same ct, so nothing is
  replaced or reordered; `postedAt` is unchanged. Cost: ~2 `UpdateItem`s per outstanding pair
  per 3 days. No depth-cache change (count unchanged). If the message row is gone (ACKed),
  the `receivedIds` row is **not** extended, so it still expires on its own schedule.
- Both run inside `post()`'s existing per-token lock, like the dedup and depth checks today
  (sufficient because the server runs as one JVM, `max_machines_running = 1`; a second instance
  would need conditional writes here as everywhere else in `post()`). A re-insert after expiry
  is a new live row and counts once in `depthCounts`; a touch doesn't count. The in-memory store
  gets the same semantics.

If Bob has already read and ACKed the frame but hasn't sent since (Alice hasn't retired), a
re-PUT either no-ops (receivedIds live) or re-inserts a copy on a token Bob no longer polls,
which expires unread.

### A.2 Where it lives

- **(a) Outside `ProtocolCore`** (`E2eeManager` + `LocationClient`). The core and golden
  transcript don't change. But `ExhaustiveScheduleTest` drives `ProtocolCore`, so its keep /
  re-PUT / drop model would be a second implementation, and the real transactions above wouldn't
  be what gets exhaustively checked.
- **(b) As `ProtocolCore` effects** (recommended): keep on the seq-1 put, re-put when due,
  release on retirement, with the keep state in the core's state. The exhaustive and property
  tests then check the real rule, and it sits with the other protocol rules as CLAUDE.md asks
  (D1 rewrites §5.4.1/§5.4.3 anyway). Cost: regenerate the golden transcript and bump the
  vectors and spec versions; no wire change, `protocol_version` unchanged.


## Review notes

- Advisor, pass 1: keep the agreed 7-day pause; justify or drop the probe; on-return re-PUT for
  Appendix A; check the storage layer (SQLDelight) and what the golden transcript records
  (`ProtocolCore` effects and state).
- Codex, pass 1 (on the earlier D1-centred draft): confirmed the three failure modes from the
  code; led to A.1's atomic keep row, touch-only-live-rows, and spelled-out server semantics.
  Disputed: whether our ratchet proves the peer read our transition (argued in A.1).
- Advisor, pass 2: the re-PUT ordering hole (fixed in A.1); `ProtocolCore` placement (A.2).
- Cost/benefit: Appendix A deferred in favour of 30-day retention for the reported use case.
- Codex, pass 2 (this plan): narrowed the root-cause claim (local data loss still needs a re-pair);
  the ~38-day expiry bound isn't enforced by the code (late outbox posts, clock jumps), so the
  label is derived and polling never stops, which also drops the stored `expiredAt`;
  `receivedIds` keeps the message TTL; tests for multi-poll backlogs, failed polls and late
  posts. Verified sound: the 7-day pause doesn't block recovery (the poll runs before the
  stale-gated send, and processing refreshes `lastRecvTs`); `MAX_KEY_AGE_MS`, the invite expiry,
  `lastPollTs`, the depth-cache size bound, the rate limiter and the poll padding don't assume
  7 days; rows already on the server keep their old expiry.
- Follow-up (user): the label is a warning, so it must come before the break (~30 days after last
  contact, when the outstanding transition frame expires), not at the ~38-day "definitely broken"
  point. Now retention minus 2 days (~28 days).
- Follow-up (user): storage is ~free, so message retention 90 days (label at ~88), with
  `receivedIds` kept at 7 days. It's the only durable log of all traffic; the messages table
  forgets what's read.
- Codex, pass 3 (90 d / 7 d split): split is safe given in-memory dedup expiry and a
  self-correcting depth cache (both added); extra tests for stale rows on either table; transient
  poll cost after a big backlog expires (accepted); repo-wide search for 7-day assumptions.
- Implementation (2026-11-22): the both-away test found a deadlock the plan missed (both apps
  stopped across the 7-day pause); fixed with the one-shot parting keepalive (item 3). The label
  also requires a successful empty poll (`isCaughtUp`), so a failed poll on return doesn't show it.
