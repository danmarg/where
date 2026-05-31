import XCTest
import Shared
@testable import Where

final class StringResourceExtensionsTests: XCTestCase {
    /// moko-resources XML templates use Android's %s for String placeholders.
    /// Swift's String(format:) requires %@. The shared helper must normalize so
    /// callers don't have to know the difference.
    func testSharingForRemaining_StringPlaceholder() {
        let formatted = MR.strings().sharing_for_remaining.localized(args: ["1h 12m" as NSString])
        XCTAssertTrue(formatted.contains("1h 12m"),
                      "expected formatted to contain the substituted value, got: \(formatted)")
        XCTAssertFalse(formatted.contains("%s"),
                       "raw %s should not survive into the formatted string")
        XCTAssertFalse(formatted.contains("%@"),
                       "raw %@ should not survive into the formatted string")
    }

    /// %d placeholders should keep working unmodified.
    func testMAgo_IntPlaceholder() {
        let formatted = MR.strings().m_ago.localized(args: [Int32(7)])
        XCTAssertTrue(formatted.contains("7"),
                      "expected formatted to contain the substituted value, got: \(formatted)")
        XCTAssertFalse(formatted.contains("%d"))
    }
}
