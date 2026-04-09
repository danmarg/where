package net.af0.where

import net.af0.where.e2ee.E2eeStorage
import net.af0.where.e2ee.E2eeStore

/** In-memory E2eeStorage for unit tests (Android Keystore is unavailable in Robolectric). */
private class InMemoryE2eeStorage : E2eeStorage {
    private val data = mutableMapOf<String, String>()

    override fun getString(key: String): String? = data[key]

    override fun putString(key: String, value: String) {
        data[key] = value
    }
}

class TestWhereApplication : WhereApplication() {
    override val e2eeStore: E2eeStore by lazy { E2eeStore(InMemoryE2eeStorage()) }
}
