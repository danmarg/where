package net.af0.where

import android.content.Context
import android.content.SharedPreferences
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.UserStore
import net.af0.where.e2ee.RawKeyValueStorage
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.af0.where.db.WhereDatabase

private fun createTestSqlDriver(context: android.content.Context): SqlDriver {
    return AndroidSqliteDriver(
        WhereDatabase.Schema,
        context,
        null
    )
}

/** In-memory RawKeyValueStorage for unit tests (Android Keystore is unavailable in Robolectric). */
private class InMemoryRawKeyValueStorage : RawKeyValueStorage {
    private val data = mutableMapOf<String, String>()
    override fun getString(key: String): String? = data[key]
    override fun putString(key: String, value: String) { data[key] = value }
}

class TestWhereApplication : WhereApplication() {
    private val inMemoryStorage = InMemoryRawKeyValueStorage()

    override val encryptedPrefs: SharedPreferences by lazy {
        getSharedPreferences("test_encrypted_prefs", Context.MODE_PRIVATE)
    }

    override val e2eeManager: E2eeManager by lazy { E2eeManager(createTestSqlDriver(this)) }
    override val userStore: UserStore by lazy { UserStore(inMemoryStorage) }
}
