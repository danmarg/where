import BackgroundTasks
import SwiftUI

@main
struct WhereApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "net.af0.where.heartbeat",
            using: nil
        ) { task in
            task.expirationHandler = {
                task.setTaskCompleted(success: false)
            }
            Task { @MainActor in
                await LocationSyncService.shared.pollAll(updateUi: false)
                task.setTaskCompleted(success: true)
                scheduleHeartbeatTask()
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
                LocationSyncService.shared.wakePoll()
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
