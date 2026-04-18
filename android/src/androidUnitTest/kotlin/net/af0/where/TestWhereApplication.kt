package net.af0.where

import android.content.Context
import android.content.SharedPreferences
import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.UserStore

/** In-memory E2eeStorage for unit tests (Android Keystore is unavailable in Robolectric). */
private class InMemoryE2eeStorage : E2eeStorage {
    private val data = mutableMapOf<String, String>()

    override fun getString(key: String): String? = data[key]

    override fun putString(
        key: String,
        value: String,
    ) {
        data[key] = value
    }
}

class TestWhereApplication : WhereApplication() {
    private val inMemoryStorage = InMemoryE2eeStorage()

    override val encryptedPrefs: SharedPreferences by lazy {
        getSharedPreferences("test_encrypted_prefs", Context.MODE_PRIVATE)
    }

    override val e2eeStore: E2eeStore by lazy { E2eeStore(inMemoryStorage) }
    override val userStore: UserStore by lazy { UserStore(inMemoryStorage) }
}
