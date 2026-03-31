package net.af0.where.e2ee

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import kotlin.system.exitProcess

/**
 * JVM test setup for E2EE tests.
 * Initializes libsodium once before any tests run.
 */
object E2eeTestSetup {
    init {
        try {
            LibsodiumInitializer.initializeWithCallback {
                // Libsodium initialized successfully
            }
        } catch (e: Exception) {
            System.err.println("Failed to initialize libsodium: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
    }

    fun ensureInitialized() {
        // Just accessing the object triggers the init block
    }
}
