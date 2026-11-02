import BackgroundTasks

/// What the heartbeat launch handler needs from a `BGTask`. `BGTask` has no public initializer, so
/// tests substitute a fake behind this protocol (see `CompletableBGTask`).
protocol HeartbeatTask: CompletableBGTask {
    func setExpirationHandler(_ handler: @escaping @Sendable () -> Void)
}

extension BGTask: HeartbeatTask {
    func setExpirationHandler(_ handler: @escaping @Sendable () -> Void) {
        expirationHandler = handler
    }
}

/// The one call into `BGTaskScheduler`, so a test can capture the registered launch handler and run
/// it as the system does.
///
/// The handler is `@Sendable`, and that is the point. The system calls it on a background queue
/// (`using: nil`), not the main thread. `WhereApp` is `@MainActor`, and Swift 6 infers a closure
/// written there without `@Sendable` as main-actor-isolated and checks that on entry, which trapped
/// in `_dispatch_assert_queue_fail` on 1.31 (#457). With the parameter typed `@Sendable`, a
/// main-actor-isolated handler no longer compiles.
protocol HeartbeatScheduling {
    func register(identifier: String, handler: @escaping @Sendable (any HeartbeatTask) -> Void)
}

/// Production scheduler. A plain struct, deliberately not `@MainActor`, so the closure it hands to
/// `BGTaskScheduler` is nonisolated.
struct SystemHeartbeatScheduler: HeartbeatScheduling {
    func register(identifier: String, handler: @escaping @Sendable (any HeartbeatTask) -> Void) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { @Sendable task in
            handler(task)
        }
    }
}

/// The heartbeat launch handler: the production code and the tests both go through `register`.
enum HeartbeatTaskRegistration {
    /// Must match `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
    static let identifier = "net.af0.where.heartbeat"

    /// Registers the handler. When the system launches the app for the task, it runs `poll`, then
    /// completes the task and calls `scheduleNext`; if the system expires the task first, `poll` is
    /// cancelled, the task is completed unsuccessfully, and `scheduleNext` still runs
    /// (see `BGTaskCompletionCoordinator`).
    static func register(
        with scheduler: some HeartbeatScheduling,
        poll: @escaping @Sendable () async -> Void,
        scheduleNext: @escaping @Sendable () -> Void
    ) {
        scheduler.register(identifier: identifier) { task in
            let coordinator = BGTaskCompletionCoordinator(task: task, scheduleNext: scheduleNext)
            task.setExpirationHandler { coordinator.expire() }
            coordinator.start(work: poll)
        }
    }
}
