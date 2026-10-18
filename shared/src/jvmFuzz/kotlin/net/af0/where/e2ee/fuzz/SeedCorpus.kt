package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.KeyExchange
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationPrecision
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.PADDING_SIZE
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.longToBeBytes
import net.af0.where.e2ee.mailboxJson
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Writes the seed corpus (src/jvmFuzz/corpus/<target>/) from well-formed inputs, so each target
 * starts from every input shape it accepts: ./gradlew :shared:generateFuzzSeeds. Files are named
 * by content hash, like libFuzzer's, so regenerating only adds.
 */
@OptIn(ExperimentalEncodingApi::class)
object SeedCorpus {
    @JvmStatic
    fun main(args: Array<String>) {
        Libsodium.ensure()
        val root = File(args.single())
        val messages =
            listOf(
                MessagePlaintext.Location(37.7749, -122.4194, 12.5, 1_715_670_000L),
                MessagePlaintext.Location(-33.86, 151.2, 1100.0, 1L, LocationPrecision.COARSE, stationary = true),
                MessagePlaintext.Keepalive(),
                MessagePlaintext.StoppedSharing(1_715_670_000L),
            ).map { Session.encodeMessage(it) } +
                listOf(
                    """{"lat":1.5,"lng":2.5,"acc":3.0,"ts":4}""",
                    """{"type":"loc","lat":"1e999","lng":-0.0,"acc":0,"ts":-1,"precision":"MEDIUM"}""",
                    """{"type":"stop"}""",
                    """{"type":"future","x":[1,{"y":null}]}""",
                ).map { it.encodeToByteArray() }

        write(root, "DecodeMessage", messages)
        write(root, "Unpad", messages.map { Session.padToFixedSize(it, PADDING_SIZE) } + listOf(byteArrayOf(0x80.toByte()), ByteArray(16)))

        val header = byteArrayOf(1) + ByteArray(32) { 9 } + ByteArray(32) { 7 } + longToBeBytes(5) + longToBeBytes(2) + byteArrayOf(0)
        write(root, "DecryptHeader", listOf(byteArrayOf(0) + ByteArray(110), byteArrayOf(1) + header, byteArrayOf(2) + header))

        val (qr, _) = KeyExchange.aliceCreateQrPayload("Alice")
        val qrJson = Base64.UrlSafe.decode(qr.toUrl().substringAfter("#"))
        write(
            root,
            "QrPayload",
            listOf(byteArrayOf(0) + qr.toUrl().encodeToByteArray(), byteArrayOf(1) + qrJson, byteArrayOf(2) + qrJson),
        )

        val (init, _) = KeyExchange.bobProcessQr(qr, "Bob")
        val initJson =
            mailboxJson.encodeToString(
                MailboxPayload.serializer(),
                KeyExchangeInitPayload(ekPub = init.ekPub, keyConfirmation = init.keyConfirmation, encryptedName = init.encryptedName),
            )
        val frameJson = mailboxJson.encodeToString(MailboxPayload.serializer(), SessionFixture.frames[0])
        write(root, "MailboxPayload", listOf(initJson, frameJson).map { it.encodeToByteArray() })
        // Mode 1: a scanner key, a name, then each mutation (0 = none) and its parameters.
        val structuredInit = ByteArray(32) { 3 } + byteArrayOf(5) + "Bobby".encodeToByteArray()
        write(
            root,
            "KeyExchangeInit",
            listOf(byteArrayOf(0) + initJson.encodeToByteArray()) +
                (0 until 6).map { mutation -> byteArrayOf(1) + structuredInit + byteArrayOf(mutation.toByte(), 4, 1, 2) },
        )

        val frame = SessionFixture.frames[3]
        write(
            root,
            "DecryptMessage",
            listOf(byteArrayOf(0, 0, 120) + frame.envelope + frame.ct) +
                SessionFixture.frames.indices.flatMap { pick ->
                    listOf(
                        byteArrayOf(1, pick.toByte(), 0, 0, 5, 1),
                        byteArrayOf(3, pick.toByte(), 0, 0, 0, 128.toByte(), 0, 128.toByte()),
                    )
                } +
                listOf(byteArrayOf(2, 0, 120) + Session.padToFixedSize(messages[0], 120)),
        )
    }

    private fun write(
        root: File,
        target: String,
        inputs: List<ByteArray>,
    ) {
        require(target in fuzzTargets) { "no target $target" }
        val dir = root.resolve(target).apply { mkdirs() }
        for (input in inputs) {
            val name = MessageDigest.getInstance("SHA-1").digest(input).joinToString("") { "%02x".format(it) }
            dir.resolve(name).writeBytes(input)
        }
    }
}
