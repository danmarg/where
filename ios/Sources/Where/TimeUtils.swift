import Foundation
import Shared

func timeAgoString(_ date: Date?) -> String {
    guard let date = date else { return MR.strings().never.localized() }
    let seconds = Date().timeIntervalSince(date)
    if seconds < 60 { return MR.strings().just_now.localized() }
    if seconds < 3600 { return MR.strings().m_ago.localized(args: [Int32(seconds / 60)]) }
    if seconds < 86400 { return MR.strings().h_ago.localized(args: [Int32(seconds / 3600)]) }
    return MR.strings().d_ago.localized(args: [Int32(seconds / 86400)])
}

func timeAgoStringFromMs(_ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
    return timeAgoString(date)
}

func timeAgoStringFromSeconds(_ seconds: Int64) -> String {
    guard seconds != 0 else { return MR.strings().never.localized() }
    let date = Date(timeIntervalSince1970: Double(seconds))
    return timeAgoString(date)
}

private let shortTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateStyle = .none
    f.timeStyle = .short
    return f
}()

private let shortDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateStyle = .short
    f.timeStyle = .none
    return f
}()

/// Renders the human-readable subtitle for the recipient's pin / friend list row,
/// matching the shared PeerDisplay rule.
func peerSubtitleText(_ display: Shared.PeerDisplay) -> String {
    if let recent = display as? Shared.PeerDisplay.StoppedRecently {
        let d = Date(timeIntervalSince1970: TimeInterval(recent.timestampSeconds))
        return "stopped sharing at \(shortTimeFormatter.string(from: d))"
    }
    if let longAgo = display as? Shared.PeerDisplay.StoppedLongAgo {
        let d = Date(timeIntervalSince1970: TimeInterval(longAgo.timestampSeconds))
        return "stopped sharing on \(shortDateFormatter.string(from: d))"
    }
    if let stationary = display as? Shared.PeerDisplay.StationarySince {
        let d = Date(timeIntervalSince1970: TimeInterval(stationary.timestampSeconds))
        return "here since \(shortTimeFormatter.string(from: d))"
    }
    if let lastSeen = display as? Shared.PeerDisplay.LastSeen {
        if let ts = lastSeen.timestampSeconds?.int64Value {
            return timeAgoStringFromSeconds(ts)
        }
        return timeAgoStringFromSeconds(0)
    }
    return timeAgoStringFromSeconds(0)
}
