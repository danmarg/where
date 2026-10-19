package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the golden transcript against the spec, not against the implementation that produced it.
 * GoldenTranscriptTest shows this checkout reproduces the file; this shows the file is right.
 *
 * The key schedule is recomputed from the formulas in docs/e2ee-location-sync.md (§4.2, §4.4, §8.3)
 * with the raw primitives only (X25519, SHA-256, HMAC-SHA-256, all Wycheproof-tested) and the info
 * strings as the spec writes them, so neither the implementation's HKDF, KDF helpers nor its Label
 * constants are trusted. Behavioural properties are checked over every step: what is delivered is
 * what the peer sent, once; RetireToken accompanies exactly the token changes; acks go to the
 * mailbox that was polled.
 */
class SpecConformanceTest {
    init {
        initializeE2eeTests()
    }

    private val transcript = Json.parseToJsonElement(VectorFiles.read(GoldenTranscript.FILE)).jsonObject
    private val steps = transcript.getValue("steps").jsonArray.map { it.jsonObject }

    // ---- the spec's primitives, written out here ----

    /** RFC 5869 HKDF-SHA-256 over raw HMAC; a null salt is 32 zero bytes. */
    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmacSha256(salt ?: ByteArray(32), ikm)
        val out = ArrayList<Byte>()
        var t = ByteArray(0)
        var i = 1
        while (out.size < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            t.forEach { out.add(it) }
            i++
        }
        return out.take(length).toByteArray()
    }

    private fun utf8(s: String) = s.encodeToByteArray()

    /** §8.3 Routing Token Derivation (and §4.4 for the initial ones, from SK). */
    private fun token(
        ikm: ByteArray,
        senderFp: ByteArray,
        recipientFp: ByteArray,
    ) = hkdf(ikm, null, utf8("Where-v1-RoutingToken") + senderFp + recipientFp, 16)

    private class RkStep(val root: ByteArray, val chain: ByteArray, val header: ByteArray)

    /** §8.3 KDF_RK. */
    private fun kdfRk(
        root: ByteArray,
        dh: ByteArray,
    ): RkStep {
        val out = hkdf(dh, root, utf8("Where-v1-RatchetStep"), 96)
        return RkStep(out.copyOfRange(0, 32), out.copyOfRange(32, 64), out.copyOfRange(64, 96))
    }

    /** §8.3 KDF_CK, n times: the chain key after n messages. */
    private fun chainAfter(
        chain: ByteArray,
        n: Long,
    ): ByteArray {
        var ck = chain
        repeat(n.toInt()) { ck = hmacSha256(ck, byteArrayOf(0x02)) }
        return ck
    }

    // ---- reading the transcript ----

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.hex(key: String) = str(key).hexToByteArray()

    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.long

    private fun JsonObject.state() = getValue("state").jsonObject

    private fun JsonObject.event() = getValue("event").jsonObject

    private fun JsonObject.effects() = getValue("effects").jsonArray.map { it.jsonObject }

    private val basePoint = byteArrayOf(9) + ByteArray(31)

    private fun be64(v: Long) = ByteArray(8) { i -> (v shr (56 - 8 * i)).toByte() }

    private fun be64(
        b: ByteArray,
        at: Int,
    ) = (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xff) }

    /** §9.1.2 de-padding: the plaintext ends at the 0x80 before the trailing zeros. */
    private fun unpad(b: ByteArray): ByteArray {
        val end = b.indexOfLast { it != 0.toByte() }
        check(end >= 0 && b[end] == 0x80.toByte()) { "bad padding" }
        return b.copyOfRange(0, end)
    }

    private fun assertHex(
        expected: ByteArray,
        actual: String,
        what: String,
    ) = assertEquals(expected.toHex(), actual, what)

    // ---- the checks ----

    @Test
    fun `the handshake follows spec 4_2 and 4_4`() {
        val invite = steps[0].state()
        assertEquals("inviting", invite.str("type"))
        assertHex(
            hkdf(invite.hex("discovery_secret"), ByteArray(32), utf8("Where-v1-Discovery"), 32).copyOfRange(0, 16),
            invite.str("discovery_token"),
            "§4.2 discovery token",
        )
        assertHex(x25519(invite.hex("ek_priv"), byteArrayOf(9) + ByteArray(31)), invite.str("ek_pub"), "EK_A.pub")

        val bob = steps[1].state()
        val alice = steps[2].state()
        val aliceEk = bob.hex("alice_ek_pub")
        val bobEk = bob.hex("bob_ek_pub")
        assertHex(aliceEk, invite.str("ek_pub"), "Bob holds Alice's invite key")
        val sk = x25519(invite.hex("ek_priv"), bobEk)
        assertHex(sk, steps[2].str("sk"), "SK = X25519(EK_A.priv, EK_B.pub)")
        // §4.4 key confirmation and §9.3 encrypted name, in Bob's KeyExchangeInit.
        val init = decodeMailboxPayload(steps[1].effects().single().str("body")) as KeyExchangeInitPayload
        assertHex(bobEk, init.ekPub.toHex(), "the init carries EK_B.pub")
        val kConfirm = hkdf(sk, null, utf8("Where-v1-ConfirmKey"), 32)
        assertHex(hmacSha256(kConfirm, utf8("Where-v1-Confirm") + aliceEk + bobEk), init.keyConfirmation.toHex(), "key_confirmation")
        val kName = hkdf(sk, null, utf8("Where-v1-SuggestedName"), 32)
        val name =
            aeadDecrypt(
                kName,
                init.encryptedName.copyOfRange(0, 12),
                init.encryptedName.copyOfRange(12, init.encryptedName.size),
                aliceEk + bobEk,
            )
        assertEquals(steps[1].event().str("suggested_name"), name.decodeToString(), "encrypted_name under K_name")

        val aliceFp = sha256(aliceEk)
        val bobFp = sha256(bobEk)
        for (s in listOf(bob, alice)) {
            assertHex(aliceFp, s.str("alice_fp"), "alice_fp = SHA-256(EK_A.pub)")
            assertHex(bobFp, s.str("bob_fp"), "bob_fp = SHA-256(EK_B.pub)")
        }

        // §4.4: (ck0 || ck1 || rk0 || hk0 || hk1 || nhk) = HKDF(SK, "Where-v1-KeyExchange", 192).
        val e = hkdf(sk, null, utf8("Where-v1-KeyExchange"), 192)
        val ck0 = e.copyOfRange(0, 32)
        val ck1 = e.copyOfRange(32, 64)
        val rk0 = e.copyOfRange(64, 96)
        val hk0 = e.copyOfRange(96, 128)
        val hk1 = e.copyOfRange(128, 160)
        val nhk = e.copyOfRange(160, 192)
        val tAB = token(sk, aliceFp, bobFp)
        val tBA = token(sk, bobFp, aliceFp)

        // Bob, as initialised.
        assertHex(ck1, bob.str("send_chain_key"), "Bob send_chain = chain_key_1")
        assertHex(ck0, bob.str("recv_chain_key"), "Bob recv_chain = chain_key_0")
        assertHex(rk0, bob.str("root_key"), "root_key_0")
        assertHex(hk1, bob.str("send_header_key"), "Bob send_header_key = header_key_1")
        assertHex(hk0, bob.str("header_key"), "Bob recv_header_key = header_key_0")
        assertHex(nhk, bob.str("next_header_key"), "next_header_key")
        assertHex(tBA, bob.str("send_token"), "T_BA_0")
        assertHex(tAB, bob.str("recv_token"), "T_AB_0")

        // Alice, after her eager ratchet (§4.4 step 10): KDF_RK(rk0, X25519(EK_A1.priv, EK_B.pub)).
        val rk = kdfRk(rk0, x25519(alice.hex("local_dh_priv"), bobEk))
        assertHex(rk.root, alice.str("root_key"), "Alice root after the eager ratchet")
        assertHex(rk.chain, alice.str("send_chain_key"), "Alice epoch-1 send chain")
        assertHex(nhk, alice.str("send_header_key"), "Alice promotes next_header_key to send")
        assertHex(rk.header, alice.str("next_header_key"), "Alice's new next_header_key")
        assertHex(hk1, alice.str("header_key"), "Alice keeps header_key_1 to receive Bob's epoch 0")
        assertHex(ck1, alice.str("recv_chain_key"), "Alice recv_chain = chain_key_1")
        assertHex(token(rk.root, aliceFp, bobFp), alice.str("send_token"), "Alice's epoch-1 send token")
        assertHex(tAB, alice.str("prev_send_token"), "Alice's transition frame goes to T_AB_0")
        assertHex(tBA, alice.str("recv_token"), "Alice polls T_BA_0")
    }

    @Test
    fun `every ratchet and chain step follows spec 8_3`() {
        val last = mutableMapOf<String, JsonObject>()
        var ratchets = 0
        var sends = 0
        for ((i, step) in steps.withIndex()) {
            val party = step.str("party")
            val now = step.state()
            val prev = last[party]
            last[party] = now
            if (prev == null || prev.str("type") != "paired" || now.str("type") != "paired") continue
            val me = now.hex("local_fp")
            val peer = now.hex("remote_fp")
            when (step.event().str("type")) {
                "send" -> {
                    sends++
                    assertHex(chainAfter(prev.hex("send_chain_key"), 1), now.str("send_chain_key"), "step $i: KDF_CK on send")
                    assertEquals(prev.num("send_seq") + 1, now.num("send_seq"), "step $i: send_seq")
                }
                "receive" ->
                    if (now.str("remote_dh_pub") != prev.str("remote_dh_pub")) {
                        ratchets++
                        val remote = now.hex("remote_dh_pub")
                        val recv = kdfRk(prev.hex("root_key"), x25519(prev.hex("local_dh_priv"), remote))
                        val send = kdfRk(recv.root, x25519(now.hex("local_dh_priv"), remote))
                        assertHex(send.root, now.str("root_key"), "step $i: root after both sub-steps")
                        assertHex(chainAfter(send.chain, now.num("send_seq")), now.str("send_chain_key"), "step $i: send chain")
                        assertHex(chainAfter(recv.chain, now.num("recv_seq")), now.str("recv_chain_key"), "step $i: recv chain")
                        assertEquals(prev.str("next_header_key"), now.str("header_key"), "step $i: recv_header_key <- old next")
                        assertHex(recv.header, now.str("send_header_key"), "step $i: send_header_key <- recv sub-step")
                        assertHex(send.header, now.str("next_header_key"), "step $i: next_header_key <- send sub-step")
                        assertHex(token(recv.root, peer, me), now.str("recv_token"), "step $i: new recv token")
                        assertHex(token(send.root, me, peer), now.str("send_token"), "step $i: new send token")
                        assertEquals(prev.str("send_token"), now.str("prev_send_token"), "step $i: prev_send_token")
                        assertEquals(prev.str("send_header_key"), now.str("prev_send_header_key"), "step $i: prev_send_header_key")
                        assertEquals(prev.num("send_seq"), now.num("pn"), "step $i: pn = previous send chain length")
                    } else {
                        val advanced = now.num("recv_seq") - prev.num("recv_seq")
                        assertHex(chainAfter(prev.hex("recv_chain_key"), advanced), now.str("recv_chain_key"), "step $i: KDF_CK on receive")
                        assertEquals(prev.str("root_key"), now.str("root_key"), "step $i: no ratchet, same root")
                    }
            }
        }
        assertTrue(ratchets >= 4 && sends >= 10, "the transcript should exercise the schedule ($ratchets ratchets, $sends sends)")
    }

    @Test
    fun `every frame opens with keys derived from its sender's state, per spec 9_1`() {
        val last = mutableMapOf<String, JsonObject>()
        var frames = 0
        for ((i, step) in steps.withIndex()) {
            val party = step.str("party")
            val prev = last[party]
            val now = step.state()
            last[party] = now
            if (now.str("type") == "paired") {
                assertHex(x25519(now.hex("local_dh_priv"), basePoint), now.str("local_dh_pub"), "step $i: DH key pair")
            }
            if (step.event().str("type") != "send" || prev == null) continue
            frames++
            val put = step.effects().single()
            val frame = decodeMailboxPayload(put.str("body")) as EncryptedMessagePayload
            val seq = prev.num("send_seq") + 1
            // §5.4.1: an epoch's first frame goes to the previous mailbox under the previous key.
            assertEquals(if (seq == 1L) prev.str("prev_send_token") else prev.str("send_token"), put.str("mailbox"), "step $i: mailbox")
            val headerKey = if (seq == 1L) prev.hex("prev_send_header_key") else prev.hex("send_header_key")
            val env = frame.envelope
            val header =
                aeadDecrypt(headerKey, env.copyOfRange(0, 12), env.copyOfRange(12, env.size), prev.hex("alice_fp") + prev.hex("bob_fp"))
            assertEquals(82, header.size, "step $i: header plaintext size")
            assertEquals(1, header[0].toInt(), "step $i: header version")
            val dh = header.copyOfRange(1, 33)
            val ack = header.copyOfRange(33, 65)
            assertHex(dh, prev.str("local_dh_pub"), "step $i: header dh_pub")
            assertHex(ack, prev.str("remote_dh_pub"), "step $i: header ack_remote_dh_pub")
            assertEquals(seq, be64(header, 65), "step $i: header msg_num")
            assertEquals(prev.num("pn"), be64(header, 73), "step $i: header prev_chain_len")

            // §8.3 KDF_CK and §9.1.2 body AAD.
            val messageKey = hmacSha256(prev.hex("send_chain_key"), byteArrayOf(0x01))
            val nonce = hkdf(messageKey, null, utf8("Where-v1-MsgNonce"), 12)
            val aad =
                utf8("Where-v1-Message") + byteArrayOf(0, 0, 0, 1) + prev.hex("local_fp") + prev.hex("remote_fp") +
                    be64(seq) + dh + ack
            val padded = aeadDecrypt(messageKey, nonce, frame.ct, aad)
            assertEquals(512, padded.size, "step $i: padded plaintext size")
            assertEquals(step.event().str("plaintext"), unpad(padded).decodeToString(), "step $i: plaintext")
        }
        assertTrue(frames >= 10)
    }

    @Test
    fun `what is delivered is what the peer sent, once, and token effects match the state`() {
        val sentBy = mutableMapOf<String, MutableList<String>>() // party -> plaintexts sent
        val deliveredTo = mutableMapOf<String, MutableList<String>>()
        val last = mutableMapOf<String, JsonObject>()
        for ((i, step) in steps.withIndex()) {
            val party = step.str("party")
            val peer = if (party == "alice") "bob" else "alice"
            val event = step.event()
            val effects = step.effects()
            when (event.str("type")) {
                "send" -> sentBy.getOrPut(party) { mutableListOf() }.add(event.str("plaintext"))
                "receive" -> {
                    val before = last.getValue(party).str("recv_token")
                    val after = step.state().str("recv_token")
                    assertEquals(before, event.str("mailbox"), "step $i: polled its current mailbox")
                    val retired = effects.filter { it.str("type") == "retire_token" }.map { it.str("mailbox") }
                    assertEquals(
                        if (after != before) listOf(before) else emptyList(),
                        retired,
                        "step $i: RetireToken iff the token changed",
                    )
                    effects.filter { it.str("type") == "ack" }.forEach { assertEquals(before, it.str("mailbox"), "step $i: ack mailbox") }
                    effects.filter { it.str("type") == "deliver" }.forEach { d ->
                        val pt = d.str("plaintext")
                        assertTrue(pt in sentBy[peer].orEmpty(), "step $i: delivered something $peer never sent: $pt")
                        deliveredTo.getOrPut(party) { mutableListOf() }.add(pt)
                    }
                }
            }
            last[party] = step.state()
        }
        for ((party, got) in deliveredTo) {
            val nonKeepalive = got.filter { "\"ka\"" !in it }
            assertEquals(nonKeepalive.distinct(), nonKeepalive, "$party got something twice")
        }
        // Every Location and StoppedSharing sent arrives (only a Keepalive is corrupted in transit).
        for ((party, sent) in sentBy) {
            val peer = if (party == "alice") "bob" else "alice"
            assertEquals(
                sent.filter { "\"ka\"" !in it }.sorted(),
                deliveredTo[peer].orEmpty().filter { "\"ka\"" !in it }.sorted(),
                "everything $party sent reached $peer",
            )
        }
    }
}
