package net.af0.where

import android.app.Application
import android.content.SharedPreferences
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient
import net.af0.where.e2ee.UserStore

open class WhereApplication : Application() {
    open val encryptedPrefs: SharedPreferences by lazy {
        SharedPrefsE2eeStorage.createEncryptedPrefs(this)
    }

    open val e2eeStore: E2eeStore by lazy { E2eeStore(SharedPrefsE2eeStorage(this)) }
    open val userStore: UserStore by lazy { UserStore(SharedPrefsE2eeStorage(this)) }
    val locationClient: LocationClient by lazy { LocationClient(BuildConfig.SERVER_HTTP_URL, e2eeStore) }

    override fun onCreate() {
        super.onCreate()
        initializeLibsodium()
    }
}
