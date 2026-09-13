import BackgroundTasks
import SwiftUI
import os

@main
struct WhereApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Force initialization of shared services immediately on launch.
        // This ensures that CLLocationManager delegates are registered even if the app
        // is launched in the background to handle a location or geofence event.
        _ = LocationManager.shared
        _ = LocationSyncService.shared

        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "net.af0.where.heartbeat",
            using: nil
        ) { task in
            // setTaskCompleted must be called exactly once per task. Without this guard, if
            // pollAll() finishes right as the task expires, both the expiration handler and
            // the Task below could call it — a documented logic error. The lock also lets us
            // cancel the still-running poll on expiration instead of letting it keep working
            // past the background budget the OS just reclaimed.
            // BGAppRefreshTask is one-shot: whoever completes it (success or expiration) must
            // also resubmit the next one, or background heartbeat polling stops permanently
            // (nothing else calls scheduleHeartbeatTask() except init() and the foreground ->
            // background scenePhase transition, neither of which fires here). Doing the
            // reschedule inside completeOnce() — the single-fire choke point — guarantees it
            // happens exactly once regardless of which path (success or expiration) wins.
            let completedLock = OSAllocatedUnfairLock(initialState: false)
            func completeOnce(success: Bool) {
                let alreadyCompleted = completedLock.withLock { completed in
                    let was = completed
                    completed = true
                    return was
                }
                if !alreadyCompleted {
                    task.setTaskCompleted(success: success)
                    scheduleHeartbeatTask()
                }
            }

            let pollTask = Task { @MainActor in
                await LocationSyncService.shared.pollAll(updateUi: false, source: .backgroundTask)
                guard !Task.isCancelled else { return }
                completeOnce(success: true)
            }
            task.expirationHandler = {
                pollTask.cancel()
                completeOnce(success: false)
            }
        }
        scheduleHeartbeatTask()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                LocationSyncService.shared.onForegroundEntry()
            } else if newPhase == .background {
                scheduleHeartbeatTask()
            }
        }
    }
}

private func scheduleHeartbeatTask() {
    let request = BGAppRefreshTaskRequest(identifier: "net.af0.where.heartbeat")
    request.earliestBeginDate = Date(timeIntervalSinceNow: 5 * 60)
    try? BGTaskScheduler.shared.submit(request)
}
