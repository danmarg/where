package net.af0.where

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Release builds talk only to the production server, over HTTPS. Debug builds override the
 * config to reach a local development server (the emulator's host, 10.0.2.2) over plain HTTP.
 * Unit tests run against the debug variant, so check both source files directly.
 */
class NetworkSecurityConfigTest {
    private fun config(sourceSet: String) = File("src/$sourceSet/res/xml/network_security_config.xml").readText()

    @Test
    fun releaseConfigForbidsCleartextEverywhere() {
        val release = config("androidMain")
        assertTrue(release.contains("""<base-config cleartextTrafficPermitted="false""""), release)
        assertTrue(!release.contains("""cleartextTrafficPermitted="true""""), release)
    }

    @Test
    fun debugConfigAllowsCleartextToALocalServer() {
        assertTrue(config("androidDebug").contains("""<base-config cleartextTrafficPermitted="true""""))
    }

    @Test
    fun releaseServerIsHttps() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("""buildConfigField("String", "SERVER_HTTP_URL", "\"https://where-api.af0.net\"")"""))
    }
}
