package net.af0.where.e2ee

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] that throws on the next statement containing [failOn], standing in for a crash or
 * a full disk at that point. Inside a transaction the throw rolls the whole transaction back.
 */
class FailingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    var failOn: String? = null

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val pattern = failOn
        if (pattern != null && sql.contains(pattern)) {
            failOn = null
            throw IllegalStateException("injected failure on: $pattern")
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
