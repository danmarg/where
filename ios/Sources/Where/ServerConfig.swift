import Foundation

/// Central place to configure the server URL.
///
/// - Simulator: use `localhost` (default).
/// - Real device on the same network: change to your machine's LAN IP,
///   e.g. `ws://192.168.1.10:8080/ws`.
/// - Production: point to your deployed server URL.
enum ServerConfig {
    #if targetEnvironment(simulator)
    static let wsBaseUrl = "ws://localhost:8080/ws"
    #else
    // Replace with your server's LAN IP or deployed URL before running on a real device.
    static let wsBaseUrl: String = {
        preconditionFailure("Replace with your server's LAN IP or deployed URL before running on a real device.")
    }()
    #endif
}
