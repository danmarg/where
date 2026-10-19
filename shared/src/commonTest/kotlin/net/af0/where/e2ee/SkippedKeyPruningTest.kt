package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After each batch the receiver keeps only the cached message keys a later batch can reach
 * (E2eeProtocol.dropUnusableSkippedKeys; CachedKeysUsable in formal/tla/WhereSync.tla). The first
 * case is the TLC counterexample to that invariant; the other two are the keys that must stay.
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
    ): ProtocolCore.Step = ProtocolCore.step(state, ProtocolCore.Event.Receive(frames.toList()), now, rng)

    private fun ProtocolCore.State.skipped() = (this as ProtocolCore.State.Paired).session.skippedMessageKeys

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
    fun `seq 1 of the peer's current epoch is dropped, since it is sealed under the previous header key`() {
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
    fun `seq 1 of the peer's bootstrap epoch is kept, since it shares that epoch's header key`() {
        val (alice, bob) = paired()
        val (b1s, b1) = send(bob, rngB, 1.0)
        val (_, b2) = send(b1s, rngB, 2.0)

        val skipped = receive(alice, rngA, b2)
        assertEquals(listOf(2.0), skipped.deliveredLats())
        assertEquals(1, skipped.state.skipped().size, "Bob's bootstrap seq 1 is still reachable")

        val late = receive(skipped.state, rngA, b1)
        assertEquals(listOf(1.0), late.deliveredLats())
    }
}
