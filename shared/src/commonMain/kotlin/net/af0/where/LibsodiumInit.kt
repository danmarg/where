package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer

/**
 * Initialize libsodium for the application.
 * Call this once on app startup before using any crypto functions.
 */
fun initializeLibsodium() {
    LibsodiumInitializer.initializeWithCallback {
        // Libsodium initialized successfully
    }
}
