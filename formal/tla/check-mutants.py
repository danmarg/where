#!/usr/bin/env python3
"""Mutation check for WhereSync.tla: each mutant breaks one protocol rule, and TLC must report the
property that rule protects. A property no mutant can violate would be passing vacuously.

    TLA2TOOLS=/path/to/tla2tools.jar formal/tla/check-mutants.py [name-substring ...]
"""
import os, re, shutil, subprocess, sys, tempfile
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, 'WhereSync.tla')).read()
jar = os.environ['TLA2TOOLS']
work = tempfile.mkdtemp(prefix='wheresync-mutants-')

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
    if invariants:
        body += "INVARIANTS TypeOK NoDoubleAccept SkippedWithinCap RatchetSound AcksOnlyProcessed NoStrandedMessages\n"
    if live: body += "PROPERTIES " + " ".join(props or ["AllDelivered", "EventuallySameEpoch"]) + "\n"
    body += "CHECK_DEADLOCK FALSE\n"
    return body

M = [
  # (name, old, new, cfg kwargs, expected property)
  ("no transition rule",
   "token |-> IF seq = 1 THEN s.prevSendTok ELSE s.sendTok]", "token |-> s.sendTok]",
   dict(), "NoStrandedMessages"),
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
   dict(sends=1, live=True, props=["AllDelivered"]), "AllDelivered"),
  ("header keys not rotated",
   "!.hk = s.nhk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   "!.hk = s.hk, !.shk = s.root + 1, !.nhk = s.root + 2,",
   dict(sends=2, live=True, props=["AllDelivered"]), "AllDelivered"),
  ("peer's new key not recorded",
   "!.recvTok = <<Peer(c), s.root + 1>>, !.sendTok = <<c, s.root + 2>>,",
   "!.recvTok = <<Peer(c), s.root + 1>>, !.sendTok = <<c, s.root + 2>>, !.remoteDh = s.remoteDh,",
   dict(sends=1, live=True, props=["EventuallySameEpoch"], invariants=False), "EventuallySameEpoch"),
]
ok = True
only = sys.argv[1:]
for name, old, new, kw, expect in M:
    if only and not any(o in name for o in only): continue
    assert src.count(old) == 1, (name, old)
    d = os.path.join(work, re.sub(r'\W+', '_', name)); os.makedirs(d, exist_ok=True)
    open(os.path.join(d, 'WhereSync.tla'), 'w').write(src.replace(old, new))
    open(os.path.join(d, 'm.cfg'), 'w').write(cfg(**kw))
    r = subprocess.run(['java', '-Xss16m', '-cp', jar, 'tlc2.TLC', '-workers', 'auto', '-cleanup', '-metadir', os.path.join(d, 'st'),
                        '-config', 'm.cfg', 'WhereSync.tla'], cwd=d, capture_output=True, text=True, timeout=900)
    out = r.stdout
    found = re.findall(r'(?:Invariant|Temporal property) (\w+) (?:is|was) violated', out)
    # TLC 1.7.x doesn't name the temporal property; each liveness mutant checks exactly one.
    if 'Temporal properties were violated' in out:
        found += kw['props']
    hit = expect in found
    ok &= hit
    print(f"{'OK  ' if hit else 'MISS'} {name}: expected {expect}, TLC reported {found or 'no violation'}")
    sys.stdout.flush()
shutil.rmtree(work, ignore_errors=True)
sys.exit(0 if ok else 1)
