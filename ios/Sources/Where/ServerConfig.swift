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
    // TODO: replace with your server's address for real-device builds
    static let wsBaseUrl = "ws://localhost:8080/ws"
    #endif
}
