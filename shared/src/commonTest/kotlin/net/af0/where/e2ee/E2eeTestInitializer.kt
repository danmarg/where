package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/**
 * Initialize libsodium for E2EE tests.
 * Call this once before running any E2EE crypto operations.
 */
fun initializeE2eeTests() {
    LibsodiumInitializer.initializeWithCallback {
        // Libsodium initialized successfully
    }
}
