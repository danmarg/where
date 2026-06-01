package net.af0.where.e2ee

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val storageDispatcher: CoroutineDispatcher = Dispatchers.IO
