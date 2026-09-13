import BackgroundTasks
import os

/// Abstraction over BGTask's completion so tests can supply a fake — BGTask itself has no
/// public initializer and can only be constructed by the system, so the real type can't be
/// exercised in a unit test.
protocol CompletableBGTask: AnyObject {
    func markTaskCompleted(success: Bool)
}

extension BGTask: CompletableBGTask {
    func markTaskCompleted(success: Bool) {
        setTaskCompleted(success: success)
    }
}

/// Coordinates a single BGAppRefreshTask's lifecycle. setTaskCompleted must be called exactly
/// once per task — if `work` finishes right as the task expires, both the expiration path and
/// the work-completion path could otherwise call it, a documented logic error. BGAppRefreshTask
/// is also one-shot: whoever completes it (success or expiration) must resubmit the next one,
/// or background heartbeat polling stops permanently. Both are handled from the single
/// `completeOnce` choke point so they happen exactly once regardless of which path wins.
final class BGTaskCompletionCoordinator {
    private let task: CompletableBGTask
    private let scheduleNext: () -> Void
    private let completedLock = OSAllocatedUnfairLock(initialState: false)
    private var workTask: Task<Void, Never>?

    init(task: CompletableBGTask, scheduleNext: @escaping () -> Void) {
        self.task = task
        self.scheduleNext = scheduleNext
    }

    /// Starts `work`. If it completes before `expire()` cancels it, marks the task successful.
    func start(work: @escaping () async -> Void) {
        workTask = Task { [weak self] in
            await work()
            guard !Task.isCancelled, let self else { return }
            self.completeOnce(success: true)
        }
    }

    /// Call from BGTask.expirationHandler. Cancels the in-flight work and marks the task
    /// failed, unless it already completed successfully.
    func expire() {
        workTask?.cancel()
        completeOnce(success: false)
    }

    private func completeOnce(success: Bool) {
        let alreadyCompleted = completedLock.withLock { completed in
            let was = completed
            completed = true
            return was
        }
        if !alreadyCompleted {
            task.markTaskCompleted(success: success)
            scheduleNext()
        }
    }
}
