package net.af0.where.e2ee.fuzz

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fuzz targets as a regular test (:shared:fuzzCorpusTest): every committed corpus input, plus
 * a few thousand seeded random ones, through every target. Jazzer itself runs nightly.
 */
class FuzzCorpusTest {
    private val corpus = File(System.getProperty("fuzz.corpus") ?: "src/jvmFuzz/corpus")

    @Test
    fun everyTargetHasASeedCorpus() {
        val dirs = corpus.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }.toSet()
        assertEquals(fuzzTargets.keys, dirs, "corpus directories under $corpus")
        for (name in dirs) assertTrue(corpus.resolve(name).list().orEmpty().isNotEmpty(), "empty corpus for $name")
    }

    @Test
    fun corpusInputsHoldEveryProperty() {
        val failures =
            fuzzTargets.flatMap { (name, target) ->
                corpus.resolve(name).listFiles().orEmpty().sortedBy { it.name }.mapNotNull { file ->
                    run(target, file.readBytes())?.let { "$name/${file.name}: $it" }
                }
            }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun randomInputsHoldEveryProperty() {
        val failures =
            fuzzTargets.flatMap { (name, target) ->
                val random = Random(name.hashCode())
                (0 until 2_000).mapNotNull { i ->
                    val input = random.nextBytes(random.nextInt(0, 700))
                    run(target, input)?.let { "$name random #$i (${input.hex()}): $it" }
                }
            }
        if (failures.isNotEmpty()) fail(failures.take(20).joinToString("\n"))
    }

    private fun run(
        target: (ByteArray) -> Unit,
        input: ByteArray,
    ): String? =
        try {
            target(input)
            null
        } catch (e: Throwable) {
            e.stackTraceToString().lines().take(8).joinToString("\n")
        }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
