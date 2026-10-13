package net.af0.where.e2ee

import kotlin.test.fail

/** This result as [ReceivedMessage.Delivered], or a test failure naming what came back instead. */
fun ReceivedMessage.delivered(): ReceivedMessage.Delivered =
    this as? ReceivedMessage.Delivered ?: fail("expected Delivered, got ${describe()}")

fun ReceivedMessage.undeliverable(): ReceivedMessage.Undeliverable =
    this as? ReceivedMessage.Undeliverable ?: fail("expected Undeliverable, got ${describe()}")

fun ReceivedMessage.replay(): ReceivedMessage.Replay = this as? ReceivedMessage.Replay ?: fail("expected Replay, got ${describe()}")

/** This result as [ReceivedMessage.Rejected] with a cause of type [T]. */
inline fun <reified T : Exception> ReceivedMessage.rejectedWith(): T {
    val rejected = this as? ReceivedMessage.Rejected ?: fail("expected Rejected, got ${describe()}")
    return rejected.cause as? T ?: fail("expected Rejected(${T::class.simpleName}), got ${describe()}")
}

fun ReceivedMessage.describe(): String =
    when (this) {
        is ReceivedMessage.Delivered -> "Delivered($plaintext)"
        is ReceivedMessage.Undeliverable -> "Undeliverable($cause)"
        is ReceivedMessage.Replay -> "Replay($cause)"
        is ReceivedMessage.Rejected -> "Rejected($cause)"
    }
