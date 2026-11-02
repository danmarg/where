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

        // The registration lives in HeartbeatTaskRegistration (not a closure here): the system calls
        // the launch handler on a background queue, and a closure written in this @MainActor init
        // would trap there. See HeartbeatScheduling.
        HeartbeatTaskRegistration.register(
            with: SystemHeartbeatScheduler(),
            poll: { await LocationSyncService.shared.pollAll(updateUi: false, source: .backgroundTask) },
            scheduleNext: scheduleHeartbeatTask
        )
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
    let request = BGAppRefreshTaskRequest(identifier: HeartbeatTaskRegistration.identifier)
    request.earliestBeginDate = Date(timeIntervalSinceNow: 5 * 60)
    try? BGTaskScheduler.shared.submit(request)
}
