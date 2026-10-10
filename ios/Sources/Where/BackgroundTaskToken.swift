import UIKit

/// A UIKit background task that always ends exactly once: when the work finishes
/// (`end()`), or when iOS calls the expiration handler. An expiration handler that
/// doesn't end the task gets the app killed by the watchdog once the background budget
/// runs out (e.g. a multi-friend poll on a short BGTask wake).
@MainActor
final class BackgroundTaskToken {
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    init(name: String) {
        identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
