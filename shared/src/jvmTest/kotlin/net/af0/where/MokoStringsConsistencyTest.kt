package net.af0.where

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every language's strings.xml must list exactly base's keys, in base's order.
 *
 * moko-resources builds MR.strings by merging the language files in directory-listing order, so
 * a key missing from (or out of place in) one language moves its field depending on which file
 * the filesystem returns first. That changes classes.dex between machines and fails F-Droid's
 * reproducible-build check against our GitHub release APKs (v2026.10.09.1, v2026.10.14.2).
 */
class MokoStringsConsistencyTest {
    private val dir = File("src/commonMain/moko-resources")
    private val name = Regex("""<(?:string|plurals|string-array) name="([^"]+)"""")

    private fun keys(lang: String) = name.findAll(File(dir, "$lang/strings.xml").readText()).map { it.groupValues[1] }.toList()

    @Test
    fun everyLanguageHasBaseKeysInBaseOrder() {
        val base = keys("base")
        val langs = dir.listFiles { f -> f.isDirectory && f.name != "base" }!!.map { it.name }.sorted()
        assertTrue(langs.isNotEmpty(), "no translations found under ${dir.absolutePath}")
        for (lang in langs) {
            assertEquals(base, keys(lang), "$lang/strings.xml keys differ from base/strings.xml (set or order)")
        }
    }
}
