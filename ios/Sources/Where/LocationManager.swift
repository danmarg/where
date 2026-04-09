import CoreLocation
import Combine

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = LocationManager()

    @Published var location: CLLocation?
    var lastLocation: CLLocation? { location }
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 10 // meters — low value ensures updates when stationary, rely on heartbeat throttle for battery
    }

    func requestPermissionAndStart() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestAlwaysAuthorization()
        case .authorizedWhenInUse:
            manager.requestAlwaysAuthorization()
            startUpdating()
        case .authorizedAlways:
            startUpdating()
        default:
            break
        }
    }

    private func startUpdating() {
        manager.allowsBackgroundLocationUpdates = (manager.authorizationStatus == .authorizedAlways)
        manager.pausesLocationUpdatesAutomatically = false
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            let backgroundTask = LocationSyncService.shared.startBackgroundTask("LocationUpdate")
            defer { backgroundTask.end() }
            self.location = loc
            LocationSyncService.shared.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
            // Ensure we also poll for updates when the OS wakes us for a location fix.
            await LocationSyncService.shared.pollAll(updateUi: false)
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            self.manager.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.startUpdating()
            }
        }
    }
}
