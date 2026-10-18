package net.af0.where.e2ee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LabelTest {
    private fun Label.text() = bytes.decodeToString()

    @Test
    fun labelsAreDistinct() {
        val texts = Label.entries.map { it.text() }
        assertEquals(texts.size, texts.toSet().size, "duplicate label in $texts")
    }

    @Test
    fun labelsOfTheSameUseArePrefixFree() {
        for (a in Label.entries) {
            for (b in Label.entries) {
                if (a == b || a.use != b.use) continue
                assertFalse(b.text().startsWith(a.text()), "$a is a prefix of $b")
            }
        }
    }

    @Test
    fun labelsKeepTheirWireValues() {
        // Changing any of these breaks interop with existing clients and sessions.
        assertEquals(
            mapOf(
                Label.RATCHET_STEP to "Where-v1-RatchetStep",
                Label.MSG_NONCE to "Where-v1-MsgNonce",
                Label.KEY_EXCHANGE to "Where-v1-KeyExchange",
                Label.ROUTING_TOKEN to "Where-v1-RoutingToken",
                Label.CONFIRM_KEY to "Where-v1-ConfirmKey",
                Label.SUGGESTED_NAME to "Where-v1-SuggestedName",
                Label.SAFETY_NUMBER to "Where-v1-SafetyNumber",
                Label.DISCOVERY to "Where-v1-Discovery",
                Label.CONFIRM to "Where-v1-Confirm",
                Label.MESSAGE_AAD to "Where-v1-Message",
            ),
            Label.entries.associateWith { it.text() },
        )
    }

    @Test
    fun hkdfRejectsALabelMeantForAnotherUse() {
        assertFailsWith<IllegalArgumentException> { hkdf(Label.MESSAGE_AAD, ByteArray(32), null, 32) }
    }
}
