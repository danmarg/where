import Foundation

/// Central place to configure the server URL.
///
/// - Simulator: use `localhost` (default).
/// - Real device on the same network: change to your machine's LAN IP,
///   e.g. `ws://192.168.1.10:8080/ws`.
/// - Production: point to your deployed server URL.
enum ServerConfig {
    #if targetEnvironment(simulator)
    static let httpBaseUrl = "http://localhost:8080"
    #else
    // Read from environment variable set at build time (e.g., by build.sh).
    // Falls back to local hostname if not provided.
    static let httpBaseUrl: String = {
        ProcessInfo.processInfo.environment["WHERE_SERVER_HTTP_URL"]
            ?? "http://where:8080"
    }()
    #endif
}
