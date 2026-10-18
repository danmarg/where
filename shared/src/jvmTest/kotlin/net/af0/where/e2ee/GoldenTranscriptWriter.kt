package net.af0.where.e2ee

import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * Rewrites the golden transcript from this checkout:
 * `WHERE_WRITE_VECTORS=1 ./gradlew :shared:jvmTest --tests '*GoldenTranscriptWriter*'`.
 * Does nothing otherwise. Only for an intended protocol change (see GoldenTranscriptTest).
 */
class GoldenTranscriptWriter {
    init {
        initializeE2eeTests()
    }

    @Test
    fun write() {
        if (System.getenv("WHERE_WRITE_VECTORS") != "1") return
        val out = File("src/commonTest/resources/vectors/${GoldenTranscript.FILE}")
        out.writeText(GoldenTranscript.prettyJson.encodeToString(JsonObject.serializer(), GoldenTranscript.generate()) + "\n")
        println("wrote ${out.absolutePath}")
    }
}
