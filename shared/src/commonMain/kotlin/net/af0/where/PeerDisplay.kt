package net.af0.where

import net.af0.where.e2ee.FriendEntry

/**
 * Cross-platform rendering rule for a peer's pin and label.
 *
 * Precedence (highest first):
 *   1. StoppedAt within the dim window  → dimmed pin, "stopped sharing at HH:mm"
 *   2. StoppedAt outside the dim window → no pin, "stopped sharing on <date>"
 *   3. StationarySince                  → normal pin, "here since HH:mm"
 *   4. fallback                         → normal pin, "last seen Xh ago"
 *
 * All timestamps are epoch-seconds.
 */
sealed class PeerDisplay {
    abstract val pinStyle: PeerPinStyle

    data class LastSeen(val timestampSeconds: Long?) : PeerDisplay() {
        override val pinStyle = PeerPinStyle.NORMAL
    }
    data class StationarySince(val timestampSeconds: Long) : PeerDisplay() {
        override val pinStyle = PeerPinStyle.NORMAL
    }
    data class StoppedRecently(val timestampSeconds: Long) : PeerDisplay() {
        override val pinStyle = PeerPinStyle.DIMMED
    }
    data class StoppedLongAgo(val timestampSeconds: Long) : PeerDisplay() {
        override val pinStyle = PeerPinStyle.HIDDEN
    }
}

enum class PeerPinStyle { NORMAL, DIMMED, HIDDEN }

const val STOPPED_PIN_DIM_WINDOW_SECONDS: Long = 6L * 3600L

/**
 * Single source of truth for how a peer should render. The receive-time
 * `lastPingSeconds` is passed in separately because it isn't on [FriendEntry]
 * (FriendEntry.lastTs is the *sender's* GPS timestamp; for "last seen X ago"
 * we want the wall clock when we received the message).
 */
fun FriendEntry.displayState(
    nowSeconds: Long,
    lastPingSeconds: Long?,
    dimWindowSeconds: Long = STOPPED_PIN_DIM_WINDOW_SECONDS,
): PeerDisplay {
    stoppedAtTs?.let { stopped ->
        return if (nowSeconds - stopped < dimWindowSeconds) {
            PeerDisplay.StoppedRecently(stopped)
        } else {
            PeerDisplay.StoppedLongAgo(stopped)
        }
    }
    stationarySinceTs?.let { return PeerDisplay.StationarySince(it) }
    return PeerDisplay.LastSeen(lastPingSeconds)
}
