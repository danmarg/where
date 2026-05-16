package net.af0.where.e2ee

import app.cash.sqldelight.db.SqlDriver

expect fun createTestSqlDriver(name: String? = null): SqlDriver
