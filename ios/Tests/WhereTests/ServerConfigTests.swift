import XCTest
@testable import Where

final class ServerConfigTests: XCTestCase {
    /// Debug builds on the simulator (which is what the test target runs) talk to a local
    /// development server, never production, unless the scheme overrides it.
    func testDebugSimulatorDefaultsToLocalServer() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["WHERE_SERVER_HTTP_URL"] != nil,
                      "WHERE_SERVER_HTTP_URL is set in the scheme")
        #if DEBUG && targetEnvironment(simulator)
        XCTAssertEqual(ServerConfig.httpBaseUrl, "http://127.0.0.1:8080")
        #else
        XCTAssertEqual(ServerConfig.httpBaseUrl, ServerConfig.productionUrl)
        #endif
    }

    func testProductionIsHttps() {
        XCTAssertTrue(ServerConfig.productionUrl.hasPrefix("https://"))
    }
}
