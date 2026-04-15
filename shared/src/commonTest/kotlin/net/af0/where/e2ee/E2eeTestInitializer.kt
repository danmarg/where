package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/**
 * Singleton that ensures libsodium is initialized exactly once for tests.
 * This is used across all common/jvm/android/ios test targets.
 */
object E2eeTestInitializer {
    private var initialized = false

    init {
        initialize()
    }

    fun ensureInitialized() {
        // Accessing the object triggers the init block
    }

    private fun initialize() {
        if (initialized) return
        try {
            LibsodiumInitializer.initializeWithCallback {
                initialized = true
            }
        } catch (e: Throwable) {
            println("Failed to initialize libsodium: ${e.message}")
            throw e
        }
    }
}

/**
 * Convenience function to initialize libsodium for E2EE tests.
 * Call this in the init {} or @BeforeTest block of any E2EE crypto test.
 */
fun initializeE2eeTests() {
    E2eeTestInitializer.ensureInitialized()
}
