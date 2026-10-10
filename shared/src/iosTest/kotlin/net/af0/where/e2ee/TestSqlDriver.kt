package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import net.af0.where.db.WhereDatabase

// Mirrors the JVM test driver: null = fresh in-memory database, otherwise a named on-disk
// database that persists across calls (for tests that reopen a store).
actual fun createTestSqlDriver(name: String?): SqlDriver =
    if (name == null) inMemoryDriver(WhereDatabase.Schema) else NativeSqliteDriver(WhereDatabase.Schema, name)
