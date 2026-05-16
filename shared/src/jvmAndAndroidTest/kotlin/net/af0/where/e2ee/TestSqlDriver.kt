package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import net.af0.where.db.WhereDatabase

actual fun createTestSqlDriver(name: String?): SqlDriver {
    val driver =
        if (name == null) {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        } else {
            JdbcSqliteDriver("jdbc:sqlite:$name")
        }
    if (name == null || !java.io.File(name).exists() || java.io.File(name).length() == 0L) {
        WhereDatabase.Schema.create(driver)
    }
    return driver
}
