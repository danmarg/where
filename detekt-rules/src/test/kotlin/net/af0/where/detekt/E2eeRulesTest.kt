package net.af0.where.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class E2eeRulesTest {
    private fun rawKeyBytesCount(code: String) = RawKeyBytes(Config.empty).lint(code).size

    @Test
    fun rawKeyBytesFlagsKeyNamedByteArraysInVisibleSignatures() {
        assertEquals(1, rawKeyBytesCount("class A(val rootKey: ByteArray)"))
        assertEquals(1, rawKeyBytesCount("internal fun derive(ikm: ByteArray): Int = 0"))
        assertEquals(1, rawKeyBytesCount("fun token(): ByteArray = ByteArray(16)"))
        assertEquals(1, rawKeyBytesCount("class A { val sendToken: ByteArray? = null }"))
    }

    @Test
    fun rawKeyBytesIgnoresOtherNamesPrivateDeclarationsAndTypedKeys() {
        assertEquals(0, rawKeyBytesCount("fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = plaintext"))
        assertEquals(0, rawKeyBytesCount("private fun derive(ikm: ByteArray) = 0"))
        assertEquals(0, rawKeyBytesCount("private class A(val rootKey: ByteArray)"))
        assertEquals(0, rawKeyBytesCount("class A(val rootKey: RootKey)"))
        assertEquals(0, rawKeyBytesCount("fun f() { val headerKey: ByteArray = ByteArray(0); listOf(1).map { key: Int -> key } }"))
    }

    @Test
    fun secretComparisonFlagsEqualityOnSecretsAndRawBytes() {
        val rule = SecretComparison(Config.empty)
        assertEquals(1, rule.lint("fun f(a: S, b: S) = a.rootKey == b.rootKey").size)
        assertEquals(1, rule.lint("fun f(a: S, b: S) = a.sk != b").size)
        assertEquals(1, rule.lint("fun f(a: K, b: K) = a.bytes.contentEquals(b.bytes)").size)
        assertEquals(1, rule.lint("fun f(a: S, b: ByteArray) = b.contentEquals(a.messageKey)").size)
        assertEquals(1, rule.lint("fun f(a: S, b: S) = a.headerKey.equals(b.headerKey)").size)
    }

    @Test
    fun secretComparisonAllowsConstantTimeAndPublicValues() {
        val rule = SecretComparison(Config.empty)
        assertEquals(0, rule.lint("fun f(a: S, b: S) = a.rootKey.constantTimeEquals(b.rootKey)").size)
        assertEquals(0, rule.lint("fun f(a: S, b: S) = a.remoteDhPub == b.remoteDhPub").size)
        assertEquals(0, rule.lint("fun f(a: ByteArray, b: ByteArray) = a.contentEquals(b)").size)
    }

    @Test
    fun insecureRandomFlagsKotlinAndJavaRandom() {
        val rule = InsecureRandom(Config.empty)
        assertEquals(1, rule.lint("import kotlin.random.Random\nfun f() = 1").size)
        assertEquals(1, rule.lint("import java.util.Random\nfun f() = 1").size)
        assertEquals(1, rule.lint("fun f() = kotlin.random.Random.nextBytes(32)").size)
        assertEquals(1, rule.lint("fun f() = Random.nextBytes(32)").size)
        assertEquals(0, rule.lint("fun f() = randomBytes(32)\nclass RandomNonceKey").size)
    }

    @Test
    fun secretInLogFlagsSecretsInTemplatesAndLogCalls() {
        val rule = SecretInLog(Config.empty)
        assertEquals(1, rule.lint("fun f(s: S) = \"key=\${s.rootKey}\"").size)
        assertEquals(1, rule.lint("fun f(s: S) = \"key=\${s.rootKey.bytes.toHex()}\"").size)
        assertEquals(1, rule.lint("fun f(sk: S) = \"\$sk\"").size)
        assertEquals(1, rule.lint("fun f(s: S) { addDiagnosticEvent(s.headerKey) }").size)
        assertEquals(1, rule.lint("fun f(k: K) { println(k.bytes) }").size)
    }

    @Test
    fun secretInLogAllowsPublicValues() {
        val rule = SecretInLog(Config.empty)
        assertEquals(0, rule.lint("fun f(s: S, e: Exception) = \"seq \${s.recvSeq}: \${e.message}\"").size)
        assertEquals(0, rule.lint("fun f(s: S) { addDiagnosticEvent(\"poll on \${s.recvToken.toHex()}\") }").size)
        // A length is public.
        assertEquals(0, rule.lint("fun f(k: K) = \"\${k.bytes.size} bytes, \${k.rootKey.size}\"").size)
        assertEquals(0, rule.lint("class K(val bytes: ByteArray) { fun f() = \"got \${bytes.size}\" }").size)
        val requireLength =
            "class K(val bytes: ByteArray) { init { require(bytes.size == 32) { \"\${this::class.simpleName}: \${bytes.size}\" } } }"
        assertEquals(0, rule.lint(requireLength).size)
        // ...but not a hex of it.
        assertEquals(1, rule.lint("fun f(k: K) = \"\${k.bytes.toHex()}\"").size)
    }
}
