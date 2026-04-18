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
    let date = Date(timeIntervalSince1970: Double(seconds))
    return timeAgoString(date)
}
