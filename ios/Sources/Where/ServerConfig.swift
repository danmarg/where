import Foundation

/// The mailbox server URL.
///
/// - `WHERE_SERVER_HTTP_URL` in the environment wins, e.g. a LAN address for a Debug build on a
///   real device. The shared scheme passes it from `ios/Local.xcconfig` (see project.yml).
/// - Debug builds on the simulator default to a local server (`./scripts/run-server.sh`).
/// - Everything else uses production.
enum ServerConfig {
    static let productionUrl = "https://where-api.af0.net"

    static let httpBaseUrl: String = {
        if let url = ProcessInfo.processInfo.environment["WHERE_SERVER_HTTP_URL"], !url.isEmpty {
            return url
        }
        #if DEBUG && targetEnvironment(simulator)
        // An IP address, not "localhost": App Transport Security allows plain HTTP to it.
        return "http://127.0.0.1:8080"
        #else
        return productionUrl
        #endif
    }()
}
