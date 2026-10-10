package net.af0.where

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The SQLite database holds live Double Ratchet session state. A restored or cloned copy
 * rolls back send counters and reuses (key, nonce) pairs (spec §5.5), so no app data may
 * leave the device via cloud backup or device-to-device transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestWhereApplication::class)
class BackupExclusionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun cloudBackupIsDisabled() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun dataExtractionRulesExcludeEveryDomainFromCloudBackupAndDeviceTransfer() {
        // ApplicationInfo.dataExtractionRulesRes is hidden API, so check the manifest source.
        val manifest = java.io.File("src/androidMain/AndroidManifest.xml").readText()
        assertTrue(
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
            "AndroidManifest.xml must set android:dataExtractionRules",
        )
        val rulesRes = context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertNotEquals(0, rulesRes, "res/xml/data_extraction_rules.xml must exist")

        val excluded = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        val parser = context.resources.getXml(rulesRes)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "cloud-backup", "device-transfer" -> section = parser.name
                "include" -> error("data extraction rules must not include anything")
                "exclude" -> {
                    val path = parser.getAttributeValue(ANDROID_NS, "path") ?: parser.getAttributeValue(null, "path")
                    val domain = parser.getAttributeValue(ANDROID_NS, "domain") ?: parser.getAttributeValue(null, "domain")
                    if (path == ".") excluded.getOrPut(section!!) { mutableSetOf() }.add(domain)
                }
            }
        }

        val required = setOf("root", "file", "database", "sharedpref", "external")
        for (s in listOf("cloud-backup", "device-transfer")) {
            assertTrue(excluded[s].orEmpty().containsAll(required), "$s must exclude $required, got ${excluded[s]}")
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
