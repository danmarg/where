import UIKit

/// A UIKit background task that always ends exactly once: when the work finishes
/// (`end()`), or when iOS calls the expiration handler. An expiration handler that
/// doesn't end the task gets the app killed by the watchdog once the background budget
/// runs out (e.g. a multi-friend poll on a short BGTask wake).
///
/// Both paths run on the main actor (UIKit calls expiration handlers on the main thread), so
/// they're serialized; `end()` also clears the identifier *before* calling out, so even a
/// re-entrant end (expiration delivered from inside `endBackgroundTask`) is a no-op.
///
/// If iOS refuses the task (`.invalid`, e.g. the background budget is already exhausted), the
/// work still runs but is not protected from suspension: it may pause until the app next runs.
/// `end()` is then a no-op.
@MainActor
final class BackgroundTaskToken {
    typealias Begin = @Sendable (String, @Sendable @escaping () -> Void) -> UIBackgroundTaskIdentifier
    typealias End = @Sendable (UIBackgroundTaskIdentifier) -> Void

    private var identifier: UIBackgroundTaskIdentifier = .invalid
    private let endTask: End

    /// `begin`/`end` default to UIApplication's; tests inject recorders.
    init(name: String, begin: Begin? = nil, end: End? = nil) {
        endTask = end ?? { id in MainActor.assumeIsolated { UIApplication.shared.endBackgroundTask(id) } }
        let onExpiration: @Sendable () -> Void = { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
        if let begin {
            identifier = begin(name, onExpiration)
        } else {
            identifier = UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: onExpiration)
        }
    }

    func end() {
        let id = identifier
        guard id != .invalid else { return }
        identifier = .invalid
        endTask(id)
    }
}
