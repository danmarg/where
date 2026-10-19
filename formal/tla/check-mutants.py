#!/usr/bin/env python3
"""Mutation check for WhereSync.tla: each mutant breaks one protocol rule, and TLC must report the
property that rule protects. A property no mutant can violate would be passing vacuously.

    TLA2TOOLS=/path/to/tla2tools.jar formal/tla/check-mutants.py [name-substring ...]
"""
import os, re, shutil, subprocess, sys, tempfile, time
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'WhereSync.tla')).read()
jar = os.environ['TLA2TOOLS']
work = tempfile.mkdtemp(prefix='wheresync-mutants-')
TIMEOUT = 480  # per TLC run (the largest probe config takes ~4 min on 4 cores); a timeout is a MISS
BUDGET = 60 * 60  # whole script; runs past it are reported as not run
started = time.monotonic()

ALL_INVARIANTS = ["TypeOK", "NoDoubleAccept", "SkippedWithinCap", "RatchetSound", "AcksOnlyProcessed",
                  "NoStrandedMessages", "NoSilentDrop", "NoUnreadAcks", "ForceAckOnlyUnprocessable",
                  "CachedKeysUsable"]

def cfg(sends=2, urgent=0, crashes=0, loss=0, dup=0, clock=0, cap=2, live=False, props=None, invariants=True):
    body = f"""CONSTANTS
    MaxSends = {sends}
    MaxUrgent = {urgent}
    MaxCrashes = {crashes}
    MaxLoss = {loss}
    MaxDup = {dup}
    MaxClock = {clock}
    TTL = 2
    SkipCap = {cap}
    ForceAckAfter = 2
"""
    body += "SPECIFICATION Spec\n" if live else "INIT Init\nNEXT Next\n"
    if invariants is True:
        invariants = ALL_INVARIANTS
    if invariants:
        body += "INVARIANTS " + " ".join(invariants) + "\n"
    if live: body += "PROPERTIES " + " ".join(props or ["AllDelivered", "EventuallyKnowsSentKeys"]) + "\n"
    body += "CHECK_DEADLOCK FALSE\n"
    return body

def run_tlc(d):
    """TLC's output, or a marker: 'timed out', 'not run' (over budget), or 'error: <first Error line>'."""
    if time.monotonic() - started > BUDGET:
        return 'not run'
    try:
        r = subprocess.run(['java', '-XX:+UseParallelGC', '-Xss16m', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-cleanup', '-metadir', os.path.join(d, 'st'),
                            '-config', 'm.cfg', 'WhereSync.tla'], cwd=d, capture_output=True, text=True, timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return 'timed out'
    out = r.stdout + r.stderr
    violated = re.search(r'(?:Invariant \w+ is|Temporal propert(?:y \w+ was|ies were)) violated', out)
    if not violated:
        errors = [l for l in out.splitlines() if l.startswith('Error:')]
        if errors or r.returncode not in (0,):
            return 'error: ' + (errors[0].removeprefix('Error: ') if errors else f'TLC exited {r.returncode}')
    return out

M = [
  # (name, old, new, cfg kwargs, expected property)
  ("no transition rule",
   "token |-> IF seq = 1 THEN s.prevSendTok ELSE s.sendTok]", "token |-> s.sendTok]",
   dict(invariants=["TypeOK", "NoStrandedMessages"]), "NoStrandedMessages"),
  ("no transition rule, against the per-frame NoSilentDrop",
   "token |-> IF seq = 1 THEN s.prevSendTok ELSE s.sendTok]", "token |-> s.sendTok]",
   dict(), "NoSilentDrop"),
  ("no replay check",
   "IF m.seq <= sp.recvSeq\n      THEN [st |-> s, res |-> \"replay\", bad |-> FALSE]",
   "IF m.seq < 0\n      THEN [st |-> s, res |-> \"replay\", bad |-> FALSE]",
   dict(sends=2, crashes=1, dup=1), "NoDoubleAccept"),
  ("skipped keys reusable",
   "THEN [st |-> [s EXCEPT !.skipped = @ \\ {key}], res |-> \"deliver\", bad |-> FALSE]",
   "THEN [st |-> s, res |-> \"deliver\", bad |-> FALSE]",
   dict(sends=3, loss=1, dup=2), "NoDoubleAccept"),
  ("no skipped-key cap",
   "IF steps > SkipCap + 1 \\/ pnGaps > SkipCap \\/ projected > SkipCap",
   "IF FALSE",
   dict(sends=3, cap=1, loss=2), "SkippedWithinCap"),
  ("ratchet skips a sub-step",
   "[s EXCEPT !.root = s.root + 2, !.localDh = s.root + 2,",
   "[s EXCEPT !.root = s.root + 3, !.localDh = s.root + 3,",
   dict(), "RatchetSound"),
  ("ack the whole batch",
   "THEN {Id(m) : m \\in inflight[c]} ELSE out.ackIds]",
   "THEN {Id(m) : m \\in inflight[c]} ELSE {Id(m) : m \\in inflight[c]}]",
   dict(sends=2, cap=0, loss=1), "AcksOnlyProcessed"),
  ("cancel on every ratchet (the old bug)",
   "IF advanced /\\ s0.prevSendTok # s0.sendTok", "IF advanced",
   dict(sends=1, live=True, props=["AllDelivered"], invariants=False), "AllDelivered"),
  ("header keys not rotated",
   "!.hk = s.nhk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   "!.hk = s.hk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   dict(sends=2, live=True, props=["AllDelivered"], invariants=False), "AllDelivered"),
  ("cancel on every ratchet, as a safety check",
   "IF advanced /\\ s0.prevSendTok # s0.sendTok", "IF advanced",
   dict(sends=1), "NoSilentDrop"),
  ("dequeue the head instead of the posted frame",
   "/\\ outbox' = [outbox EXCEPT ![c] = SelectSeq(@, LAMBDA x : Id(x) # posting[c])]",
   "/\\ outbox' = [outbox EXCEPT ![c] = IF @ = << >> THEN @ ELSE Tail(@)]",
   dict(sends=2, urgent=1), "NoSilentDrop"),
  ("header keys not rotated, as a safety check",
   "!.hk = s.nhk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   "!.hk = s.hk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   dict(sends=2), "NoUnreadAcks"),
  ("processing not fair (the hand-written WFProcess does constrain behaviours)",
   "/\\ WF_vars(Poll(c)) /\\ WFProcess(c) /\\ WF_vars(Ack(c))", "/\\ WF_vars(Poll(c)) /\\ WF_vars(Ack(c))",
   dict(sends=1, live=True, props=["AllDelivered"], invariants=False), "AllDelivered"),
  ("unusable cached keys kept",
   "    IN [out EXCEPT !.st = DropUnusable(c, out.st)]", "    IN out",
   dict(sends=2, loss=1), "CachedKeysUsable"),
  ("every cached key dropped",
   "Usable(c, s, k) == k[1] = s.remoteDh /\\ (k[2] >= 2 \\/ s.remoteDh = PeerBootstrapDh(c))", "Usable(c, s, k) == FALSE",
   dict(sends=2, loss=1, dup=1), "AcksOnlyProcessed"),
  ("no bootstrap exception for cached seq-1 keys",
   "Usable(c, s, k) == k[1] = s.remoteDh /\\ (k[2] >= 2 \\/ s.remoteDh = PeerBootstrapDh(c))", "Usable(c, s, k) == k[1] = s.remoteDh /\\ k[2] >= 2",
   dict(sends=2, loss=1, dup=1), "AcksOnlyProcessed"),
  ("peer's new key not recorded",
   "!.recvTok = <<Peer(c), s.root + 1>>, !.sendTok = <<c, s.root + 2>>,",
   "!.recvTok = <<Peer(c), s.root + 1>>, !.sendTok = <<c, s.root + 2>>, !.remoteDh = s.remoteDh,",
   dict(sends=1, live=True, props=["EventuallyKnowsSentKeys"], invariants=False), "EventuallyKnowsSentKeys"),
  ("force-ACK of a batch that processed something",
   "force == nothing /\\ silentHere + 1 >= ForceAckAfter", "force == silentHere + 1 >= ForceAckAfter",
   dict(sends=2, loss=1, cap=0, dup=1), "ForceAckOnlyUnprocessable"),
]
ok = True
only = sys.argv[1:]
for name, old, new, kw, expect in M:
    if only and not any(o in name for o in only): continue
    assert src.count(old) == 1, (name, old)
    d = os.path.join(work, re.sub(r'\W+', '_', name)); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'WhereSync.tla'), 'w').write(src.replace(old, new))
    open(os.path.join(d, 'm.cfg'), 'w').write(cfg(**kw))
    out = run_tlc(d)
    found = re.findall(r'(?:Invariant|Temporal property) (\w+) (?:is|was) violated', out)
    if out == 'timed out':
        found.append(f'nothing (timed out after {TIMEOUT} s)')
    elif out == 'not run':
        found.append('nothing (not run: over the time budget)')
    elif out.startswith('error: '):
        found.append(out)
    # TLC 1.7.x doesn't name the temporal property; each liveness mutant checks exactly one.
    if 'Temporal properties were violated' in out:
        found += kw['props']
    hit = expect in found
    ok &= hit
    print(f"{'OK  ' if hit else 'MISS'} {name}: expected {expect}, TLC reported {found or 'no violation'}")
    sys.stdout.flush()
# Reachability probes: each makes one behaviour trip RatchetSound (or adds a state invariant) and
# runs against the checked-in configuration that claims to cover it. A probe TLC never trips
# means that configuration doesn't reach the behaviour, so its properties say nothing about it.
CRASH = """    /\\ crashes' = crashes + 1
    /\\ inflight' = [inflight EXCEPT ![c] = {}]"""
CRASH_UNCHANGED = """    /\\ UNCHANGED <<session, outbox, mbox, clock, sends, urgent, losses, dups, sent, accepted,
                   everPosted, lostKeys, ackedUnread, flags>>"""
P = [
  # (name, config, edits [(old, new)], extra invariant definition or None)
  ("a skipped key is cached", "WhereSyncSafetyLoss", [], "Probe == \\A c \\in Clients : session[c].skipped = {}"),
  ("a cached key is used", "WhereSyncSafetyLoss",
   [("THEN [st |-> [s EXCEPT !.skipped = @ \\ {key}], res |-> \"deliver\", bad |-> FALSE]",
     "THEN [st |-> [s EXCEPT !.skipped = @ \\ {key}], res |-> \"deliver\", bad |-> TRUE]")], None),
  ("the previous chain's gap is filled", "WhereSyncSafetyLoss",
   [("bad |-> isNew /\\ m.dh # s.root + 1]", "bad |-> (isNew /\\ m.dh # s.root + 1) \\/ pnGaps > 0]")], None),
  ("a frame is rejected over the cap", "WhereSyncSafetyReject",
   [("THEN [st |-> s, res |-> \"reject\", bad |-> FALSE]", "THEN [st |-> s, res |-> \"reject\", bad |-> TRUE]")], None),
  ("a batch is force-ACKed", "WhereSyncSafetyReject", [], "Probe == \\A c \\in Clients : ~ackForced[c]"),
  ("a message expires", "WhereSyncSafetyTTL", [], "Probe == lostKeys = {}"),
  ("a crash lands between saving and acking", "WhereSyncSafety",
   [(CRASH, CRASH.replace("crashes + 1", "crashes + 1\n    /\\ flags' = [flags EXCEPT !.badRatchet = toAck[c] # {}]")),
    (CRASH_UNCHANGED, CRASH_UNCHANGED.replace(", flags>>", ">>"))], None),
  ("each side ratchets twice", "WhereSyncSafety", [], "Probe == ~(session[\"A\"].root >= 5 /\\ session[\"B\"].root >= 4)"),
  ("a duplicate is served", "WhereSyncSafety", [], "Probe == dups = 0"),
]
for name, config, edits, probe in P:
    if only and not any(o in name for o in only): continue
    text = src
    for old, new in edits:
        assert text.count(old) == 1, (name, old)
        text = text.replace(old, new)
    want = "RatchetSound"
    if probe:
        text = text.replace("\n=============================================================================", "\n" + probe + "\n=============================================================================")
        want = "Probe"
    d = os.path.join(work, "probe_" + re.sub(r'\W+', '_', name)); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'WhereSync.tla'), 'w').write(text)
    c = open(os.path.join(here, config + '.cfg')).read()
    c = re.sub(r'^INVARIANTS .*$', 'INVARIANTS ' + want, c, flags=re.M)
    open(os.path.join(d, 'm.cfg'), 'w').write(c)
    out = run_tlc(d)
    hit = f'Invariant {want} is violated' in out
    why = '' if hit else {'timed out': f' (timed out after {TIMEOUT} s)', 'not run': ' (not run: over the time budget)'}.get(
        out, f' ({out})' if out.startswith('error: ') else ' (never reached)')
    ok &= hit
    print(f"{'OK  ' if hit else 'MISS'} reachable in {config}: {name}{why}")
    sys.stdout.flush()

shutil.rmtree(work, ignore_errors=True)
sys.exit(0 if ok else 1)
