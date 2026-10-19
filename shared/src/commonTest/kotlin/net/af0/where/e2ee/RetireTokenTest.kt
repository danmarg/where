package net.af0.where.e2ee

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The RetireToken effect has to track the receive token exactly: a receive that changes the
 * committed recvToken emits exactly one RetireToken for the token it replaced, and one that
 * doesn't emits none. (LocationClient polls the saved token each round, so it follows a rotation
 * even without the effect; it uses the effect to count follows against MAX_TOKEN_FOLLOWS_PER_POLL.
 * Interpreters that act on the effect itself rely on this equivalence.)
 */
class RetireTokenTest {
    init {
        initializeE2eeTests()
    }

    private class Party(val name: String, var state: ProtocolCore.State, val rng: Rng)

    private fun ProtocolCore.State.session() = (this as ProtocolCore.State.Paired).session

    @Test
    fun `RetireToken is emitted exactly when the receive token changes, over random schedules`() {
        var receives = 0
        var rotations = 0
        repeat(150) { seed ->
            val random = Random(seed)
            val now = 1_767_225_600_000L
            val alice = Party("alice", ProtocolCore.State.Idle, DeterministicRng("retire/$seed/alice"))
            val bob = Party("bob", ProtocolCore.State.Idle, DeterministicRng("retire/$seed/bob"))
            alice.state = ProtocolCore.step(alice.state, ProtocolCore.Event.CreateInvite("Alice"), now, alice.rng).state
            val qr = (alice.state as ProtocolCore.State.Inviting).qr
            val scan = ProtocolCore.step(bob.state, ProtocolCore.Event.ScanQr(qr, "Bob"), now, bob.rng)
            bob.state = scan.state
            val init = (scan.effects.single() as ProtocolCore.Effect.Put).payload as KeyExchangeInitPayload
            alice.state = ProtocolCore.step(alice.state, ProtocolCore.Event.ProcessInit(init), now, alice.rng).state

            // The server: mailbox -> frames, with everything ever posted kept for re-serving.
            val mailboxes = mutableMapOf<String, MutableList<MailboxPayload>>()
            val everPosted = mutableListOf<Pair<String, MailboxPayload>>()

            repeat(60) {
                val (me, peer) = if (random.nextBoolean()) alice to bob else bob to alice
                when (random.nextInt(10)) {
                    in 0..3 -> {
                        val step = ProtocolCore.step(me.state, ProtocolCore.Event.Send(MessagePlaintext.Keepalive()), now, me.rng)
                        me.state = step.state
                        val put = step.effects.single() as ProtocolCore.Effect.Put
                        if (random.nextInt(8) != 0) { // sometimes lost
                            mailboxes.getOrPut(put.mailbox) { mutableListOf() }.add(put.payload)
                            everPosted.add(put.mailbox to put.payload)
                        }
                    }
                    9 ->
                        if (everPosted.isNotEmpty()) { // the server re-serves an old frame
                            val (mailbox, frame) = everPosted[random.nextInt(everPosted.size)]
                            mailboxes.getOrPut(mailbox) { mutableListOf() }.add(frame)
                        }
                    else -> {
                        val before = me.state.session().recvToken.toHex()
                        val waiting = mailboxes[before].orEmpty()
                        // A random subset in a random order, sometimes with a corrupted body.
                        val batch =
                            waiting.filter { random.nextInt(3) != 0 }.shuffled(random).map { frame ->
                                if (frame is EncryptedMessagePayload && random.nextInt(6) == 0) {
                                    frame.copy(ct = frame.ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
                                } else {
                                    frame
                                }
                            }
                        val step = ProtocolCore.step(me.state, ProtocolCore.Event.Receive(before, batch), now, me.rng)
                        me.state = step.state
                        receives++
                        val after = me.state.session().recvToken.toHex()
                        val retired = step.effects.filterIsInstance<ProtocolCore.Effect.RetireToken>()
                        if (after != before) {
                            rotations++
                            assertEquals(listOf(before), retired.map { it.mailbox }, "seed $seed: rotation without exactly one RetireToken")
                        } else {
                            assertTrue(retired.isEmpty(), "seed $seed: RetireToken without a rotation")
                        }
                        step.effects.filterIsInstance<ProtocolCore.Effect.Ack>().forEach { ack ->
                            assertEquals(before, ack.mailbox, "seed $seed: ack for another mailbox")
                            mailboxes[before]?.removeAll { it.msgId in ack.ids }
                        }
                    }
                }
            }
        }
        // The schedules must actually exercise rotations, or the property says nothing.
        assertTrue(rotations > 100, "only $rotations rotations in $receives receives")
    }

    @Test
    fun `an undeliverable transition frame is acked and the same poll continues on the new token`() =
        runTest {
            val mailbox = ChaosMailboxClient(MemoryMailboxClient())
            val aliceManager = testE2eeManager(createTestSqlDriver())
            val aliceClient = LocationClient("http://localhost", aliceManager, mailbox)
            val bobManager = testE2eeManager(createTestSqlDriver())
            val bobClient = LocationClient("http://localhost", bobManager, mailbox)
            val qr = aliceManager.createInvite("Alice")
            val (initPayload, bobEntry) = bobManager.processScannedQr(qr, "Bob")
            bobClient.postKeyExchangeInit(bobEntry.id, qr, initPayload)
            val pending = aliceClient.pollPendingInvites()
            val aliceFriend = requireNotNull(aliceManager.processKeyExchangeInit(pending[0].payload, "Bob", pending[0].inviteEkPub))

            // Alice's transition frame (on Bob's current mailbox) and a second frame (on the next).
            aliceManager.encryptAndAdvance(aliceFriend.id, MessagePlaintext.Location(10.0, 20.0, 1.0, 1L))
            aliceManager.encryptAndAdvance(aliceFriend.id, MessagePlaintext.Location(11.0, 21.0, 1.0, 2L))
            aliceClient.syncNow()
            val before = requireNotNull(bobManager.getFriend(bobEntry.id)).session.recvToken

            // The transition frame's body is corrupted in transit: undeliverable, but its header
            // authenticates, so Bob ratchets, rotates his token, and must poll the new one.
            mailbox.corruptNextPayloadOnly = true
            val got = bobClient.poll(isForeground = true, pausedFriendIds = emptySet())

            assertTrue(requireNotNull(bobManager.getFriend(bobEntry.id)).session.recvToken != before, "Bob didn't rotate")
            assertEquals(listOf(11.0), got.map { it.lat }, "Bob didn't follow the rotation to Alice's second frame")
        }
}
