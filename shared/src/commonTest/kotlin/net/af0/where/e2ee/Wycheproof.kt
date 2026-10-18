package net.af0.where.e2ee

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads the Wycheproof vector files vendored in src/commonTest/resources/wycheproof (see the
 * README there), which the build compiles into [WycheproofFiles] so iOS tests see them too.
 */
internal object Wycheproof {
    class Case(private val group: JsonObject, private val test: JsonObject) {
        val tcId: Int get() = test.getValue("tcId").jsonPrimitive.int
        val comment: String get() = test.getValue("comment").jsonPrimitive.content
        val result: String get() = test.getValue("result").jsonPrimitive.content
        val flags: List<String> get() = test.getValue("flags").jsonArray.map { it.jsonPrimitive.content }

        fun hex(field: String): ByteArray = test.getValue(field).jsonPrimitive.content.hexToByteArray()

        fun int(field: String): Int = test.getValue(field).jsonPrimitive.int

        /** An integer parameter of the case's test group, e.g. ivSize or tagSize (in bits). */
        fun groupInt(field: String): Int = group.getValue(field).jsonPrimitive.int

        override fun toString() = "tcId $tcId ($comment, $result, $flags)"
    }

    fun load(file: String): List<Case> {
        val root = Json.parseToJsonElement(WycheproofFiles.read(file)).jsonObject
        val cases =
            root.getValue("testGroups").jsonArray.flatMap { g ->
                val group = g.jsonObject
                group.getValue("tests").jsonArray.map { Case(group, it.jsonObject) }
            }
        check(cases.size == root.getValue("numberOfTests").jsonPrimitive.int) { "$file: test count mismatch" }
        return cases
    }

    /**
     * Runs [check] on every case of [file] and fails once, listing every case that failed, so a
     * broken primitive reports all of its divergences rather than the first.
     */
    fun verify(
        file: String,
        check: (Case) -> String?,
    ) {
        val cases = load(file)
        val failures =
            cases.mapNotNull { case ->
                val problem =
                    try {
                        check(case)
                    } catch (e: Throwable) {
                        "unexpected ${e::class.simpleName}: ${e.message}"
                    }
                problem?.let { "$case: $it" }
            }
        if (failures.isNotEmpty()) {
            throw AssertionError("$file: ${failures.size} of ${cases.size} cases failed:\n" + failures.joinToString("\n"))
        }
    }
}
