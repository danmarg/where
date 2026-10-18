package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.DhPrivateKey
import net.af0.where.e2ee.DhPublicKey
import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.KeyExchangeInitMessage
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.SystemRng
import net.af0.where.e2ee.decodeMailboxPayload
import net.af0.where.e2ee.dh
import net.af0.where.e2ee.x25519

/**
 * Alice's handling of a KeyExchangeInit for her pending invite: decoding it from the mailbox,
 * then aliceProcessInit. Mode 1 builds a genuine init from a fuzzer-chosen scanner key and name,
 * then optionally corrupts one field, so the checks after key confirmation are reached too.
 */
object KeyExchangeInitFuzzer {
    private val invite by lazy {
        Libsodium.ensure()
        KeyExchange.aliceCreateQrPayload("Alice")
    }
    private val basePoint = ByteArray(32).also { it[0] = 9 }

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        val (qr, aliceEkPriv) = invite
        val alicePub = qr.ekPub.toDhPublicKey()
        val input = FuzzInput(data)
        val init: KeyExchangeInitMessage
        var genuine = false
        if (input.byte() % 2 == 0) {
            val payload = refusedOnlyWithWhereException { decodeMailboxPayload(input.rest().decodeToString()) }
            if (payload !is KeyExchangeInitPayload) return
            init = KeyExchangeInitMessage(payload.v, payload.ekPub, payload.keyConfirmation, payload.encryptedName)
        } else {
            val bobPrivBytes = input.fixed(32)
            val bobPub = DhPublicKey(x25519(bobPrivBytes, basePoint))
            val sk = refusedOnlyWithWhereException { dh(DhPrivateKey(bobPrivBytes), alicePub) } ?: return
            val name = input.sized(64).decodeToString()
            var ekPub = bobPub.toByteArray()
            var confirmation = KeyExchange.buildKeyConfirmation(sk, alicePub, bobPub)
            var encryptedName = KeyExchange.encryptSuggestedName(sk, alicePub, bobPub, name, SystemRng)
            var version = 1
            when (input.byte() % 6) {
                0 -> genuine = true
                1 -> ekPub = input.sized(40)
                2 -> confirmation = input.sized(40)
                3 -> encryptedName = input.sized(128)
                4 -> version = input.long().toInt()
                else -> {
                    val field = listOf(ekPub, confirmation, encryptedName)[input.byte() % 3]
                    val at = input.byte() % field.size
                    field[at] = (field[at].toInt() xor (1 shl (input.byte() % 8))).toByte()
                }
            }
            init = KeyExchangeInitMessage(version, ekPub, confirmation, encryptedName)
            if (genuine) {
                fuzzCheck(KeyExchange.decryptSuggestedName(sk, alicePub, bobPub, encryptedName) == name) { "name round trip" }
            }
        }
        val before = aliceEkPriv.copyOf()
        val session = refusedOnlyWithWhereException { KeyExchange.aliceProcessInit(init, aliceEkPriv, alicePub) }
        fuzzCheck(aliceEkPriv == before) { "aliceProcessInit modified the invite's private key" }
        fuzzCheck(!genuine || session != null) { "genuine KeyExchangeInit refused" }
    }
}
