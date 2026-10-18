package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.EncryptedMessagePayload
import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.ReceivedMessage
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.SessionState

/**
 * A paired session, built once per JVM, with Bob's receive state fixed partway through a
 * conversation: Alice's first epoch is part-received with a skipped key cached, and Alice has
 * since ratcheted. [frames] are genuine frames from Alice that each decrypt against [bob] by a
 * different path (cached key, in order, gap, new epoch).
 */
internal object SessionFixture {
    val bob: SessionState

    /** Alice before her ratchet, for authenticating arbitrary plaintexts as her next frame. */
    val aliceFirstEpoch: SessionState

    val frames: List<EncryptedMessagePayload>

    /** The header each of [frames] carries, as Bob decrypts it. */
    val headers: List<Session.DecryptedHeader>

    init {
        Libsodium.ensure()
        val (qr, aliceEkPriv) = KeyExchange.aliceCreateQrPayload("Alice")
        val (init, bob0) = KeyExchange.bobProcessQr(qr, "Bob")
        var alice = KeyExchange.aliceProcessInit(init, aliceEkPriv, qr.ekPub.toDhPublicKey())

        val first =
            (1..6).map { i ->
                val out = Session.encryptMessage(alice, MessagePlaintext.Location(1.0, 2.0, 3.0, i.toLong()))
                alice = out.state
                out.payload
            }
        aliceFirstEpoch = alice
        var b = bob0
        for (i in listOf(0, 2)) b = delivered(b, first[i])
        val reply = Session.encryptMessage(b, MessagePlaintext.Keepalive())
        bob = reply.state
        alice = delivered(alice, reply.payload)
        val second =
            (1..2).map {
                val out = Session.encryptMessage(alice, MessagePlaintext.StoppedSharing(it.toLong()))
                alice = out.state
                out.payload
            }

        // Skipped key (seq 2), next in order (4), after a gap (6), the new epoch's first two.
        frames = listOf(first[1], first[3], first[5], second[0], second[1])
        headers =
            frames.map { f ->
                bob.recvHeaderKeys().firstNotNullOf { key ->
                    runCatching { Session.decryptHeader(key, f.envelope, bob.headerAad()) }.getOrNull()
                }
            }
        for (f in frames) delivered(bob, f)
    }

    private fun delivered(
        state: SessionState,
        frame: EncryptedMessagePayload,
    ): SessionState =
        when (val r = Session.decryptMessage(state, frame)) {
            is ReceivedMessage.Delivered -> r.state
            else -> error("fixture frame did not decrypt: $r")
        }
}
