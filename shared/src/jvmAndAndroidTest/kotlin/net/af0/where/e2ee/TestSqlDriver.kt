package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.af0.where.db.WhereDatabase

actual fun createTestSqlDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    WhereDatabase.Schema.create(driver)
    return driver
}
