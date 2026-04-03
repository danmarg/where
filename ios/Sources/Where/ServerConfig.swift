import Foundation

/// Central place to configure the server URL for E2EE mailbox endpoints.
///
/// - Simulator: use `localhost` (default).
/// - Real device on the same network: set via WHERE_SERVER_HTTP_URL env var or use `http://where:8080`.
/// - Production: point to your deployed server URL.
enum ServerConfig {
    #if targetEnvironment(simulator)
    static let httpBaseUrl = "http://localhost:8080"
    #else
    static let httpBaseUrl: String = {
        // Read from environment variable set at build time (e.g., by Local.xcconfig or xcodebuild).
        // Defaults to production server.
        ProcessInfo.processInfo.environment["WHERE_SERVER_HTTP_URL"]
            ?? "https://where-api.af0.net"
    }()
    #endif
}
