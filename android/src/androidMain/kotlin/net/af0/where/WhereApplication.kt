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
        initMapLibre(this)
        // EncryptedSharedPreferences/Keystore master-key creation (including the StrongBox
        // provisioning attempt in SharedPrefsRawKeyValueStorage.buildMasterKey) can take
        // hundreds of ms on cold start. Warm it on a background thread here so it's usually
        // already done by the time LocationService.onCreate() or the ViewModel first touches
        // userStore/encryptedPrefs on the main thread. `by lazy` is thread-safe (synchronized
        // by default), so if the main thread gets there first it just blocks on the same
        // computation instead of triggering a second one — no behavior change, just usually
        // off the hot path that must call startForeground() in time.
        // Swallow any failure here: this thread exists purely to pre-compute the lazies before
        // the main thread needs them. If Keystore/EncryptedSharedPreferences setup is actually
        // broken, letting that exception escape this bare Thread would crash the process
        // immediately via the runtime's default uncaught-exception handler — worse than today,
        // where the same failure only surfaces (and crashes, identically) at the real call site
        // on the main thread. Swallowing it here just means the main thread redoes the (still
        // failing) computation and hits that same original failure path, unchanged.
        Thread {
            try {
                encryptedPrefs
                userStore
            } catch (_: Exception) {
                // Deliberately ignored — see comment above.
            }
        }.start()
    }
}
