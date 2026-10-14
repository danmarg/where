import XCTest
@testable import Where

/// The app records the commit it was built from (project.yml's Stamp Git Commit phase), so a
/// TestFlight build or crash report can be traced to its source.
final class BuildInfoTests: XCTestCase {
    func testBuildIsStampedWithItsCommit() {
        let commit = Bundle.main.infoDictionary?["WhereGitCommit"] as? String
        XCTAssertNotNil(commit)
        XCTAssertNotEqual(commit, "unknown", "the Stamp Git Commit build phase didn't run")
        XCTAssertNotNil(commit?.range(of: "^[0-9a-f]{12}(-dirty)?$", options: .regularExpression), commit ?? "")
    }

    @MainActor
    func testBuildDescriptionShowsVersionBuildAndCommit() {
        let info = Bundle.main.infoDictionary ?? [:]
        let description = FriendsSheet.buildDescription
        XCTAssertTrue(description.contains("(\(info["CFBundleVersion"] as? String ?? "?"))"), description)
        XCTAssertTrue(description.hasSuffix(info["WhereGitCommit"] as? String ?? "unknown"), description)
    }
}
