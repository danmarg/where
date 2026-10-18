----------------------------- MODULE WhereSync -----------------------------
(***************************************************************************)
(* Delivery, epochs and acks in Where's ratchet (docs/e2ee-location-sync.md *)
(* §5.3-5.4, §8.3.1), for two paired clients and the mailbox server.       *)
(*                                                                         *)
(* Crypto is abstracted away. Both sides walk one root chain; the DH       *)
(* sub-step that produces root index r also produces chain r and header    *)
(* key r, and a DH public key is named by the root index of the sub-step   *)
(* that introduced it. The keys from SK get negative names:                *)
(*   DH keys   A0 = -1, B0 = -2   (and the initial chains with them)       *)
(*   headers   H0 = -1 (Alice's), H1 = -2 (Bob's), HN = -3 (next)          *)
(*   tokens    <<sender, 0>> from SK, <<sender, r>> from root r.           *)
(* A message is (from, dh, seq, pn, ackDh, hk, token); a message key is    *)
(* <<dh, seq>>, i.e. (epoch = chain, seq). The assignments mirror the code *)
(* line for line: KeyExchange.initSession and aliceProcessInit for the     *)
(* initial states, Session.performDhRatchet for Ratchet, encryptPadded and *)
(* SessionState.transportFor for Encrypt, decryptMessageOrThrow for        *)
(* DecryptOne, E2eeProtocol.decryptAndSort/decryptBatch for the batch,     *)
(* LocationClient.pollFriend/processOutbox for the I/O around it.          *)
(*                                                                         *)
(* Persisted vs in-memory: `session` and `outbox` are what each client has *)
(* on disk. `inflight`, `toAck`, `silent` and `posting` live only in       *)
(* memory and are lost on Crash. Decrypting a batch and saving the new     *)
(* session is one transaction in the code (E2eeManager.processBatch runs   *)
(* under the store lock), so Process is one step; the windows a crash can *)
(* hit are GET -> Process (batch lost, refetched) and Process -> DELETE    *)
(* (§5.4.5: state saved, messages left on the server), and Post -> dequeue *)
(* on the send side (the frame is posted again).                           *)
(***************************************************************************)
EXTENDS Integers, Sequences, FiniteSets

CONSTANTS
    MaxSends,       \* ordinary sends (Location/Keepalive) per client
    MaxUrgent,      \* StoppedSharing sends per client (skip the empty-outbox gate)
    MaxCrashes,     \* crashes, both clients together
    MaxLoss,        \* messages the server loses
    MaxDup,         \* deleted messages the server serves again
    MaxClock,       \* server clock bound (0 = no time passes, so no expiry)
    TTL,            \* server message lifetime in clock ticks (7 days)
    SkipCap,        \* MAX_SKIPPED_KEYS
    ForceAckAfter   \* MAX_SILENT_DROP_RETRIES

Clients == {"A", "B"}
Peer(c) == IF c = "A" THEN "B" ELSE "A"

\* Each ratchet adds 2 to a root index; there is at most one per received new key.
MaxRoot == 2 * 2 * (MaxSends + MaxUrgent) + 3
Tokens == Clients \X (0 .. MaxRoot)
DhIds == -2 .. MaxRoot
Keys == DhIds \X (1 .. (MaxSends + MaxUrgent))
None == <<"none">>

VARIABLES
    session,    \* persisted ratchet state, per client
    outbox,     \* persisted outbox, per client (sequence of messages)
    mbox,       \* server: token -> set of messages
    inflight,   \* in memory: the batch from the last GET
    polledTok,  \* in memory: the token that batch came from
    toAck,      \* in memory: ids to DELETE (after Process, before Ack)
    ackForced,  \* in memory: toAck is a force-ACK of an unprocessable batch
    silent,     \* in memory: consecutive polls that processed nothing
    posting,    \* in memory: id of the outbox head just posted, or None
    clock,      \* server clock
    \* ghost / bounds
    sends, urgent, crashes, losses, dups,
    sent,       \* keys each client sent
    accepted,   \* keys each client accepted (delivered and saved)
    everPosted, \* every message ever stored, for Dup
    lostKeys,   \* keys lost or expired on the server before acceptance
    flags       \* violations recorded by actions (checked by invariants)

vars == <<session, outbox, mbox, inflight, polledTok, toAck, ackForced, silent, posting,
          clock, sends, urgent, crashes, losses, dups, sent, accepted, everPosted,
          lostKeys, flags>>

Id(m) == <<m.from, m.dh, m.seq>>
KeyOf(m) == <<m.dh, m.seq>>

-----------------------------------------------------------------------------
(* Session state *)

\* KeyExchange.initSession for Bob; aliceProcessInit (initSession + Alice's first DH step).
InitSession(c) ==
    IF c = "A"
    THEN [root |-> 1, localDh |-> 1, remoteDh |-> -2,
          sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0,
          sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>,
          hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}]
    ELSE [root |-> 0, localDh |-> -2, remoteDh |-> -1,
          sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0,
          sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>,
          hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]

\* Session.performDhRatchet: receive sub-step to root+1, a fresh key, send sub-step to root+2.
Ratchet(c, s, dh) ==
    [s EXCEPT !.root = s.root + 2, !.localDh = s.root + 2, !.remoteDh = dh,
              !.hk = s.nhk, !.shk = s.root + 1, !.nhk = s.root + 2,
              !.recvTok = <<Peer(c), s.root + 1>>, !.sendTok = <<c, s.root + 2>>,
              !.pshk = s.shk, !.prevSendTok = s.sendTok,
              !.sendSeq = 0, !.recvSeq = 0, !.pn = s.sendSeq, !.pr = s.recvSeq]

\* Session.encryptPadded: the epoch's first frame goes to the previous mailbox under the
\* previous header key (§5.4.1, transportFor).
Encrypt(c, s) ==
    LET seq == s.sendSeq + 1 IN
    [from |-> c, dh |-> s.localDh, seq |-> seq, pn |-> s.pn, ackDh |-> s.remoteDh, at |-> 0,
     hk |-> IF seq = 1 THEN s.pshk ELSE s.shk,
     token |-> IF seq = 1 THEN s.prevSendTok ELSE s.sendTok]

\* tryDecryptHeader: only the current and next receive header keys (§8.3.1(6)).
Opens(s, m) == m.hk \in {s.hk, s.nhk}

\* Session.decryptMessageOrThrow. res is "deliver", "replay" or "reject"; bad marks a DH step
\* whose keys can't match the sender's (the receiver isn't one sub-step behind it).
DecryptOne(c, s, m) ==
    LET key == KeyOf(m) IN
    IF key \in s.skipped
    THEN [st |-> [s EXCEPT !.skipped = @ \ {key}], res |-> "deliver", bad |-> FALSE]
    ELSE
      LET isNew == m.dh # s.remoteDh
          sp == IF isNew THEN Ratchet(c, s, m.dh) ELSE s
      IN
      IF m.seq <= sp.recvSeq
      THEN [st |-> s, res |-> "replay", bad |-> FALSE]
      ELSE
        LET steps == m.seq - sp.recvSeq
            kept == IF isNew THEN {k \in sp.skipped : k[1] \in {m.dh, s.remoteDh}} ELSE sp.skipped
            pnGaps == IF isNew /\ m.pn > s.recvSeq THEN m.pn - s.recvSeq ELSE 0
            projected == Cardinality(kept) + (steps - 1) + pnGaps
        IN
        IF steps > SkipCap + 1 \/ pnGaps > SkipCap \/ projected > SkipCap
        THEN [st |-> s, res |-> "reject", bad |-> FALSE]
        ELSE [st |-> [sp EXCEPT !.recvSeq = m.seq,
                                !.skipped = kept
                                    \cup {<<m.dh, k>> : k \in (sp.recvSeq + 1) .. (m.seq - 1)}
                                    \cup {<<s.remoteDh, k>> : k \in (s.recvSeq + 1) .. (s.recvSeq + pnGaps)}],
              res |-> "deliver",
              bad |-> isNew /\ m.dh # s.root + 1]

\* E2eeProtocol.decryptAndSort: current-epoch frames by seq, then new-epoch frames by pn, seq
\* (bucket and header keys from the state before the batch).
Rank(s0, m) == <<IF m.dh = s0.remoteDh THEN 1 ELSE 2, IF m.dh = s0.remoteDh THEN 0 ELSE m.pn, m.seq, m.dh>>
LexLess(a, b) == \E i \in 1 .. 4 : a[i] < b[i] /\ \A j \in 1 .. (i - 1) : a[j] = b[j]

RECURSIVE Sorted(_, _)
Sorted(S, s0) ==
    IF S = {} THEN << >>
    ELSE LET m == CHOOSE x \in S : \A y \in S \ {x} : LexLess(Rank(s0, x), Rank(s0, y))
         IN <<m>> \o Sorted(S \ {m}, s0)

\* E2eeProtocol.decryptBatch over the sorted frames.
RECURSIVE Run(_, _, _, _)
Run(c, ms, s, acc) ==
    IF ms = << >> THEN [acc EXCEPT !.st = s]
    ELSE LET m == Head(ms)
             r == DecryptOne(c, s, m)
             acc2 == [acc EXCEPT
                        !.delivered = IF r.res = "deliver" THEN @ \cup {KeyOf(m)} ELSE @,
                        !.ackIds = IF r.res \in {"deliver", "replay"} THEN @ \cup {Id(m)} ELSE @,
                        !.bad = @ \/ r.bad]
         IN Run(c, Tail(ms), r.st, acc2)

ProcessBatch(c, s0, batch) ==
    Run(c, Sorted({m \in batch : Opens(s0, m)}, s0), s0,
        [st |-> s0, delivered |-> {}, ackIds |-> {}, bad |-> FALSE])

-----------------------------------------------------------------------------
(* Initial state *)

Init ==
    /\ session = [c \in Clients |-> InitSession(c)]
    /\ outbox = [c \in Clients |-> << >>]
    /\ mbox = [t \in Tokens |-> {}]
    /\ inflight = [c \in Clients |-> {}]
    /\ polledTok = [c \in Clients |-> None]
    /\ toAck = [c \in Clients |-> {}]
    /\ ackForced = [c \in Clients |-> FALSE]
    /\ silent = [c \in Clients |-> 0]
    /\ posting = [c \in Clients |-> None]
    /\ clock = 0
    /\ sends = [c \in Clients |-> 0]
    /\ urgent = [c \in Clients |-> 0]
    /\ crashes = 0 /\ losses = 0 /\ dups = 0
    /\ sent = [c \in Clients |-> {}]
    /\ accepted = [c \in Clients |-> {}]
    /\ everPosted = {}
    /\ lostKeys = {}
    /\ flags = [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE]

-----------------------------------------------------------------------------
(* Client actions *)

\* E2eeManager.encryptAndAdvance: advance and queue in one transaction.
Enqueue(c, m) ==
    /\ session' = [session EXCEPT ![c].sendSeq = @ + 1]
    /\ outbox' = [outbox EXCEPT ![c] = Append(@, m)]
    /\ sent' = [sent EXCEPT ![c] = @ \cup {KeyOf(m)}]

\* A Location or Keepalive: only with an empty outbox (sendMessageToFriendInternal).
Send(c) ==
    /\ sends[c] < MaxSends
    /\ outbox[c] = << >>
    /\ Enqueue(c, Encrypt(c, session[c]))
    /\ sends' = [sends EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<mbox, inflight, polledTok, toAck, ackForced, silent, posting, clock,
                   urgent, crashes, losses, dups, accepted, everPosted, lostKeys, flags>>

\* StoppedSharing: queued even behind a stuck outbox (#369).
SendUrgent(c) ==
    /\ urgent[c] < MaxUrgent
    /\ Enqueue(c, Encrypt(c, session[c]))
    /\ urgent' = [urgent EXCEPT ![c] = @ + 1]
    /\ UNCHANGED <<mbox, inflight, polledTok, toAck, ackForced, silent, posting, clock,
                   sends, crashes, losses, dups, accepted, everPosted, lostKeys, flags>>

\* PUT /inbox/{token}/{msgId} of the outbox head; idempotent per msgId.
Post(c) ==
    /\ outbox[c] # << >>
    /\ posting[c] = None
    /\ LET m == Head(outbox[c])
           present == \E x \in mbox[m.token] : Id(x) = Id(m)
           stored == [m EXCEPT !.at = clock]
       IN /\ mbox' = IF present THEN mbox ELSE [mbox EXCEPT ![m.token] = @ \cup {stored}]
          /\ everPosted' = IF present THEN everPosted ELSE everPosted \cup {m}
          /\ posting' = [posting EXCEPT ![c] = Id(m)]
    /\ UNCHANGED <<session, outbox, inflight, polledTok, toAck, ackForced, silent, clock,
                   sends, urgent, crashes, losses, dups, sent, accepted, lostKeys, flags>>

\* removeFromOutbox by msgId after the PUT returned (a no-op if CancelPut got there first).
Dequeue(c) ==
    /\ posting[c] # None
    /\ outbox' = [outbox EXCEPT ![c] = SelectSeq(@, LAMBDA x : Id(x) # posting[c])]
    /\ posting' = [posting EXCEPT ![c] = None]
    /\ UNCHANGED <<session, mbox, inflight, polledTok, toAck, ackForced, silent, clock,
                   sends, urgent, crashes, losses, dups, sent, accepted, everPosted, lostKeys, flags>>

\* GET /inbox/{recvToken}: the one token this client polls (§5.4.2).
Poll(c) ==
    /\ inflight[c] = {} /\ toAck[c] = {}
    /\ mbox[session[c].recvTok] # {}
    /\ inflight' = [inflight EXCEPT ![c] = mbox[session[c].recvTok]]
    /\ polledTok' = [polledTok EXCEPT ![c] = session[c].recvTok]
    /\ UNCHANGED <<session, outbox, mbox, toAck, ackForced, silent, posting, clock,
                   sends, urgent, crashes, losses, dups, sent, accepted, everPosted, lostKeys, flags>>

\* E2eeManager.processBatch (decrypt, save, CancelPut) and pollFriend's ack choice.
Process(c) ==
    /\ inflight[c] # {}
    /\ LET s0 == session[c]
           out == ProcessBatch(c, s0, inflight[c])
           advanced == out.st.root # s0.root
           nothing == out.ackIds = {}
           force == nothing /\ silent[c] + 1 >= ForceAckAfter
       IN /\ session' = [session EXCEPT ![c] = out.st]
          \* CancelPut: the peer read our transition frame (§5.4.3). Not while the previous send
          \* token is still the bootstrap one (Bob's first ratchet answers Alice's own, §4.4).
          /\ outbox' = IF advanced /\ s0.prevSendTok # s0.sendTok
                       THEN [outbox EXCEPT ![c] = SelectSeq(@, LAMBDA x : x.token # s0.prevSendTok)]
                       ELSE outbox
          /\ accepted' = [accepted EXCEPT ![c] = @ \cup out.delivered]
          /\ toAck' = [toAck EXCEPT ![c] = IF force THEN {Id(m) : m \in inflight[c]} ELSE out.ackIds]
          /\ ackForced' = [ackForced EXCEPT ![c] = force]
          /\ silent' = [silent EXCEPT ![c] = IF nothing /\ ~force THEN @ + 1 ELSE 0]
          /\ flags' = [flags EXCEPT !.doubleAccept = @ \/ (out.delivered \cap accepted[c] # {}),
                                    !.badRatchet = @ \/ out.bad]
    /\ inflight' = [inflight EXCEPT ![c] = {}]
    /\ UNCHANGED <<mbox, polledTok, posting, clock, sends, urgent, crashes, losses, dups,
                   sent, everPosted, lostKeys>>

\* DELETE /inbox/{token}?ids=...
Ack(c) ==
    /\ toAck[c] # {}
    /\ LET t == polledTok[c]
           gone == {m \in mbox[t] : Id(m) \in toAck[c]}
       IN /\ mbox' = [mbox EXCEPT ![t] = @ \ gone]
          /\ flags' = [flags EXCEPT !.ackedUnprocessed =
                         @ \/ (~ackForced[c] /\ \E m \in gone : KeyOf(m) \notin accepted[c])]
          /\ lostKeys' = lostKeys \cup {KeyOf(m) : m \in {x \in gone : KeyOf(x) \notin accepted[c]}}
    /\ toAck' = [toAck EXCEPT ![c] = {}]
    /\ ackForced' = [ackForced EXCEPT ![c] = FALSE]
    /\ UNCHANGED <<session, outbox, inflight, polledTok, silent, posting, clock, sends, urgent,
                   crashes, losses, dups, sent, accepted, everPosted>>

\* Process death: everything in memory is gone; disk and server survive.
Crash(c) ==
    /\ crashes < MaxCrashes
    /\ crashes' = crashes + 1
    /\ inflight' = [inflight EXCEPT ![c] = {}]
    /\ polledTok' = [polledTok EXCEPT ![c] = None]
    /\ toAck' = [toAck EXCEPT ![c] = {}]
    /\ ackForced' = [ackForced EXCEPT ![c] = FALSE]
    /\ silent' = [silent EXCEPT ![c] = 0]
    /\ posting' = [posting EXCEPT ![c] = None]
    /\ UNCHANGED <<session, outbox, mbox, clock, sends, urgent, losses, dups, sent, accepted,
                   everPosted, lostKeys, flags>>

-----------------------------------------------------------------------------
(* Server faults *)

Lose ==
    /\ losses < MaxLoss
    /\ \E t \in Tokens : \E m \in mbox[t] :
          /\ mbox' = [mbox EXCEPT ![t] = @ \ {m}]
          /\ lostKeys' = lostKeys \cup {KeyOf(m)}
    /\ losses' = losses + 1
    /\ UNCHANGED <<session, outbox, inflight, polledTok, toAck, ackForced, silent, posting, clock,
                   sends, urgent, crashes, dups, sent, accepted, everPosted, flags>>

\* A deleted message served again.
Dup ==
    /\ dups < MaxDup
    /\ \E m \in everPosted :
          /\ ~\E x \in mbox[m.token] : Id(x) = Id(m)
          /\ mbox' = [mbox EXCEPT ![m.token] = @ \cup {[m EXCEPT !.at = clock]}]
    /\ dups' = dups + 1
    /\ UNCHANGED <<session, outbox, inflight, polledTok, toAck, ackForced, silent, posting, clock,
                   sends, urgent, crashes, losses, sent, accepted, everPosted, lostKeys, flags>>

Tick ==
    /\ clock < MaxClock
    /\ clock' = clock + 1
    /\ UNCHANGED <<session, outbox, mbox, inflight, polledTok, toAck, ackForced, silent, posting,
                   sends, urgent, crashes, losses, dups, sent, accepted, everPosted, lostKeys, flags>>

Expire ==
    /\ \E t \in Tokens : \E m \in mbox[t] :
          /\ clock - m.at >= TTL
          /\ mbox' = [mbox EXCEPT ![t] = @ \ {m}]
          /\ lostKeys' = lostKeys \cup {KeyOf(m)}
    /\ UNCHANGED <<session, outbox, inflight, polledTok, toAck, ackForced, silent, posting, clock,
                   sends, urgent, crashes, losses, dups, sent, accepted, everPosted, flags>>

-----------------------------------------------------------------------------

ClientStep(c) ==
    Send(c) \/ SendUrgent(c) \/ Post(c) \/ Dequeue(c) \/ Poll(c) \/ Process(c) \/ Ack(c) \/ Crash(c)

Next ==
    \/ \E c \in Clients : ClientStep(c)
    \/ Lose \/ Dup \/ Tick \/ Expire

\* Weak fairness on the client's own work; none on crashes or server faults.
Fairness ==
    \A c \in Clients :
        /\ WF_vars(Send(c)) /\ WF_vars(SendUrgent(c))
        /\ WF_vars(Post(c)) /\ WF_vars(Dequeue(c))
        /\ WF_vars(Poll(c)) /\ WF_vars(Process(c)) /\ WF_vars(Ack(c))

Spec == Init /\ [][Next]_vars /\ Fairness

-----------------------------------------------------------------------------
(* Safety *)

TypeOK ==
    /\ \A c \in Clients : session[c].root <= MaxRoot - 2
    /\ \A t \in Tokens : \A m \in mbox[t] : m.token = t

\* No (epoch, seq) is accepted twice, even across crashes and server duplicates.
NoDoubleAccept == ~flags.doubleAccept

\* The skipped-key cache stays within MAX_SKIPPED_KEYS.
SkippedWithinCap == \A c \in Clients : Cardinality(session[c].skipped) <= SkipCap

\* Every DH step the receiver takes is one sub-step behind the sender, so its keys match.
RatchetSound == ~flags.badRatchet

\* A non-forced ACK deletes only messages whose state change is already saved.
AcksOnlyProcessed == ~flags.ackedUnprocessed

\* A client never polls only a retired token while unread messages wait on a newer one: any
\* unaccepted frame on a token newer than the one it polls has its epoch's transition frame
\* still on the way to the polled token (queued, on the server there, or in hand). Holds while
\* the server keeps what it stored (§5.4.5 "What this does NOT fix").
TransitionPending(c, m) ==
    LET isT(x) == x.from = m.from /\ x.dh = m.dh /\ x.seq = 1
    IN \/ \E x \in mbox[session[c].recvTok] : isT(x)
       \/ \E i \in 1 .. Len(outbox[m.from]) : isT(outbox[m.from][i])
       \/ \E x \in inflight[c] : isT(x)

NoStrandedMessages ==
    lostKeys = {} =>
        \A c \in Clients : \A t \in Tokens : \A m \in mbox[t] :
            (m.from = Peer(c) /\ KeyOf(m) \notin accepted[c]
             /\ t[1] = Peer(c) /\ t[2] > session[c].recvTok[2])
                => TransitionPending(c, m)

\* The sent keys are the ones the peer can derive: within the key space we enumerate.
Safety == TypeOK /\ NoDoubleAccept /\ SkippedWithinCap /\ RatchetSound /\ AcksOnlyProcessed
          /\ NoStrandedMessages

-----------------------------------------------------------------------------
(* Liveness (with MaxLoss = 0 and no expiry) *)

\* Every message sent is eventually accepted by the peer.
AllDelivered ==
    \A c \in Clients : \A k \in Keys : [](k \in sent[c] => <>(k \in accepted[Peer(c)]))

\* Both sides eventually agree on each other's DH key: each knows the key the other is
\* sending under, unless the other has not sent under it yet.
Synced(c) == session[c].remoteDh = session[Peer(c)].localDh \/ session[Peer(c)].sendSeq = 0
EventuallySameEpoch == <>[](\A c \in Clients : Synced(c))

=============================================================================
