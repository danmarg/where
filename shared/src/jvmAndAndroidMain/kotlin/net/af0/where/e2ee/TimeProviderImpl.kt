package net.af0.where.e2ee

actual fun currentTimeSeconds(): Long = System.currentTimeMillis() / 1000L

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
