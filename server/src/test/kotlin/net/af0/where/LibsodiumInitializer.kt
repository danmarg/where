package net.af0.where

import com.ionspin.kotlin.crypto.LibsodiumInitializer

object LibsodiumInitializer {
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
