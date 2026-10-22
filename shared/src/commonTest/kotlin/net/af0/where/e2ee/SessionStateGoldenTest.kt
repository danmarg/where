@file:OptIn(RawKeyAccess::class)
@file:Suppress("ktlint:standard:max-line-length") // recorded JSON goldens

package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins the persisted SessionState JSON and the bytes the ratchet derives from fixed inputs, so a
 * refactor of the crypto types can't silently change what's on disk or on the wire. Every value
 * here was recorded from the code as it was before the typed key wrappers.
 */
class SessionStateGoldenTest {
    init {
        initializeE2eeTests()
    }

    // Same configuration as E2eeStore's persistence Json.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun bytes(
        tag: Int,
        size: Int = 32,
    ): ByteArray = ByteArray(size) { (tag * 31 + it).toByte() }

    private val basepoint = ByteArray(32).also { it[0] = 9 }

    private fun pub(priv: DhPrivateKey) = DhPublicKey(x25519(priv.bytes, basepoint))

    private val alicePriv = DhPrivateKey(bytes(1))
    private val bobPriv = DhPrivateKey(bytes(2))
    private val sk = SharedSecret(bytes(3))

    private fun alice() =
        KeyExchange.initSession(
            sk = sk,
            isAlice = true,
            myDhPriv = alicePriv,
            myDhPub = pub(alicePriv),
            theirDhPub = pub(bobPriv),
            aliceFp = fingerprint(pub(alicePriv)),
            bobFp = fingerprint(pub(bobPriv)),
        )

    private fun bob() =
        KeyExchange.initSession(
            sk = sk,
            isAlice = false,
            myDhPriv = bobPriv,
            myDhPub = pub(bobPriv),
            theirDhPub = pub(alicePriv),
            aliceFp = fingerprint(pub(alicePriv)),
            bobFp = fingerprint(pub(bobPriv)),
        )

    private fun encode(state: SessionState) = json.encodeToString(SessionState.serializer(), state)

    @Test
    fun initialSessionJsonIsUnchanged() {
        assertEquals(ALICE_INIT_JSON, encode(alice()))
        assertEquals(BOB_INIT_JSON, encode(bob()))
    }

    @Test
    fun persistedJsonRoundTrips() {
        val state = alice().copy(skippedMessageKeys = mapOf("ab:1" to SkippedMessageKey.decode(bytes(9, 52))!!), sendSeq = 3, pn = 2)
        val encoded = encode(state)
        assertEquals(state, json.decodeFromString(SessionState.serializer(), encoded))
        assertEquals(encoded, encode(json.decodeFromString(SessionState.serializer(), encoded)))
    }

    @Test
    fun legacyBlobWithoutDefaultedFieldsLoads() {
        val state = json.decodeFromString(SessionState.serializer(), LEGACY_JSON)
        assertEquals(LEGACY_REENCODED_JSON, encode(state))
        // The fields it stored as "" load as null.
        listOf(state.localFp, state.remoteFp, state.headerKey, state.sendHeaderKey, state.nextHeaderKey, state.prevSendHeaderKey)
            .forEach { assertNull(it) }
        // Such a session can't seal a header; it fails cleanly instead of with an AEAD error.
        assertFailsWith<SessionBrickedException> { Session.encryptMessage(state, MessagePlaintext.Keepalive()) }
    }

    @Test
    fun encryptAndDecryptAreUnchanged() {
        val plaintext = Session.padToFixedSize("""{"type":"ka"}""".encodeToByteArray(), PADDING_SIZE)
        val (aliceNext, msg) = Session.encryptPadded(alice(), plaintext, SystemRng)
        assertEquals(CT_HEX, msg.ct.toHex())
        assertEquals(ALICE_AFTER_SEND_JSON, encode(aliceNext))

        val (bobNext, decoded) = Session.decryptMessage(bob(), msg).delivered()
        assertEquals(true, decoded is MessagePlaintext.Keepalive)
        assertEquals(BOB_AFTER_RECV_JSON, encode(bobNext))
    }

    @Test
    fun sessionStoredWithoutLocalAndRemoteFingerprintsStillWorks() {
        // Sessions stored before localFp/remoteFp existed load them as null; message AAD must
        // still bind the real fingerprints.
        val legacyAlice = alice().copy(localFp = null, remoteFp = null)
        val ka = Session.padToFixedSize("""{"type":"ka"}""".encodeToByteArray(), PADDING_SIZE)

        val (_, toBob) = Session.encryptPadded(legacyAlice, ka.copyOf(), SystemRng)
        assertEquals(true, Session.decryptMessage(bob(), toBob).delivered().plaintext is MessagePlaintext.Keepalive)

        val (_, toAlice) = Session.encryptPadded(bob(), ka.copyOf(), SystemRng)
        assertEquals(true, Session.decryptMessage(legacyAlice, toAlice).delivered().plaintext is MessagePlaintext.Keepalive)
    }

    @Test
    fun ratchetDerivationsAreUnchanged() {
        val step = dhRatchetSubStep(alicePriv, pub(bobPriv), RootKey(bytes(4)))
        assertEquals(RATCHET_STEP_HEX, step.newRootKey.bytes.toHex() + step.newChainKey.bytes.toHex() + step.newHeaderKey.bytes.toHex())
        val chain = kdfCk(ChainKey(bytes(5)))
        assertEquals(CHAIN_STEP_HEX, chain.newChainKey.bytes.toHex() + chain.messageKey.bytes.toHex() + chain.messageNonce.bytes.toHex())
        assertEquals(ROUTING_TOKEN_HEX, deriveRoutingToken(RootKey(bytes(6)), Fingerprint(bytes(7)), Fingerprint(bytes(8))).toHex())
        assertEquals(FINGERPRINT_HEX, fingerprint(DhPublicKey(bytes(10))).toHex())
    }

    private companion object {
        const val ALICE_INIT_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"7H6aN9e49CWj6HbAZt80DgNaL9kegEBm061VtoC9mDA=","recvChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","sendToken":"/Z3qwZHICw5JFIk3sPA/UA==","recvToken":"asexbFVWrRKXipGYujA+Ag==","sendSeq":0,"recvSeq":0,"localDhPriv":"HyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4=","localDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","remoteDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","localFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","remoteFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","prevSendToken":"/Z3qwZHICw5JFIk3sPA/UA==","prevSendHeaderKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","isAlice":true,"skippedMessageKeys":{},"pn":0,"pr":0,"headerKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","sendHeaderKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","nextHeaderKey":"Ozuubnm0Eq2GOugxlRrTal8PVTqxRNLZkBEGut4Dzp4="}"""
        const val BOB_INIT_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","recvChainKey":"7H6aN9e49CWj6HbAZt80DgNaL9kegEBm061VtoC9mDA=","sendToken":"asexbFVWrRKXipGYujA+Ag==","recvToken":"/Z3qwZHICw5JFIk3sPA/UA==","sendSeq":0,"recvSeq":0,"localDhPriv":"Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF0=","localDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","remoteDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","localFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","remoteFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","prevSendToken":"asexbFVWrRKXipGYujA+Ag==","prevSendHeaderKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","isAlice":false,"skippedMessageKeys":{},"pn":0,"pr":0,"headerKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","sendHeaderKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","nextHeaderKey":"Ozuubnm0Eq2GOugxlRrTal8PVTqxRNLZkBEGut4Dzp4="}"""
        const val LEGACY_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"7H6aN9e49CWj6HbAZt80DgNaL9kegEBm061VtoC9mDA=","recvChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","sendToken":"/Z3qwZHICw5JFIk3sPA/UA==","recvToken":"asexbFVWrRKXipGYujA+Ag==","sendSeq":0,"recvSeq":0,"localDhPriv":"HyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4=","localDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","remoteDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","prevSendToken":"/Z3qwZHICw5JFIk3sPA/UA==","isAlice":true}"""
        const val LEGACY_REENCODED_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"7H6aN9e49CWj6HbAZt80DgNaL9kegEBm061VtoC9mDA=","recvChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","sendToken":"/Z3qwZHICw5JFIk3sPA/UA==","recvToken":"asexbFVWrRKXipGYujA+Ag==","sendSeq":0,"recvSeq":0,"localDhPriv":"HyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4=","localDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","remoteDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","localFp":"","remoteFp":"","prevSendToken":"/Z3qwZHICw5JFIk3sPA/UA==","prevSendHeaderKey":"","isAlice":true,"skippedMessageKeys":{},"pn":0,"pr":0,"headerKey":"","sendHeaderKey":"","nextHeaderKey":""}"""
        const val CT_HEX =
            """e611b42fd989d7974fb099d8ccbd46f8420bd3025743fbdd17bad0351a5c9e79a848c5a3a5511d1e8a9461f1e0e34a5a29db0fa65de884d1cfc0d4a12846ad787069539c66b7b0fed10a5a7b8526e5cff1544ff01cdb028773b156e272e51a19e48f8f3fe7dade58b66dbcd021ab4f1607f35e58929963a56d60522323a304f29941daeef6ab635e9864836ad622ac92e4b61af34a92e121f3730463b797fbb492b7c4fdcd6f0bc359fcd154246f22af9e7b1644d04d7f6286b943ff7b6c87004dba5af6672bc31d363a7e00e45164e9e912c135c8c0974d3c521e522810510f9d01fbf89adc379bfd4cc7821e357ee4501d6ef3f49cbe5fee5c857436b0b86ab680f08c60513b7099c58fab9aae812c7f9793da97dc994c03fcf6507fdcb16ad8754aced31cf088321e00a7daaee9b78bc1a52626b83dccc14749f6ace06fa55680522a8bd557edf3beccc9b9aa3a389837fae553be0c3624e042e8f626ee76f746981a93071c141cec6a5187850ec52d5ea83b1147367f140231dbf005d507ced23d6956fb3f75f33fa88c880c360123a8a22bf735e641bc354fd36d2616591deafd049f4636003d4fe7210385668693ef876edbe89acf69fd02543cf9352ef4de69b43f790913b40c1efc416c4a9b6f1751756732ae3a03965cb17d7e32b833538fa19bf6fc28a82d1024090ff510bdf087ffeda0b3399a89f47440156fce3bfd356ef09c1611afcd4cbcdbab4d7d"""
        const val ALICE_AFTER_SEND_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"iC7Xc5aapmR/rDiESo8t+bWbnJhBbKHV2mEaD8zmczc=","recvChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","sendToken":"/Z3qwZHICw5JFIk3sPA/UA==","recvToken":"asexbFVWrRKXipGYujA+Ag==","sendSeq":1,"recvSeq":0,"localDhPriv":"HyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4=","localDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","remoteDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","localFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","remoteFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","prevSendToken":"/Z3qwZHICw5JFIk3sPA/UA==","prevSendHeaderKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","isAlice":true,"skippedMessageKeys":{},"pn":0,"pr":0,"headerKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","sendHeaderKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","nextHeaderKey":"Ozuubnm0Eq2GOugxlRrTal8PVTqxRNLZkBEGut4Dzp4="}"""
        const val BOB_AFTER_RECV_JSON =
            """{"rootKey":"G5tYCnwAWwEVRdWEuBJNtm9KwFUN9SFMo21OaSjScA8=","sendChainKey":"6OvFr8YpzS3sna9eNVQSG9QWF5A4PTHlETHXuIGF75Q=","recvChainKey":"iC7Xc5aapmR/rDiESo8t+bWbnJhBbKHV2mEaD8zmczc=","sendToken":"asexbFVWrRKXipGYujA+Ag==","recvToken":"/Z3qwZHICw5JFIk3sPA/UA==","sendSeq":0,"recvSeq":1,"localDhPriv":"Pj9AQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVpbXF0=","localDhPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","remoteDhPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","aliceEkPub":"2dmFJ3SVE6mbrTieGecfOBns0suheCdD9KcdYC8fBTU=","bobEkPub":"DrvhaxsFSG0uuh8stQV4JBBnSrRyDjJDsU4IyGfujCU=","aliceFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","bobFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","localFp":"QJu5ykhBUcLCQvq1+DkeTtRU+ZiaPVNH45KW/dItWS4=","remoteFp":"hLxpDEJTs5rMcpEticXzSkyvX3l2jxZNwr4gE5CvcZ0=","prevSendToken":"asexbFVWrRKXipGYujA+Ag==","prevSendHeaderKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","isAlice":false,"skippedMessageKeys":{},"pn":0,"pr":0,"headerKey":"6EFwFcWqmW1pOJ/skMsbh8jYaJnbDLtQqqaT4LDgPhM=","sendHeaderKey":"MtX0RZ4tDBxaX6o4o83yOExhgPtwTzBh0ZH/Dfgtmno=","nextHeaderKey":"Ozuubnm0Eq2GOugxlRrTal8PVTqxRNLZkBEGut4Dzp4="}"""
        const val RATCHET_STEP_HEX =
            """56ebf7901aa39edc0bad67dd6966e26936bf1ba59f91dcba696c27e69141705ccb6f4be90426e5e0720357884ca6ae896f1b569eec4c735d039b0a54d93b0ddc0f3764da3ce89842b54c0df332ff1a641219ceeda1417dfcb6df027c716e2c70"""
        const val CHAIN_STEP_HEX =
            """cbc2a218609e0eb74a5d27dda09e0f326d7319be590d87d92abf4041a7885319123e2ec368138f073d8541fd7b7c5ab9c65ce22956b25f449c249debaebe69aaf2a5fc0ac3e68d91e4c11300"""
        const val ROUTING_TOKEN_HEX = "fd9f2d795e52ac59ed56b369be2671a4"
        const val FINGERPRINT_HEX = "990f285dab2fc0d19e23c484ae29c8cf5e2a949a343c228980b3bcdd3e7ff6e0"
    }
}
