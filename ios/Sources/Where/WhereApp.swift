import BackgroundTasks
import SwiftUI

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
        ) { @Sendable task in
            // `@Sendable` keeps this closure nonisolated. WhereApp is @MainActor, so without it
            // Swift 6 infers the closure as main-actor-isolated and checks that at entry; but
            // BGTaskScheduler (queue: nil) runs it on a background queue, which traps in
            // `_dispatch_assert_queue_fail` (the 1.31 crash).
            //
            // See BGTaskCompletionCoordinator for why this needs its own type: BGTask has no
            // public initializer, so the completion/reschedule logic has to live somewhere
            // testable rather than inline in this closure.
            let coordinator = BGTaskCompletionCoordinator(task: task, scheduleNext: scheduleHeartbeatTask)
            task.expirationHandler = { coordinator.expire() }
            coordinator.start {
                await LocationSyncService.shared.pollAll(updateUi: false, source: .backgroundTask)
            }
        }
        scheduleHeartbeatTask()
    }

    var body: some Scene {
        WindowGroup {
            if let error = LocationSyncService.shared.databaseOpenError {
                DatabaseErrorView(message: error)
            } else {
                ContentView()
            }
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
