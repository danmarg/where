package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import net.af0.where.db.WhereDatabase

actual fun createTestSqlDriver(name: String?): SqlDriver {
    return inMemoryDriver(WhereDatabase.Schema)
}
