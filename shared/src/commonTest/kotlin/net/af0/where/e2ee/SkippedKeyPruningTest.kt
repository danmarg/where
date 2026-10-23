@file:OptIn(RawKeyAccess::class)

package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * After each batch the receiver keeps only the cached message keys a later batch can reach
 * (E2eeProtocol.dropUnusableSkippedKeys). The first case is a counterexample found by model
 * checking (the TLA+ model on the `claude/sync-model` branch); the second is the other key that
 * must go, and the next two are the keys that must stay. The rest check which keys a prune wipes
 * and that seq 1 is kept only in the peer's real bootstrap epoch.
 */
class SkippedKeyPruningTest {
    init {
        initializeE2eeTests()
    }

    private val now = 1_767_225_600_000L
    private val rngA = DeterministicRng("pruning/alice")
    private val rngB = DeterministicRng("pruning/bob")

    private fun paired(): Pair<ProtocolCore.State, ProtocolCore.State> {
        val inviting = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.CreateInvite("Alice"), now, rngA).state
        val qr = (inviting as ProtocolCore.State.Inviting).qr
        val scan = ProtocolCore.step(ProtocolCore.State.Idle, ProtocolCore.Event.ScanQr(qr, "Bob"), now, rngB)
        val init = (scan.effects.single() as ProtocolCore.Effect.Put).payload as KeyExchangeInitPayload
        val alice = ProtocolCore.step(inviting, ProtocolCore.Event.ProcessInit(init), now, rngA).state
        return alice to scan.state
    }

    private fun send(
        state: ProtocolCore.State,
        rng: Rng,
        lat: Double,
    ): Pair<ProtocolCore.State, MailboxPayload> {
        val step = ProtocolCore.step(state, ProtocolCore.Event.Send(MessagePlaintext.Location(lat, 0.0, 1.0, now / 1000)), now, rng)
        return step.state to (step.effects.single() as ProtocolCore.Effect.Put).payload
    }

    private fun receive(
        state: ProtocolCore.State,
        rng: Rng,
        vararg frames: MailboxPayload,
    ): ProtocolCore.Step {
        val mailbox = (state as ProtocolCore.State.Paired).session.recvToken.toHex()
        return ProtocolCore.step(state, ProtocolCore.Event.Receive(mailbox, frames.toList()), now, rng)
    }

    private fun ProtocolCore.State.skipped() = (this as ProtocolCore.State.Paired).session.skippedMessageKeys

    private fun SkippedMessageKey.material() = messageKey.bytes + nonce.bytes

    private fun SkippedMessageKey.isWiped() = material().all { it == 0.toByte() }

    private fun Map<String, SkippedMessageKey>.snapshot() = mapValues { it.value.material() }

    private fun key(fill: Int): SkippedMessageKey {
        val b = fill.toByte()
        return SkippedMessageKey(MessageKey(ByteArray(KEY_SIZE) { b }), MessageNonce(ByteArray(MESSAGE_NONCE_SIZE) { b }), now)
    }

    private fun ProtocolCore.Step.deliveredLats() =
        effects.filterIsInstance<ProtocolCore.Effect.Deliver>().map { (it.plaintext as MessagePlaintext.Location).lat }

    @Test
    fun `the previous chain's gap key is dropped once its header key is gone`() {
        var (alice, bob) = paired()
        // Bob's first frame (his bootstrap epoch) is lost.
        val (bob1, lost) = send(bob, rngB, 1.0)
        bob = bob1
        // Bob ratchets on Alice's first frame; his next frame is in a new epoch with pn = 1.
        val (alice1, a1) = send(alice, rngA, 10.0)
        alice = alice1
        bob = receive(bob, rngB, a1).state
        val (bob2, b2) = send(bob, rngB, 2.0)
        bob = bob2

        // Alice ratchets on it and fills the gap for the lost frame, but no later batch can open
        // that frame: it is sealed under a header key she no longer holds. So nothing is kept.
        val r = receive(alice, rngA, b2)
        assertEquals(listOf(2.0), r.deliveredLats())
        assertTrue(r.state.skipped().isEmpty(), "kept an unusable key: ${r.state.skipped().keys}")

        // And the frame really is unreachable: a late copy isn't even opened.
        val late = receive(r.state, rngA, lost)
        assertTrue(late.deliveredLats().isEmpty())
        assertTrue(late.effects.none { it is ProtocolCore.Effect.Ack }, "an unopenable frame isn't acked")
    }

    @Test
    fun `seq 1 of the peer's current epoch is dropped since it is sealed under the previous header key`() {
        var (alice, bob) = paired()
        val (a1s, lost) = send(alice, rngA, 1.0)
        val (_, a2) = send(a1s, rngA, 2.0)

        // Alice's transition frame is lost; Bob ratchets on her second one and fills seq 1.
        val r = receive(bob, rngB, a2)
        assertEquals(listOf(2.0), r.deliveredLats())
        assertTrue(r.state.skipped().isEmpty(), "kept an unusable key: ${r.state.skipped().keys}")

        val late = receive(r.state, rngB, lost)
        assertTrue(late.deliveredLats().isEmpty())
        assertTrue(late.effects.none { it is ProtocolCore.Effect.Ack }, "an unopenable frame isn't acked")
    }

    @Test
    fun `a gap key in the peer's current epoch is kept and used later`() {
        var (alice, bob) = paired()
        val (a1s, a1) = send(alice, rngA, 1.0)
        val (a2s, a2) = send(a1s, rngA, 2.0)
        val (_, a3) = send(a2s, rngA, 3.0)
        bob = receive(bob, rngB, a1).state

        val skipped = receive(bob, rngB, a3)
        assertEquals(listOf(3.0), skipped.deliveredLats())
        assertEquals(1, skipped.state.skipped().size, "seq 2 of the current epoch is still reachable")

        val late = receive(skipped.state, rngB, a2)
        assertEquals(listOf(2.0), late.deliveredLats())
        assertTrue(late.state.skipped().isEmpty())
    }

    @Test
    fun `seq 1 of the peer's bootstrap epoch is kept since it shares that epoch's header key`() {
        val (alice, bob) = paired()
        val (b1s, b1) = send(bob, rngB, 1.0)
        val (_, b2) = send(b1s, rngB, 2.0)

        val skipped = receive(alice, rngA, b2)
        assertEquals(listOf(2.0), skipped.deliveredLats())
        assertEquals(1, skipped.state.skipped().size, "Bob's bootstrap seq 1 is still reachable")

        val late = receive(skipped.state, rngA, b1)
        assertEquals(listOf(1.0), late.deliveredLats())
    }

    @Test
    fun `a prune wipes only the dropped keys this batch owns`() {
        val (_, bob) = paired()
        val input = (bob as ProtocolCore.State.Paired).session
        val current = input.remoteDhPub.toHex()
        val old = "00".repeat(32)
        val inputOld = key(1)
        val inputCurrent = key(2)
        val copiedOld = key(3)
        val newOld = key(4)
        val newCurrent = key(5)
        val inputKeys = mapOf("$old:2" to inputOld, "$current:2" to inputCurrent, "$old:3" to copiedOld)
        val before = inputKeys.snapshot()
        // A synthetic batch state with every kind of entry at once (a real ratchet copies all the
        // input's keys): the input's own old key, a ratchet's copy of another, and keys derived in
        // this batch, old and current. Only this test pins the reference check on its own.
        val session =
            input.copy(
                skippedMessageKeys =
                    mapOf(
                        "$old:2" to inputOld,
                        "$current:2" to inputCurrent,
                        "$old:3" to copiedOld.copyOf(),
                        "$old:4" to newOld,
                        "$current:3" to newCurrent,
                    ),
            )

        val pruned = E2eeProtocol.dropUnusableSkippedKeys(session, input.copy(skippedMessageKeys = inputKeys))

        assertEquals(setOf("$current:2", "$current:3"), pruned.skippedMessageKeys.keys)
        assertTrue(session.skippedMessageKeys.getValue("$old:3").isWiped(), "the ratchet's copy is ours to wipe")
        assertTrue(newOld.isWiped(), "a dropped key derived in this batch is wiped")
        assertFalse(newCurrent.isWiped(), "a kept key is intact")
        assertFalse(inputCurrent.isWiped(), "a kept key is intact")
        inputKeys.forEach { (id, k) -> assertTrue(before.getValue(id).contentEquals(k.material()), "input's $id was changed") }
    }

    @Test
    fun `a batch that ratchets leaves the input state's cached keys intact`() {
        var (alice, bob) = paired()
        val sent = mutableListOf<MailboxPayload>()
        repeat(3) {
            val (next, frame) = send(alice, rngA, it + 1.0)
            alice = next
            sent += frame
        }
        bob = receive(bob, rngB, sent[0], sent[2]).state
        assertEquals(1, bob.skipped().size, "Bob caches seq 2 of Alice's epoch")
        val before = bob.skipped().snapshot()

        // Alice ratchets on Bob's reply. Her transition frame and seq 3 are lost; Bob reads seq 2
        // and 4, so he drops the old epoch's cached key and his new gap for seq 1, and keeps seq 3.
        val (_, b1) = send(bob, rngB, 0.0)
        alice = receive(alice, rngA, b1).state
        val next = mutableListOf<MailboxPayload>()
        repeat(4) {
            val (s, frame) = send(alice, rngA, it + 11.0)
            alice = s
            next += frame
        }
        val r = receive(bob, rngB, next[1], next[3])
        assertEquals(listOf(12.0, 14.0), r.deliveredLats())
        assertEquals(1, r.state.skipped().size, "only seq 3 of the new epoch is kept")

        // The input state is untouched and still works (the ratchet's deep copy and the prune's
        // reference check both protect it); the kept key opens its frame and the dropped gap's
        // frame no longer opens.
        bob.skipped().forEach { (id, k) -> assertTrue(before.getValue(id).contentEquals(k.material()), "input's $id was changed") }
        assertEquals(listOf(2.0), receive(bob, rngB, sent[1]).deliveredLats())
        assertEquals(listOf(13.0), receive(r.state, rngB, next[2]).deliveredLats())
        val late = receive(r.state, rngB, next[0])
        assertTrue(late.deliveredLats().isEmpty())
        assertTrue(late.effects.none { it is ProtocolCore.Effect.Ack }, "an unopenable frame isn't acked")
    }

    @Test
    fun `seq 1 is kept only in the peer's bootstrap epoch however many ratchets follow`() {
        var (alice, bob) = paired()
        // Bootstrap epoch: Bob's seq 1 is lost, seq 2 arrives, and seq 1 stays reachable.
        val (b1s, b1) = send(bob, rngB, 1.0)
        val (b2s, b2) = send(b1s, rngB, 2.0)
        bob = b2s
        val first = receive(alice, rngA, b2)
        assertEquals(1, first.state.skipped().size, "bootstrap seq 1 is kept")
        alice = receive(first.state, rngA, b1).state
        assertTrue(alice.skipped().isEmpty())

        // Each later epoch of Bob's: its seq 1 restarts the counter but is sealed under a header
        // key Alice has dropped, so its gap key must go.
        repeat(3) { round ->
            val (a, fromAlice) = send(alice, rngA, 100.0 + round)
            alice = a
            bob = receive(bob, rngB, fromAlice).state
            val (s1, lost) = send(bob, rngB, 10.0 * (round + 1) + 1)
            val (s2, second) = send(s1, rngB, 10.0 * (round + 1) + 2)
            bob = s2
            val r = receive(alice, rngA, second)
            assertEquals(listOf(10.0 * (round + 1) + 2), r.deliveredLats())
            assertTrue(r.state.skipped().isEmpty(), "round $round kept seq 1 of a non-bootstrap epoch: ${r.state.skipped().keys}")
            alice = r.state
            val late = receive(alice, rngA, lost)
            assertTrue(late.deliveredLats().isEmpty())
            assertTrue(late.effects.none { it is ProtocolCore.Effect.Ack }, "an unopenable frame isn't acked")
        }
    }
}
