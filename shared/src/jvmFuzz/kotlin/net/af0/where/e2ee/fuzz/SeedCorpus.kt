package net.af0.where.e2ee.fuzz

import net.af0.where.e2ee.DiscoverySecret
import net.af0.where.e2ee.EncryptedMessagePayload
import net.af0.where.e2ee.InvitePublicKey
import net.af0.where.e2ee.KeyExchangeInitPayload
import net.af0.where.e2ee.LocationPrecision
import net.af0.where.e2ee.MailboxPayload
import net.af0.where.e2ee.MessagePlaintext
import net.af0.where.e2ee.PADDING_SIZE
import net.af0.where.e2ee.QrPayload
import net.af0.where.e2ee.Session
import net.af0.where.e2ee.longToBeBytes
import net.af0.where.e2ee.mailboxJson
import net.af0.where.e2ee.x25519
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

        // Fixed bytes, not fresh keys: a seed must decode the same way on every run, and
        // regenerating must not add near-duplicates. The fixture's own keys are random per JVM,
        // so genuine frames are reached by the structured modes, relative to the fixture.
        val qrs =
            listOf(
                QrPayload(
                    ekPub =
                        InvitePublicKey(
                            x25519(
                                ByteArray(32) {
                                    1
                                },
                                BASE_POINT,
                            ),
                        ),
                    suggestedName = "Alice",
                    discoverySecret = DiscoverySecret(ByteArray(32) { 2 }),
                ),
                // An expiry far enough out that the seed never stops reaching the DH and session code.
                QrPayload(
                    ekPub =
                        InvitePublicKey(
                            x25519(
                                ByteArray(32) {
                                    3
                                },
                                BASE_POINT,
                            ),
                        ),
                    suggestedName = "Ålice 🗺",
                    discoverySecret =
                        DiscoverySecret(
                            ByteArray(32) { 4 },
                        ),
                    expiresAt = FAR_FUTURE,
                ),
            )
        write(
            root,
            "QrPayload",
            qrs.flatMap { qr ->
                val json = Base64.UrlSafe.decode(qr.toUrl().substringAfter("#"))
                listOf(byteArrayOf(0) + qr.toUrl().encodeToByteArray(), byteArrayOf(1) + json, byteArrayOf(2) + json)
            },
        )

        val initJson =
            mailboxJson.encodeToString(
                MailboxPayload.serializer(),
                KeyExchangeInitPayload(
                    ekPub = ByteArray(32) { 5 },
                    keyConfirmation = ByteArray(32) { 6 },
                    encryptedName = ByteArray(40) { 7 },
                ),
            )
        val envelope = ByteArray(ENVELOPE_SIZE) { 8 }
        val ct = ByteArray(PADDING_SIZE + TAG_SIZE) { 9 }
        val frameJson = mailboxJson.encodeToString(MailboxPayload.serializer(), EncryptedMessagePayload(envelope = envelope, ct = ct))
        write(root, "MailboxPayload", listOf(initJson, frameJson).map { it.encodeToByteArray() })
        // Mode 1: a scanner key, a name, then each mutation (0 = none) and its parameters.
        val structuredInit = ByteArray(32) { 3 } + byteArrayOf(5) + "Bobby".encodeToByteArray()
        write(
            root,
            "KeyExchangeInit",
            listOf(byteArrayOf(0) + initJson.encodeToByteArray()) +
                (0 until 6).map { mutation -> byteArrayOf(1) + structuredInit + byteArrayOf(mutation.toByte(), 4, 1, 2) },
        )

        write(
            root,
            "DecryptMessage",
            // Mode 0: a frame of the real shape (110-byte envelope, padded body) that authenticates
            // under no key.
            listOf(byteArrayOf(0, 0, ENVELOPE_SIZE.toByte()) + envelope + ct) +
                SessionFixture.frames.indices.flatMap { pick ->
                    listOf(
                        byteArrayOf(1, pick.toByte(), 0, 0, 5, 1),
                        byteArrayOf(3, pick.toByte(), 0, 0, 0, 128.toByte(), 0, 128.toByte()),
                    )
                } +
                // Mode 2: real-sized padded plaintexts, authenticated as Alice's next frame.
                messages.take(4).map { m ->
                    val length = byteArrayOf((PADDING_SIZE shr 8).toByte(), PADDING_SIZE.toByte())
                    byteArrayOf(2, 0) + length + Session.padToFixedSize(m, PADDING_SIZE)
                },
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

    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    private const val FAR_FUTURE = 4_102_444_800L // 2100-01-01
    private const val ENVELOPE_SIZE = 12 + 82 + 16 // nonce, header, tag
    private const val TAG_SIZE = 16
}
