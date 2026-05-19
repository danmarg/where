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
        ) { task in
            task.expirationHandler = {
                task.setTaskCompleted(success: false)
            }
            Task { @MainActor in
                await LocationSyncService.shared.pollAll(updateUi: false, source: .backgroundTask)
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
