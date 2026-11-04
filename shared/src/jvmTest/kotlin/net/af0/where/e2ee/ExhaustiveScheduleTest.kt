package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.fail

/**
 * Exhaustive small-scope check of [ProtocolCore] under every fault schedule: a breadth-first
 * search over the interleavings of two paired parties' sends, outbox posts, polls and a handful of
 * faults, up to a depth bound, deduplicating on the full protocol state. It runs the real code (no
 * separate model to drift), which is deterministic given its injected clock and [Rng].
 *
 * Transport rules are those of [ScheduleInterleavingPropertyTest]: the outbox drains in order and a
 * new message is created only when it is empty; the server keeps frames per mailbox token in
 * arrival order, stores a PUT once per msgId and serves a page of 50; a receiver polls only its
 * current recvToken; an epoch's transition frame (seq 1) is never lost (§5.4.5 accepts that as
 * unrecoverable), though it can be corrupted. Actions:
 *
 *  - send (up to [SENDS] per party), post the outbox head (with or without clearing it: a crash
 *    between the PUT and the outbox delete), swap two adjacent same-epoch frames, the server loses
 *    a frame, a frame's body is corrupted (header intact, so it soft-fails), the clock jumps past
 *    the skipped-key age, a poll handing over the whole batch, reversed, or just its first or last
 *    frame (the server serves a prefix of arrival order, never a suffix), with the ACK either landing or lost (a crash before it).
 *
 * Safety is checked after every step: nothing delivered twice or that wasn't sent to that party;
 * a replay changes nothing; no step throws; the skipped-key cache stays
 * bounded. Liveness is checked at every reachable state: with the faults switched off, both
 * outboxes drain and both mailboxes are read until quiet, after which a fresh message each way
 * must get through, a second round must too, and every message that reached the
 * server intact (and wasn't left undeliverable by a clock jump) must have been delivered exactly
 * once. A state from which that fails is a session that stays broken (the shape of #416).
 *
 * Bounds: `-Dschedule.depth=N` (default [DEFAULT_DEPTH]; CI runs the default, a nightly job a
 * deeper one). A violation prints its action sequence; `-Dschedule.maxStates` caps the search.
 */
class ExhaustiveScheduleTest {
    init {
        initializeE2eeTests()
    }

    @Test
    fun everyScheduleKeepsDeliveryInvariantsAndRecovers() {
        val depth = System.getProperty("schedule.depth")?.toInt() ?: DEFAULT_DEPTH
        val maxStates = System.getProperty("schedule.maxStates")?.toInt() ?: 2_000_000
        val result = Search(depth, maxStates).run()
        println("exhaustive schedules: depth $depth, ${result.states} states, ${result.drains} liveness drains")
        result.violation?.let { fail(it) }
    }

    private class Violation(message: String) : Exception(message)

    private data class Out(val mailbox: String, val msgId: String, val number: Int)

    private data class Frame(val mailbox: String, val msgId: String, val number: Int, val corrupt: Boolean = false)

    private data class World(
        val parties: List<ProtocolCore.State>,
        val outbox: List<List<Out>>,
        val server: List<Frame>,
        val senderOf: Map<Int, Int>,
        val seqOf: Map<Int, Long>,
        val delivered: List<Set<Int>>,
        val lost: Set<Int>,
        val corrupted: Set<Int>,
        val atRisk: Set<Int>,
        val now: Long,
        val sends: List<Int>,
        val ticks: Int,
        val faults: Int,
    ) {
        val created get() = senderOf.size
    }

    private class Node(val world: World, val parent: Node?, val action: String)

    private class Result(val states: Int, val drains: Int, val violation: String?)

    private enum class Mode { ALL, REVERSED, FIRST }

    private class Search(val depth: Int, val maxStates: Int) {
        /** Every payload ever put on the wire, by msgId. */
        val payloads = HashMap<String, EncryptedMessagePayload>()
        var drains = 0
        var lastReport: ProtocolCore.ReceiveReport? = null

        fun run(): Result {
            val seen = HashSet<World>()
            var frontier = listOf(Node(initial(), null, "start"))
            seen += frontier[0].world
            var states = 1
            try {
                check(frontier[0], liveness = true)
                for (level in 1..depth) {
                    val next = mutableListOf<Node>()
                    for (node in frontier) {
                        for ((label, act) in actions(node.world)) {
                            val child = Node(act(), node, label)
                            if (!seen.add(child.world)) continue
                            check(child, liveness = true)
                            next += child
                            if (++states > maxStates) throw Violation("state cap $maxStates reached at depth $level; lower the depth")
                        }
                    }
                    frontier = next
                }
            } catch (v: Violation) {
                return Result(states, drains, v.message)
            }
            return Result(states, drains, null)
        }

        /** Pairing done by the golden-transcript script's first three steps; Alice is parties[0]. */
        fun initial(): World {
            val ra = DeterministicRng("enumerator/alice")
            val rb = DeterministicRng("enumerator/bob")
            val now = 1_767_225_600_000L
            val invite = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.CreateInvite("Alice"), now, ra)
            val qr = (invite.state as ProtocolCore.State.Inviting).qr
            val scan = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.ScanQr(qr, "Bob"), now, rb)
            val init = (scan.effects.single() as ProtocolCore.Effect.Put).payload as KeyExchangeInitPayload
            val alice = ProtocolCore.step(invite.state, ProtocolCore.Event.ProcessInit(init), now, ra)
            return World(
                parties = listOf(alice.state, scan.state),
                outbox = listOf(emptyList(), emptyList()),
                server = emptyList(),
                senderOf = emptyMap(),
                seqOf = emptyMap(),
                delivered = listOf(emptySet(), emptySet()),
                lost = emptySet(),
                corrupted = emptySet(),
                atRisk = emptySet(),
                now = now,
                sends = listOf(0, 0),
                ticks = 0,
                faults = 0,
            )
        }

        fun names(p: Int) = if (p == 0) "A" else "B"

        fun session(
            w: World,
            p: Int,
        ) = (w.parties[p] as ProtocolCore.State.Paired).session

        /** Randomness is a function of the party's state and the time, so equal states stay equal. */
        fun rngFor(
            w: World,
            p: Int,
        ) = DeterministicRng("enumerator/${names(p)}/${session(w, p).hashCode()}/${w.now}")

        fun actions(w: World): List<Pair<String, () -> World>> {
            val out = mutableListOf<Pair<String, () -> World>>()
            for (p in 0..1) {
                if (w.sends[p] < SENDS && w.outbox[p].isEmpty()) out += "send ${names(p)}" to { send(w, p) }
                if (w.outbox[p].isNotEmpty()) {
                    out += "post ${names(p)}" to { post(w, p, clear = true) }
                    out += "post ${names(p)} (crash before outbox delete)" to { post(w, p, clear = false) }
                }
                val batch = batchFor(w, p)
                if (batch.isNotEmpty()) {
                    for (mode in Mode.entries) {
                        if (batch.size == 1 && mode != Mode.ALL) continue
                        for (ackLost in listOf(false, true)) {
                            val label = "poll ${names(p)} $mode" + if (ackLost) " (ack lost)" else ""
                            out += label to { poll(w, p, pick(batch, mode), ackLost) }
                        }
                    }
                }
            }
            if (w.faults < FAULTS) {
                w.server.forEachIndexed { i, f ->
                    if (w.seqOf[f.number] != 1L) out += "server loses #${f.number}" to { lose(w, i) }
                    if (!f.corrupt) out += "corrupt #${f.number}" to { corrupt(w, i) }
                    val n = w.server.getOrNull(i + 1)
                    if (n != null && n.mailbox == f.mailbox && w.seqOf[f.number] != 1L && w.seqOf[n.number] != 1L) {
                        out += "swap #${f.number} #${n.number}" to { swap(w, i) }
                    }
                }
            }
            if (w.ticks < TICKS) out += "clock jumps past skipped-key age" to { tick(w) }
            return out
        }

        fun batchFor(
            w: World,
            p: Int,
        ): List<Frame> {
            val token = session(w, p).recvToken.toHex()
            return w.server.filter { it.mailbox == token }.take(PAGE)
        }

        fun pick(
            batch: List<Frame>,
            mode: Mode,
        ) = when (mode) {
            Mode.ALL -> batch
            Mode.REVERSED -> batch.reversed()
            Mode.FIRST -> listOf(batch.first())
        }

        fun send(
            w: World,
            p: Int,
        ): World {
            val number = w.created + 1
            val loc = MessagePlaintext.Location(lat = number.toDouble(), lng = 0.0, acc = 1.0, ts = number.toLong())
            val step =
                try {
                    ProtocolCore.step(w.parties[p], ProtocolCore.Event.Send(loc), w.now, rngFor(w, p))
                } catch (e: Exception) {
                    throw Violation("send threw $e")
                }
            val put = step.effects.single() as ProtocolCore.Effect.Put
            val payload = put.payload as EncryptedMessagePayload
            payloads[payload.msgId] = payload
            return w.copy(
                parties = w.parties.replace(p, step.state),
                outbox = w.outbox.replace(p, w.outbox[p] + Out(put.mailbox, payload.msgId, number)),
                senderOf = w.senderOf + (number to p),
                seqOf = w.seqOf + (number to (step.state as ProtocolCore.State.Paired).session.sendSeq),
                sends = w.sends.replace(p, w.sends[p] + 1),
            )
        }

        fun post(
            w: World,
            p: Int,
            clear: Boolean,
        ): World {
            val head = w.outbox[p].first()
            val stored = w.server.any { it.mailbox == head.mailbox && it.msgId == head.msgId }
            return w.copy(
                server = if (stored) w.server else w.server + Frame(head.mailbox, head.msgId, head.number),
                outbox = if (clear) w.outbox.replace(p, w.outbox[p].drop(1)) else w.outbox,
            )
        }

        fun lose(
            w: World,
            i: Int,
        ) = w.copy(server = w.server.filterIndexed { j, _ -> j != i }, lost = w.lost + w.server[i].number, faults = w.faults + 1)

        fun corrupt(
            w: World,
            i: Int,
        ) = w.copy(
            server = w.server.mapIndexed { j, f -> if (j == i) f.copy(corrupt = true) else f },
            corrupted = w.corrupted + w.server[i].number,
            faults = w.faults + 1,
        )

        fun swap(
            w: World,
            i: Int,
        ) = w.copy(
            server =
                w.server.toMutableList().also {
                    it[i] = w.server[i + 1]
                    it[i + 1] = w.server[i]
                },
            faults = w.faults + 1,
        )

        fun tick(w: World): World {
            // Whatever hasn't been delivered yet may find its skipped key purged: don't demand it.
            val undelivered = w.senderOf.keys.filter { n -> n !in w.delivered[1 - w.senderOf.getValue(n)] }
            return w.copy(now = w.now + MAX_KEY_AGE_MS + 1, ticks = w.ticks + 1, atRisk = w.atRisk + undelivered)
        }

        fun wire(f: Frame): EncryptedMessagePayload {
            val original = payloads.getValue(f.msgId)
            if (!f.corrupt) return original
            val ct = original.ct.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            return EncryptedMessagePayload(v = original.v, envelope = original.envelope, ct = ct, msgId = original.msgId)
        }

        fun poll(
            w: World,
            p: Int,
            frames: List<Frame>,
            ackLost: Boolean,
        ): World {
            val before = session(w, p)
            val mailbox = before.recvToken.toHex()
            val step =
                try {
                    ProtocolCore.step(w.parties[p], ProtocolCore.Event.Receive(mailbox, frames.map { wire(it) }), w.now, rngFor(w, p))
                } catch (e: Exception) {
                    throw Violation("poll ${names(p)} of ${frames.map { it.number }} threw $e")
                }
            lastReport = step.report
            var next = w.copy(parties = w.parties.replace(p, step.state))
            val deliveredNow = mutableListOf<Int>()
            for (effect in step.effects) {
                when (effect) {
                    is ProtocolCore.Effect.Deliver -> {
                        val n = (effect.plaintext as MessagePlaintext.Location).lat.toInt()
                        deliveredNow += n
                        if (w.senderOf[n] != 1 - p) throw Violation("${names(p)} delivered #$n, which ${names(1 - p)} never sent to it")
                        if (n in next.delivered[p]) throw Violation("${names(p)} delivered #$n twice")
                        next = next.copy(delivered = next.delivered.replace(p, next.delivered[p] + n))
                    }
                    is ProtocolCore.Effect.Ack ->
                        if (!ackLost) {
                            next = next.copy(server = next.server.filterNot { it.mailbox == effect.mailbox && it.msgId in effect.ids })
                        }
                    is ProtocolCore.Effect.CancelPut ->
                        next = next.copy(outbox = next.outbox.replace(p, next.outbox[p].filterNot { it.mailbox == effect.mailbox }))
                    is ProtocolCore.Effect.RetireToken -> {}
                    is ProtocolCore.Effect.Put -> throw Violation("a receive emitted a Put")
                }
            }
            val after = session(next, p)
            if (after.skippedMessageKeys.size > MAX_SKIPPED_KEYS) throw Violation("skipped keys ${after.skippedMessageKeys.size}")
            if (frames.all { it.number in w.delivered[p] && !it.corrupt } && (deliveredNow.isNotEmpty() || after != before)) {
                throw Violation("${names(p)}: replay of ${frames.map { it.number }} changed the session or delivered $deliveredNow")
            }
            return next
        }

        // ---- checks ----

        fun check(
            node: Node,
            liveness: Boolean,
        ) {
            try {
                if (liveness) drainAndCheck(node.world)
            } catch (v: Violation) {
                val trace = generateSequence(node) { it.parent }.map { it.action }.toList().reversed()
                throw Violation(
                    "${v.message}\nschedule (${trace.size - 1} actions):\n" +
                        trace.mapIndexed {
                                i,
                                a,
                            ->
                            "  $i. $a"
                        }.joinToString("\n"),
                )
            }
        }

        /** Fair drain: no more faults; post everything in order and read every mailbox until quiet. */
        fun quiesce(start: World): World {
            var w = start
            repeat(MAX_ROUNDS) {
                var progress = false
                for (p in 0..1) {
                    while (w.outbox[p].isNotEmpty()) {
                        w = post(w, p, clear = true)
                        progress = true
                    }
                }
                for (p in 0..1) {
                    val batch = batchFor(w, p)
                    if (batch.isNotEmpty()) {
                        // A corrupted frame that fails without authenticating is never ACKed (it stays
                        // until the server's TTL), so a poll that changes nothing is not progress.
                        val after = poll(w, p, batch, ackLost = false)
                        if (after != w) progress = true
                        w = after
                    }
                }
                if (!progress) return w
            }
            throw Violation(
                "no quiescence after $MAX_ROUNDS rounds; server=${w.server.map {
                    "#${it.number}@${it.mailbox.take(
                        6,
                    )}${if (it.corrupt) "!" else ""}"
                }}, recv=${(0..1).map {
                    session(
                        w,
                        it,
                    ).recvToken.toHex().take(6)
                }}, last report=$lastReport",
            )
        }

        /** After a fair drain only a corrupted frame may still sit in a mailbox somebody polls. */
        fun requireNoCleanResidue(w: World) {
            for (p in 0..1) {
                val stuck = batchFor(w, p).filter { !it.corrupt }
                if (stuck.isNotEmpty()) {
                    throw Violation(
                        "${names(p)}'s mailbox still holds intact, unACKed ${stuck.map { "#" + it.number }} after a fair drain",
                    )
                }
            }
        }

        fun drainAndCheck(start: World) {
            drains++
            var w = quiesce(start)
            requireNoCleanResidue(w)
            val freshA = w.created + 1
            w = send(w, 0)
            val freshB = w.created + 1
            w = send(w, 1)
            w = quiesce(w)
            if (freshA !in w.delivered[1]) throw Violation("after a fair drain, a fresh message A->B (#$freshA) was not delivered")
            if (freshB !in w.delivered[0]) throw Violation("after a fair drain, a fresh message B->A (#$freshB) was not delivered")
            // A second round each way: the epoch the first round settled into must carry traffic too.
            val againA = w.created + 1
            w = send(w, 0)
            val againB = w.created + 1
            w = send(w, 1)
            w = quiesce(w)
            if (againA !in w.delivered[1] || againB !in w.delivered[0]) {
                throw Violation(
                    "a second fresh round after a fair drain was not delivered",
                )
            }
            for ((n, from) in w.senderOf) {
                val to = 1 - from
                val excused = n in w.lost || n in w.corrupted || n in w.atRisk
                if (!excused && n !in w.delivered[to]) {
                    throw Violation("message #$n (from ${names(from)}) was never delivered although it reached the server intact")
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_DEPTH = 8
        const val SENDS = 3
        const val FAULTS = 2
        const val TICKS = 1
        const val PAGE = 50
        const val MAX_ROUNDS = 40

        fun <T> List<T>.replace(
            i: Int,
            v: T,
        ): List<T> = mapIndexed { j, x -> if (j == i) v else x }
    }
}
