package net.af0.where.e2ee

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Dispatchers.IO is internal in Kotlin/Native; Default is equivalent here since
// NativeSqliteDriver handles its own threading and there is no ANR concept on iOS.
internal actual val storageDispatcher: CoroutineDispatcher = Dispatchers.Default
