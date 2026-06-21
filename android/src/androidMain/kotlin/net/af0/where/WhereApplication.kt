package net.af0.where

import android.app.Application
import android.content.SharedPreferences
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.af0.where.e2ee.E2eeManager
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.UserStore

open class WhereApplication : Application() {
    open val encryptedPrefs: SharedPreferences by lazy {
        SharedPrefsRawKeyValueStorage.createEncryptedPrefs(this)
    }

    open val e2eeManager: E2eeManager by lazy {
        E2eeManager(
            AndroidSqliteDriver(net.af0.where.db.WhereDatabase.Schema, this, "where.db"),
        )
    }
    open val userStore: UserStore by lazy { UserStore(SharedPrefsRawKeyValueStorage(this)) }
    val locationClient: LocationClient by lazy { LocationClient(BuildConfig.SERVER_HTTP_URL, e2eeManager) }
    open val locationSource: LocationSource by lazy { LocationRepository(userStore) }
    open val uiStateStore: UiStateSource by lazy { UiStateStore() }

    override fun onCreate() {
        super.onCreate()
        initializeLibsodium()
        initOsmdroid(this)
    }
}
