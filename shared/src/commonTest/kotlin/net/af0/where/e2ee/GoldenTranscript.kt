package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The golden transcript (commonTest/resources/vectors/where-v1-transcript.json): a pairing and the
 * first frames each way, run through [ProtocolCore.step] with a [DeterministicRng] per party and a
 * fixed clock, recorded byte for byte. It is the normative example for docs/e2ee-location-sync.md.
 *
 * Each step records its input event, the effects, and the party's state afterwards. Inputs that
 * cross between the parties (the QR, the init, the frames) are recorded as the other party's
 * output, exactly as it went over the wire, and [replay] feeds them back from the file, so a
 * passing replay shows both that this checkout produces the same bytes and that the recorded
 * bytes are accepted.
 */
object GoldenTranscript {
    const val FILE = "where-v1-transcript.json"

    /** Bumped when the transcript's format or scenario changes; independent of the protocol version. */
    const val VECTORS_VERSION = 1
    const val START_MILLIS = 1_767_225_600_000L // 2026-01-01T00:00:00Z
    const val STEP_MILLIS = 1_000L
    val SEEDS = mapOf(ALICE to "where-golden-v1/alice", BOB to "where-golden-v1/bob")

    private const val ALICE = "alice"
    private const val BOB = "bob"

    val prettyJson = Json { prettyPrint = true }

    /** One scripted step: who acts, and the event built from what has been recorded so far. */
    private class Scripted(val party: String, val event: (Recorded) -> ProtocolCore.Event)

    /** The steps recorded so far, for building later events from earlier outputs. */
    private class Recorded(val steps: List<JsonObject>) {
        /** The wire bodies of the Put effects of step [index]. */
        fun puts(index: Int): List<JsonObject> = steps[index].effects().filter { it.str("type") == "put" }

        fun put(
            index: Int,
            n: Int = 0,
        ) = puts(index)[n]
    }

    private fun loc(
        lat: Double,
        lng: Double,
        tsSeconds: Long,
        stationary: Boolean = false,
    ) = MessagePlaintext.Location(lat = lat, lng = lng, acc = 10.0, ts = tsSeconds, stationary = stationary)

    private val T0 = START_MILLIS / 1000

    /**
     * Alice invites, Bob scans, Alice takes the init. Alice sends two frames; Bob takes them one
     * by one (his first receive is a DH ratchet and a receive-token rotation). Bob replies; his
     * first frame is the epoch's transition frame on the previous mailbox, so Alice ratchets,
     * rotates her receive token and cancels her own transition frame, then reads Bob's second
     * frame on the new mailbox. Then StoppedSharing and Keepalive, the second epoch each way, and
     * the batch cases (below). Every frame is read from the mailbox it was put to.
     */
    private val script =
        listOf(
            Scripted(ALICE) { ProtocolCore.Event.CreateInvite("Alice") },
            Scripted(BOB) { r -> ProtocolCore.Event.ScanQr(QrPayload.fromUrl(r.steps[0].state().str("qr_url"))!!, "Bob") },
            Scripted(ALICE) { r -> ProtocolCore.Event.ProcessInit(r.put(1).body() as KeyExchangeInitPayload) },
            Scripted(ALICE) { ProtocolCore.Event.Send(loc(37.7749, -122.4194, T0 + 3)) },
            Scripted(ALICE) { ProtocolCore.Event.Send(loc(37.775, -122.4183, T0 + 4)) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(3).body())) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(4).body())) },
            Scripted(BOB) { ProtocolCore.Event.Send(loc(40.7128, -74.006, T0 + 7)) },
            Scripted(BOB) { ProtocolCore.Event.Send(loc(40.7129, -74.0061, T0 + 8, stationary = true)) },
            Scripted(ALICE) { r -> ProtocolCore.Event.Receive(listOf(r.put(7).body())) },
            Scripted(ALICE) { r -> ProtocolCore.Event.Receive(listOf(r.put(8).body())) },
            Scripted(ALICE) { ProtocolCore.Event.Send(MessagePlaintext.StoppedSharing(ts = T0 + 11)) },
            Scripted(ALICE) { ProtocolCore.Event.Send(MessagePlaintext.Keepalive()) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(11).body())) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(12).body())) },
            // Out of order: Alice reads Bob's third frame before his second (a skipped key), then
            // the second (from the cache) with the third again (a replay, still acked).
            Scripted(BOB) { ProtocolCore.Event.Send(loc(40.713, -74.0062, T0 + 15)) },
            Scripted(BOB) { ProtocolCore.Event.Send(loc(40.7131, -74.0063, T0 + 16)) },
            Scripted(BOB) { ProtocolCore.Event.Send(loc(40.7132, -74.0064, T0 + 17)) },
            Scripted(ALICE) { r -> ProtocolCore.Event.Receive(listOf(r.put(15).body())) },
            Scripted(ALICE) { r -> ProtocolCore.Event.Receive(listOf(r.put(17).body())) },
            Scripted(ALICE) { r -> ProtocolCore.Event.Receive(listOf(r.put(16).body(), r.put(17).body())) },
            // A batch delivered out of order (sorted by seq before decrypting), then a corrupted
            // body (soft fail: committed and acked, not delivered) next to a payload that isn't
            // an encrypted frame (acked unread).
            Scripted(ALICE) { ProtocolCore.Event.Send(loc(37.7751, -122.4182, T0 + 21)) },
            Scripted(ALICE) { ProtocolCore.Event.Send(loc(37.7752, -122.4181, T0 + 22)) },
            Scripted(ALICE) { ProtocolCore.Event.Send(loc(37.7753, -122.418, T0 + 23)) },
            Scripted(ALICE) { ProtocolCore.Event.Send(MessagePlaintext.Keepalive()) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(21).body())) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(r.put(23).body(), r.put(22).body())) },
            Scripted(BOB) { r -> ProtocolCore.Event.Receive(listOf(corrupt(r.put(24).body()), r.put(1).body())) },
        )

    /** The frame with the last byte of its body flipped: the header still opens, the body doesn't. */
    private fun corrupt(payload: MailboxPayload): MailboxPayload {
        val frame = payload as EncryptedMessagePayload
        val ct = frame.ct.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        return EncryptedMessagePayload(v = frame.v, envelope = frame.envelope, ct = ct)
    }

    /** Runs the script and returns the transcript. */
    fun generate(): JsonObject = run(recordedEvent = null)

    /**
     * Runs the script with every event that crosses between the parties taken from [recorded]
     * instead, checking each step's output against the recorded one with [check].
     */
    fun replay(
        recorded: JsonObject,
        check: (index: Int, expected: JsonObject, actual: JsonObject) -> Unit,
    ) {
        val steps = steps(recorded)
        require(steps.size == script.size) { "the transcript has ${steps.size} steps, the script ${script.size}" }
        val actual = run(recordedEvent = { i -> decodeEvent(steps[i].getValue("event").jsonObject) })
        check(-1, recorded.header(), actual.header())
        steps(actual).forEachIndexed { i, step -> check(i, steps[i], step) }
    }

    private fun JsonObject.header() = JsonObject(filterKeys { it != "steps" })

    private fun run(recordedEvent: ((index: Int) -> ProtocolCore.Event)?): JsonObject {
        val rngs = SEEDS.mapValues { (_, seed) -> DeterministicRng(seed) }
        val states = mutableMapOf<String, ProtocolCore.State>(ALICE to ProtocolCore.State.Idle, BOB to ProtocolCore.State.Idle)
        val steps = mutableListOf<JsonObject>()
        script.forEachIndexed { i, scripted ->
            val now = START_MILLIS + i * STEP_MILLIS
            val event = recordedEvent?.invoke(i) ?: scripted.event(Recorded(steps))
            val before = states.getValue(scripted.party)
            val step = ProtocolCore.step(before, event, now, rngs.getValue(scripted.party))
            states[scripted.party] = step.state
            steps +=
                buildJsonObject {
                    put("party", scripted.party)
                    put("time_ms", now)
                    put("event", encodeEvent(event))
                    if (event is ProtocolCore.Event.ProcessInit) put("sk", handshakeSecret(before, event))
                    put("effects", JsonArray(step.effects.map(::encodeEffect)))
                    put("state", encodeState(step.state))
                }
        }
        return buildJsonObject {
            put("vectors_version", VECTORS_VERSION)
            put("protocol_version", PROTOCOL_VERSION)
            put("spec", "docs/e2ee-location-sync.md")
            put(
                "about",
                "Golden transcript: each step is ProtocolCore.step(state, event, time_ms, rng) for one party. " +
                    "Hex unless noted; mailbox bodies are the exact wire JSON. Regenerate with " +
                    "WHERE_WRITE_VECTORS=1 ./gradlew :shared:jvmTest --tests '*GoldenTranscriptWriter*'.",
            )
            put(
                "rng",
                buildJsonObject {
                    put(
                        "definition",
                        "Per party. Draw i (from 0) of n bytes = HKDF-SHA-256(IKM = SHA-256(UTF-8 seed), salt = empty, " +
                            "info = UTF-8 \"where-test-rng\" || u64be(i), L = n). An X25519 key pair is one 32-byte draw " +
                            "as the private key (clamped by X25519) and its public key.",
                    )
                    put("seeds", buildJsonObject { SEEDS.forEach { (party, seed) -> put(party, seed) } })
                },
            )
            put("steps", JsonArray(steps))
        }
    }

    // ---- encoding ----

    private fun plaintextJson(pt: MessagePlaintext) = Session.encodeMessage(pt).decodeToString()

    private fun body(payload: MailboxPayload) = mailboxJson.encodeToString(MailboxPayload.serializer(), payload)

    private fun encodeEvent(event: ProtocolCore.Event): JsonObject =
        buildJsonObject {
            when (event) {
                is ProtocolCore.Event.CreateInvite -> {
                    put("type", "create_invite")
                    put("suggested_name", event.suggestedName)
                }
                is ProtocolCore.Event.ScanQr -> {
                    put("type", "scan_qr")
                    put("qr_url", event.qr.toUrl())
                    put("suggested_name", event.suggestedName)
                }
                is ProtocolCore.Event.ProcessInit -> {
                    put("type", "process_init")
                    put("body", body(event.init))
                }
                is ProtocolCore.Event.Send -> {
                    put("type", "send")
                    put("plaintext", plaintextJson(event.payload))
                }
                is ProtocolCore.Event.Receive -> {
                    put("type", "receive")
                    put("bodies", buildJsonArray { event.messages.forEach { add(JsonPrimitive(body(it))) } })
                }
            }
        }

    private fun decodeEvent(o: JsonObject): ProtocolCore.Event =
        when (o.str("type")) {
            "create_invite" -> ProtocolCore.Event.CreateInvite(o.str("suggested_name"))
            "scan_qr" -> ProtocolCore.Event.ScanQr(QrPayload.fromUrl(o.str("qr_url"))!!, o.str("suggested_name"))
            "process_init" -> ProtocolCore.Event.ProcessInit(decodeMailboxPayload(o.str("body")) as KeyExchangeInitPayload)
            "send" -> ProtocolCore.Event.Send(Session.decodeMessage(o.str("plaintext").encodeToByteArray()))
            "receive" -> ProtocolCore.Event.Receive(o["bodies"]!!.jsonArray.map { decodeMailboxPayload(it.jsonPrimitive.content) })
            else -> error("unknown event ${o.str("type")}")
        }

    private fun encodeEffect(effect: ProtocolCore.Effect): JsonObject =
        buildJsonObject {
            when (effect) {
                is ProtocolCore.Effect.Put -> {
                    put("type", "put")
                    put("mailbox", effect.mailbox)
                    put("msg_id", effect.payload.msgId)
                    put("body", body(effect.payload))
                }
                is ProtocolCore.Effect.CancelPut -> {
                    put("type", "cancel_put")
                    put("mailbox", effect.mailbox)
                }
                is ProtocolCore.Effect.Ack -> {
                    put("type", "ack")
                    put("mailbox", effect.mailbox)
                    put("ids", buildJsonArray { effect.ids.forEach { add(JsonPrimitive(it)) } })
                }
                is ProtocolCore.Effect.Deliver -> {
                    put("type", "deliver")
                    put("plaintext", plaintextJson(effect.plaintext))
                }
                is ProtocolCore.Effect.RetireToken -> {
                    put("type", "retire_token")
                    put("mailbox", effect.mailbox)
                }
            }
        }

    @OptIn(RawKeyAccess::class)
    private fun hex(key: KeyBytes?): String = key?.bytes?.toHex() ?: ""

    /** The skipped-key cache: its map key ("dh_pub hex:seq"), the key, nonce and time stored. */
    @OptIn(RawKeyAccess::class)
    private fun skipped(s: SessionState) =
        buildJsonArray {
            s.skippedMessageKeys.forEach { (id, k) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("message_key", hex(k.messageKey))
                        put("nonce", hex(k.nonce))
                        put("stored_at_ms", k.storedAtMillis)
                    },
                )
            }
        }

    @OptIn(RawKeyAccess::class)
    private fun handshakeSecret(
        state: ProtocolCore.State,
        event: ProtocolCore.Event.ProcessInit,
    ): String {
        val inviting = state as ProtocolCore.State.Inviting
        return x25519(inviting.priv.bytes, event.init.ekPub).toHex()
    }

    private fun encodeState(state: ProtocolCore.State): JsonObject =
        buildJsonObject {
            when (state) {
                ProtocolCore.State.Idle -> put("type", "idle")
                is ProtocolCore.State.Inviting -> {
                    put("type", "inviting")
                    put("qr_url", state.qr.toUrl())
                    put("ek_priv", hex(state.priv))
                    put("ek_pub", hex(state.qr.ekPub))
                    put("discovery_secret", hex(state.qr.discoverySecret))
                    put("discovery_token", state.qr.discoveryToken().toHex())
                    put("expires_at", state.qr.expiresAt)
                }
                is ProtocolCore.State.Paired -> {
                    val s = state.session
                    put("type", "paired")
                    put("is_alice", s.isAlice)
                    put("alice_ek_pub", hex(s.aliceEkPub))
                    put("bob_ek_pub", hex(s.bobEkPub))
                    put("alice_fp", hex(s.aliceFp))
                    put("bob_fp", hex(s.bobFp))
                    put("local_fp", hex(s.localFp))
                    put("remote_fp", hex(s.remoteFp))
                    put("root_key", hex(s.rootKey))
                    put("send_chain_key", hex(s.sendChainKey))
                    put("recv_chain_key", hex(s.recvChainKey))
                    put("send_header_key", hex(s.sendHeaderKey))
                    put("prev_send_header_key", hex(s.prevSendHeaderKey))
                    put("header_key", hex(s.headerKey))
                    put("next_header_key", hex(s.nextHeaderKey))
                    put("send_token", hex(s.sendToken))
                    put("prev_send_token", hex(s.prevSendToken))
                    put("recv_token", hex(s.recvToken))
                    put("local_dh_priv", hex(s.localDhPriv))
                    put("local_dh_pub", hex(s.localDhPub))
                    put("remote_dh_pub", hex(s.remoteDhPub))
                    put("send_seq", s.sendSeq)
                    put("recv_seq", s.recvSeq)
                    put("pn", s.pn)
                    put("pr", s.pr)
                    put("skipped_keys", skipped(s))
                }
            }
        }

    // ---- reading ----

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.effects() = getValue("effects").jsonArray.map { it.jsonObject }

    private fun JsonObject.state() = getValue("state").jsonObject

    private fun JsonObject.body() = decodeMailboxPayload(str("body"))

    fun steps(transcript: JsonObject): List<JsonObject> = transcript.getValue("steps").jsonArray.map { it.jsonObject }

    fun effectTypes(step: JsonObject): List<String> = step.effects().map { it.str("type") }
}
