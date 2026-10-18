---- MODULE WhereSync_TTrace_1792359926 ----
EXTENDS Sequences, WhereSync, TLCExt, Toolbox, Naturals, TLC

_expression ==
    LET WhereSync_TEExpression == INSTANCE WhereSync_TEExpression
    IN WhereSync_TEExpression!expression
----

_trace ==
    LET WhereSync_TETrace == INSTANCE WhereSync_TETrace
    IN WhereSync_TETrace!trace
----

_prop ==
    ~<>[](
        silent = ([A |-> 0, B |-> 0])
        /\
        ackForced = ([A |-> FALSE, B |-> FALSE])
        /\
        session = ([A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 2, localDh |-> 2, remoteDh |-> 1, sendSeq |-> 0, recvSeq |-> 1, pn |-> 1, pr |-> 0, sendTok |-> <<"B", 2>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 1>>, hk |-> -3, nhk |-> 2, shk |-> 1, pshk |-> -2, skipped |-> {}]])
        /\
        flags = ([doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE])
        /\
        accepted = ([A |-> {}, B |-> {<<1, 1>>}])
        /\
        everPosted = ({[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]})
        /\
        clock = (0)
        /\
        crashes = (0)
        /\
        losses = (0)
        /\
        posting = ([A |-> <<"none">>, B |-> <<"none">>])
        /\
        outbox = ([A |-> <<>>, B |-> <<>>])
        /\
        mbox = ((<<"A", 0>> :> {} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}))
        /\
        sent = ([A |-> {<<1, 1>>}, B |-> {<<-2, 1>>}])
        /\
        lostKeys = ({})
        /\
        inflight = ([A |-> {}, B |-> {}])
        /\
        polledTok = ([A |-> <<"none">>, B |-> <<"A", 0>>])
        /\
        sends = ([A |-> 1, B |-> 1])
        /\
        toAck = ([A |-> {}, B |-> {}])
        /\
        dups = (0)
        /\
        urgent = ([A |-> 0, B |-> 0])
    )
----

_init ==
    /\ toAck = _TETrace[1].toAck
    /\ silent = _TETrace[1].silent
    /\ dups = _TETrace[1].dups
    /\ accepted = _TETrace[1].accepted
    /\ urgent = _TETrace[1].urgent
    /\ clock = _TETrace[1].clock
    /\ ackForced = _TETrace[1].ackForced
    /\ flags = _TETrace[1].flags
    /\ lostKeys = _TETrace[1].lostKeys
    /\ outbox = _TETrace[1].outbox
    /\ inflight = _TETrace[1].inflight
    /\ polledTok = _TETrace[1].polledTok
    /\ mbox = _TETrace[1].mbox
    /\ losses = _TETrace[1].losses
    /\ sent = _TETrace[1].sent
    /\ crashes = _TETrace[1].crashes
    /\ session = _TETrace[1].session
    /\ posting = _TETrace[1].posting
    /\ sends = _TETrace[1].sends
    /\ everPosted = _TETrace[1].everPosted
----

_next ==
    /\ \E i,j \in DOMAIN _TETrace:
        /\ \/ /\ j = i + 1
              /\ i = TLCGet("level")
        /\ toAck  = _TETrace[i].toAck
        /\ toAck' = _TETrace[j].toAck
        /\ silent  = _TETrace[i].silent
        /\ silent' = _TETrace[j].silent
        /\ dups  = _TETrace[i].dups
        /\ dups' = _TETrace[j].dups
        /\ accepted  = _TETrace[i].accepted
        /\ accepted' = _TETrace[j].accepted
        /\ urgent  = _TETrace[i].urgent
        /\ urgent' = _TETrace[j].urgent
        /\ clock  = _TETrace[i].clock
        /\ clock' = _TETrace[j].clock
        /\ ackForced  = _TETrace[i].ackForced
        /\ ackForced' = _TETrace[j].ackForced
        /\ flags  = _TETrace[i].flags
        /\ flags' = _TETrace[j].flags
        /\ lostKeys  = _TETrace[i].lostKeys
        /\ lostKeys' = _TETrace[j].lostKeys
        /\ outbox  = _TETrace[i].outbox
        /\ outbox' = _TETrace[j].outbox
        /\ inflight  = _TETrace[i].inflight
        /\ inflight' = _TETrace[j].inflight
        /\ polledTok  = _TETrace[i].polledTok
        /\ polledTok' = _TETrace[j].polledTok
        /\ mbox  = _TETrace[i].mbox
        /\ mbox' = _TETrace[j].mbox
        /\ losses  = _TETrace[i].losses
        /\ losses' = _TETrace[j].losses
        /\ sent  = _TETrace[i].sent
        /\ sent' = _TETrace[j].sent
        /\ crashes  = _TETrace[i].crashes
        /\ crashes' = _TETrace[j].crashes
        /\ session  = _TETrace[i].session
        /\ session' = _TETrace[j].session
        /\ posting  = _TETrace[i].posting
        /\ posting' = _TETrace[j].posting
        /\ sends  = _TETrace[i].sends
        /\ sends' = _TETrace[j].sends
        /\ everPosted  = _TETrace[i].everPosted
        /\ everPosted' = _TETrace[j].everPosted

\* Uncomment the ASSUME below to write the states of the error trace
\* to the given file in Json format. Note that you can pass any tuple
\* to `JsonSerialize`. For example, a sub-sequence of _TETrace.
    \* ASSUME
    \*     LET J == INSTANCE Json
    \*         IN J!JsonSerialize("WhereSync_TTrace_1792359926.json", _TETrace)

=============================================================================

 Note that you can extract this module `WhereSync_TEExpression`
  to a dedicated file to reuse `expression` (the module in the 
  dedicated `WhereSync_TEExpression.tla` file takes precedence 
  over the module `WhereSync_TEExpression` below).

---- MODULE WhereSync_TEExpression ----
EXTENDS Sequences, WhereSync, TLCExt, Toolbox, Naturals, TLC

expression == 
    [
        \* To hide variables of the `WhereSync` spec from the error trace,
        \* remove the variables below.  The trace will be written in the order
        \* of the fields of this record.
        toAck |-> toAck
        ,silent |-> silent
        ,dups |-> dups
        ,accepted |-> accepted
        ,urgent |-> urgent
        ,clock |-> clock
        ,ackForced |-> ackForced
        ,flags |-> flags
        ,lostKeys |-> lostKeys
        ,outbox |-> outbox
        ,inflight |-> inflight
        ,polledTok |-> polledTok
        ,mbox |-> mbox
        ,losses |-> losses
        ,sent |-> sent
        ,crashes |-> crashes
        ,session |-> session
        ,posting |-> posting
        ,sends |-> sends
        ,everPosted |-> everPosted
        
        \* Put additional constant-, state-, and action-level expressions here:
        \* ,_stateNumber |-> _TEPosition
        \* ,_toAckUnchanged |-> toAck = toAck'
        
        \* Format the `toAck` variable as Json value.
        \* ,_toAckJson |->
        \*     LET J == INSTANCE Json
        \*     IN J!ToJson(toAck)
        
        \* Lastly, you may build expressions over arbitrary sets of states by
        \* leveraging the _TETrace operator.  For example, this is how to
        \* count the number of times a spec variable changed up to the current
        \* state in the trace.
        \* ,_toAckModCount |->
        \*     LET F[s \in DOMAIN _TETrace] ==
        \*         IF s = 1 THEN 0
        \*         ELSE IF _TETrace[s].toAck # _TETrace[s-1].toAck
        \*             THEN 1 + F[s-1] ELSE F[s-1]
        \*     IN F[_TEPosition - 1]
    ]

=============================================================================



Parsing and semantic processing can take forever if the trace below is long.
 In this case, it is advised to uncomment the module below to deserialize the
 trace from a generated binary file.

\*
\*---- MODULE WhereSync_TETrace ----
\*EXTENDS IOUtils, WhereSync, TLC
\*
\*trace == IODeserialize("WhereSync_TTrace_1792359926.bin", TRUE)
\*
\*=============================================================================
\*

---- MODULE WhereSync_TETrace ----
EXTENDS WhereSync, TLC

trace == 
    <<
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {}, B |-> {}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"none">>],sends |-> [A |-> 0, B |-> 0],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"none">>],sends |-> [A |-> 1, B |-> 0],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"A", 1, 1>>, B |-> <<"none">>],outbox |-> [A |-> <<[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"none">>],sends |-> [A |-> 1, B |-> 0],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 0, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"none">>],sends |-> [A |-> 1, B |-> 0],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<[from |-> "B", dh |-> -2, seq |-> 1, pn |-> 0, hk |-> -2, ackDh |-> -1, at |-> 0, token |-> <<"B", 0>>]>>],mbox |-> (<<"A", 0>> :> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {<<-2, 1>>}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"none">>],sends |-> [A |-> 1, B |-> 1],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 0, localDh |-> -2, remoteDh |-> -1, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"B", 0>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 0>>, hk |-> -1, nhk |-> -3, shk |-> -2, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<[from |-> "B", dh |-> -2, seq |-> 1, pn |-> 0, hk |-> -2, ackDh |-> -1, at |-> 0, token |-> <<"B", 0>>]>>],mbox |-> (<<"A", 0>> :> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {<<-2, 1>>}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]}],polledTok |-> [A |-> <<"none">>, B |-> <<"A", 0>>],sends |-> [A |-> 1, B |-> 1],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 2, localDh |-> 2, remoteDh |-> 1, sendSeq |-> 0, recvSeq |-> 1, pn |-> 1, pr |-> 0, sendTok |-> <<"B", 2>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 1>>, hk |-> -3, nhk |-> 2, shk |-> 1, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {<<1, 1>>}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {<<-2, 1>>}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"A", 0>>],sends |-> [A |-> 1, B |-> 1],toAck |-> [A |-> {}, B |-> {<<"A", 1, 1>>}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]]),
    ([silent |-> [A |-> 0, B |-> 0],ackForced |-> [A |-> FALSE, B |-> FALSE],session |-> [A |-> [root |-> 1, localDh |-> 1, remoteDh |-> -2, sendSeq |-> 1, recvSeq |-> 0, pn |-> 0, pr |-> 0, sendTok |-> <<"A", 1>>, prevSendTok |-> <<"A", 0>>, recvTok |-> <<"B", 0>>, hk |-> -2, nhk |-> 1, shk |-> -3, pshk |-> -1, skipped |-> {}], B |-> [root |-> 2, localDh |-> 2, remoteDh |-> 1, sendSeq |-> 0, recvSeq |-> 1, pn |-> 1, pr |-> 0, sendTok |-> <<"B", 2>>, prevSendTok |-> <<"B", 0>>, recvTok |-> <<"A", 1>>, hk |-> -3, nhk |-> 2, shk |-> 1, pshk |-> -2, skipped |-> {}]],flags |-> [doubleAccept |-> FALSE, badRatchet |-> FALSE, ackedUnprocessed |-> FALSE],accepted |-> [A |-> {}, B |-> {<<1, 1>>}],everPosted |-> {[from |-> "A", dh |-> 1, seq |-> 1, pn |-> 0, hk |-> -1, ackDh |-> -2, at |-> 0, token |-> <<"A", 0>>]},clock |-> 0,crashes |-> 0,losses |-> 0,posting |-> [A |-> <<"none">>, B |-> <<"none">>],outbox |-> [A |-> <<>>, B |-> <<>>],mbox |-> (<<"A", 0>> :> {} @@ <<"A", 1>> :> {} @@ <<"A", 2>> :> {} @@ <<"A", 3>> :> {} @@ <<"A", 4>> :> {} @@ <<"A", 5>> :> {} @@ <<"A", 6>> :> {} @@ <<"A", 7>> :> {} @@ <<"B", 0>> :> {} @@ <<"B", 1>> :> {} @@ <<"B", 2>> :> {} @@ <<"B", 3>> :> {} @@ <<"B", 4>> :> {} @@ <<"B", 5>> :> {} @@ <<"B", 6>> :> {} @@ <<"B", 7>> :> {}),sent |-> [A |-> {<<1, 1>>}, B |-> {<<-2, 1>>}],lostKeys |-> {},inflight |-> [A |-> {}, B |-> {}],polledTok |-> [A |-> <<"none">>, B |-> <<"A", 0>>],sends |-> [A |-> 1, B |-> 1],toAck |-> [A |-> {}, B |-> {}],dups |-> 0,urgent |-> [A |-> 0, B |-> 0]])
    >>
----


=============================================================================

---- CONFIG WhereSync_TTrace_1792359926 ----
CONSTANTS
    MaxSends = 1
    MaxUrgent = 0
    MaxCrashes = 0
    MaxLoss = 0
    MaxDup = 0
    MaxClock = 0
    TTL = 1
    SkipCap = 2
    ForceAckAfter = 2

PROPERTY
    _prop

CHECK_DEADLOCK
    \* CHECK_DEADLOCK off because of PROPERTY or INVARIANT above.
    FALSE

INIT
    _init

NEXT
    _next

CONSTANT
    _TETrace <- _trace

ALIAS
    _expression
=============================================================================
\* Generated on Sun Oct 18 21:45:28 UTC 2026