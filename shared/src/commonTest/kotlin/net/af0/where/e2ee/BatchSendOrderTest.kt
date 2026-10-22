package net.af0.where.e2ee

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A batch is processed in the peer's send order whatever order the server returns it in (§5.4.4):
 * the sharing state (§5.7.2) relies on it. The batch here spans the receiver's current epoch and
 * the next one, and includes frames a hostile server copied in from the next epoch's mailbox and a
 * duplicate.
 */
class BatchSendOrderTest {
    init {
        initializeE2eeTests()
    }

    private val now = 1_767_225_600_000L

    private fun paired(
        rngA: Rng,
        rngB: Rng,
    ): Pair<ProtocolCore.State, ProtocolCore.State> {
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
        frames: List<MailboxPayload>,
    ): ProtocolCore.Step {
        val mailbox = (state as ProtocolCore.State.Paired).session.recvToken.toHex()
        return ProtocolCore.step(state, ProtocolCore.Event.Receive(mailbox, frames), now, rng)
    }

    private fun ProtocolCore.Step.deliveredLats() =
        effects.filterIsInstance<ProtocolCore.Effect.Deliver>().map { (it.plaintext as MessagePlaintext.Location).lat }

    @Test
    fun `a batch spanning two epochs is delivered in send order however the server orders it`() {
        val rngA = DeterministicRng("batch-order/alice")
        val rngB = DeterministicRng("batch-order/bob")
        var (alice, bob) = paired(rngA, rngB)

        // Bob ratchets on Alice's first frame; Alice reads his transition frame, so his new epoch
        // (not the bootstrap one) is her current epoch and she polls its mailbox.
        val (alice1, a1) = send(alice, rngA, 0.0)
        alice = alice1
        bob = receive(bob, rngB, listOf(a1)).state
        val (bob1, b1) = send(bob, rngB, 0.0)
        bob = bob1
        alice = receive(alice, rngA, listOf(b1)).state

        // Bob sends three more in that epoch, then reads Alice's next frame and ratchets again:
        // seq 1 of his next epoch goes to the mailbox Alice polls, seq 2 and 3 to the next one.
        val sent = mutableListOf<MailboxPayload>()
        for (lat in listOf(1.0, 2.0, 3.0)) {
            val (next, frame) = send(bob, rngB, lat)
            bob = next
            sent += frame
        }
        val (alice2, a2) = send(alice, rngA, 0.0)
        alice = alice2
        bob = receive(bob, rngB, listOf(a2)).state
        for (lat in listOf(4.0, 5.0, 6.0)) {
            val (next, frame) = send(bob, rngB, lat)
            bob = next
            sent += frame
        }

        // The premise: the transition frame is on the mailbox Alice polls, the rest of that
        // epoch on the next one.
        val bobSession = (bob as ProtocolCore.State.Paired).session
        val polled = (alice as ProtocolCore.State.Paired).session.recvToken.toHex()
        assertEquals(polled, bobSession.prevSendToken.toHex())
        assertNotEquals(polled, bobSession.sendToken.toHex())

        // The server returns them, and a duplicate from each epoch, in any order.
        val batch = sent + sent[1] + sent[4]
        val shuffle = Random(0)
        repeat(SHUFFLES) {
            assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), receive(alice, rngA, batch.shuffled(shuffle)).deliveredLats())
        }
    }

    private companion object {
        const val SHUFFLES = 50
    }
}
