package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The golden transcript (resources/vectors, see [GoldenTranscript]) on every platform: this
 * checkout must reproduce it byte for byte, and must accept the recorded wire bytes. A failure
 * here after an intended protocol change means regenerating the file (see its "about" field),
 * bumping its version with the spec's, and saying so in the PR.
 */
class GoldenTranscriptTest {
    init {
        initializeE2eeTests()
    }

    private val text = VectorFiles.read(GoldenTranscript.FILE)
    private val recorded = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `replaying the recorded inputs reproduces every step`() {
        GoldenTranscript.replay(recorded) { i, expected, actual ->
            assertEquals(pretty(expected), pretty(actual), if (i < 0) "transcript header differs" else "step $i differs")
        }
    }

    @Test
    fun `generating from scratch reproduces the file byte for byte`() {
        assertEquals(text.trimEnd(), GoldenTranscript.prettyJson.encodeToString(JsonObject.serializer(), GoldenTranscript.generate()))
    }

    @Test
    fun `transcript covers a DH ratchet and a token rotation in each direction`() {
        val steps = GoldenTranscript.steps(recorded)
        for (party in listOf("alice", "bob")) {
            val receives = steps.filter { it.party() == party && it.eventType() == "receive" }.flatMap { GoldenTranscript.effectTypes(it) }
            assertTrue("deliver" in receives, "$party delivers nothing")
            assertTrue("retire_token" in receives, "$party never rotates its receive token")
        }
        val all = steps.flatMap { GoldenTranscript.effectTypes(it) }
        assertTrue("cancel_put" in all, "no transition frame is cancelled")
    }

    @Test
    fun `transcript covers skipped keys and replays and reordering and soft failures`() {
        val steps = GoldenTranscript.steps(recorded)
        assertTrue(steps.any { it.getValue("state").jsonObject["skipped_keys"]?.jsonArray?.isNotEmpty() == true }, "no skipped key")
        val receives = steps.filter { it.eventType() == "receive" }

        fun acked(step: JsonObject) =
            step.getValue("effects").jsonArray.map { it.jsonObject }.filter { it.str("type") == "ack" }.flatMap { e ->
                e.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
            }

        fun delivered(step: JsonObject) = GoldenTranscript.effectTypes(step).count { it == "deliver" }

        fun bodies(step: JsonObject) = step.getValue("event").jsonObject.getValue("bodies").jsonArray.size
        // A replay or a soft failure: acked but not delivered.
        assertTrue(receives.count { acked(it).size > delivered(it) } >= 2, "no replayed or undeliverable frame")
        assertTrue(receives.any { bodies(it) >= 2 && delivered(it) == 2 }, "no multi-frame batch")
    }

    @Test
    fun `every receive reads the mailbox its frames were put to`() {
        val putTo = mutableMapOf<String, String>()
        val recvToken = mutableMapOf<String, String>()
        for ((i, step) in GoldenTranscript.steps(recorded).withIndex()) {
            val event = step.getValue("event").jsonObject
            if (event.str("type") == "receive") {
                // Frames as they were put; the corrupted copy and the stray non-frame are planted.
                for (body in event.getValue("bodies").jsonArray) {
                    val payload = decodeMailboxPayload(body.jsonPrimitive.content)
                    if (payload !is EncryptedMessagePayload || payload.msgId !in putTo) continue
                    assertEquals(recvToken[step.party()], putTo[payload.msgId], "step $i reads ${payload.msgId} from the wrong mailbox")
                }
            }
            for (effect in step.getValue("effects").jsonArray.map { it.jsonObject }) {
                if (effect.str("type") == "put") putTo[effect.str("msg_id")] = effect.str("mailbox")
            }
            step.getValue("state").jsonObject["recv_token"]?.let { recvToken[step.party()] = it.jsonPrimitive.content }
        }
    }

    private fun pretty(o: JsonObject) = GoldenTranscript.prettyJson.encodeToString(JsonObject.serializer(), o)

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.party() = str("party")

    private fun JsonObject.eventType() = getValue("event").jsonObject.str("type")
}
