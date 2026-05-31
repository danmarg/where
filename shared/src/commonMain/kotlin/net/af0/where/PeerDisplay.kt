package net.af0.where

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

fun peerDisplay(
    stoppedAtSeconds: Long?,
    stationarySinceSeconds: Long?,
    lastPingSeconds: Long?,
    nowSeconds: Long,
    dimWindowSeconds: Long = STOPPED_PIN_DIM_WINDOW_SECONDS,
): PeerDisplay {
    if (stoppedAtSeconds != null) {
        return if (nowSeconds - stoppedAtSeconds < dimWindowSeconds) {
            PeerDisplay.StoppedRecently(stoppedAtSeconds)
        } else {
            PeerDisplay.StoppedLongAgo(stoppedAtSeconds)
        }
    }
    if (stationarySinceSeconds != null) {
        return PeerDisplay.StationarySince(stationarySinceSeconds)
    }
    return PeerDisplay.LastSeen(lastPingSeconds)
}
