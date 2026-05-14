package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import net.af0.where.db.WhereDatabase

fun createIosSqlDriver(): SqlDriver {
    return NativeSqliteDriver(WhereDatabase.Schema, "where.db")
}
