package net.af0.where

import android.app.Application
import net.af0.where.e2ee.E2eeStore
import net.af0.where.e2ee.LocationClient

open class WhereApplication : Application() {
    open val e2eeStore: E2eeStore by lazy { E2eeStore(SharedPrefsE2eeStorage(this)) }
    val locationClient: LocationClient by lazy { LocationClient(BuildConfig.SERVER_HTTP_URL, e2eeStore) }

    override fun onCreate() {
        super.onCreate()
        initializeLibsodium()
    }
}
