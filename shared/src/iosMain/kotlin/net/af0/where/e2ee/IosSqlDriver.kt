@file:OptIn(ExperimentalForeignApi::class)

package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import kotlinx.cinterop.ExperimentalForeignApi
import net.af0.where.db.WhereDatabase
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

fun createIosSqlDriver(): SqlDriver = createIosSqlDriver("where.db")

fun createIosSqlDriver(name: String): SqlDriver {
    var config: DatabaseConfiguration? = null
    val driver = NativeSqliteDriver(WhereDatabase.Schema, name, onConfiguration = { c -> c.also { config = it } })
    config?.takeUnless { it.inMemory }?.let { c ->
        val dir = databaseDirectory(c)
        protectDatabaseDirectory(dir, c.name ?: "")
    }
    return driver
}

/** Directory SQLiter stores the database named [name] in (default base path). */
fun iosDatabaseDirectory(name: String): String = DatabaseFileContext.databasePath(name, null).substringBeforeLast('/')

private fun databaseDirectory(config: DatabaseConfiguration): String =
    DatabaseFileContext.databasePath(config.name ?: "", config.extendedConfig.basePath).substringBeforeLast('/')

/**
 * The database holds live Double Ratchet session state (root/chain/header keys, DH private
 * keys) and pending-invite private keys. It must never be backed up: a restored copy rolls
 * back send counters and reuses (key, nonce) pairs (spec §5.5). Excluding the directory
 * covers the -wal/-shm sidecar files too.
 *
 * The protection class pins the current iOS default (the app has no data-protection
 * entitlement) so the files stay readable for background location relaunches after first
 * unlock. A directory's class only applies to files created later, so existing files are
 * set individually too.
 */
private fun protectDatabaseDirectory(
    dir: String,
    name: String,
) {
    val excluded = NSURL.fileURLWithPath(dir).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
    if (!excluded) println("WARNING: failed to exclude database directory from backup: $dir")
    val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)
    val fm = NSFileManager.defaultManager
    for (path in listOf(dir, "$dir/$name", "$dir/$name-wal", "$dir/$name-shm")) {
        if (fm.fileExistsAtPath(path)) fm.setAttributes(protection, ofItemAtPath = path, error = null)
    }
}
