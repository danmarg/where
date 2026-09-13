package net.af0.where

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * WhereApplication subclass that simulates a broken Keystore/EncryptedSharedPreferences setup
 * by throwing whenever encryptedPrefs is first accessed.
 */
class ThrowingWhereApplication : WhereApplication() {
    override val encryptedPrefs: SharedPreferences
        get() = throw RuntimeException("simulated Keystore failure")
}

/**
 * Regression test for the warm-up thread in WhereApplication.onCreate(): a
 * Keystore/EncryptedSharedPreferences failure there must be swallowed, not left to escape the
 * bare Thread and crash the process via the runtime's default uncaught-exception handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = ThrowingWhereApplication::class)
class WhereApplicationTest {
    @Test
    fun warmUpEncryptedStorage_swallowsException_doesNotPropagate() {
        val app = ApplicationProvider.getApplicationContext<ThrowingWhereApplication>()

        // Must not throw. Before the fix, this exception would have escaped the warm-up
        // Thread uncaught, crashing the process during app startup.
        app.warmUpEncryptedStorage()
    }
}
