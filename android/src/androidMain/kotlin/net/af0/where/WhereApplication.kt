package net.af0.where

import android.app.Application

class WhereApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize libsodium for crypto operations
        initializeLibsodium()
    }
}
