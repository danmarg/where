import CoreLocation
import Combine
import UIKit

@MainActor
protocol LocationProviding: AnyObject {
    var locationPublisher: AnyPublisher<CLLocation?, Never> { get }
    var lastLocation: CLLocation? { get }
    func requestPermissionAndStart()
}

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate, LocationProviding {
    static let shared = LocationManager()

    @Published var location: CLLocation?
    var lastLocation: CLLocation? { location }
    var locationPublisher: AnyPublisher<CLLocation?, Never> {
        $location.eraseToAnyPublisher()
    }
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    private let manager: CLLocationManager?

    override init() {
        if NSClassFromString("XCTestCase") != nil {
            self.manager = nil
            super.init()
            return
        }
        let m = CLLocationManager()
        self.manager = m
        super.init()
        m.delegate = self
        m.desiredAccuracy = kCLLocationAccuracyHundredMeters
        m.distanceFilter = 50 // meters — heartbeat timer covers stationary case
    }

    func requestPermissionAndStart() {
        guard let manager = manager else { return }
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
        guard let manager = manager else { return }
        manager.allowsBackgroundLocationUpdates = (manager.authorizationStatus == .authorizedAlways)
        manager.pausesLocationUpdatesAutomatically = false
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            let identifier = UIApplication.shared.beginBackgroundTask(withName: "LocationUpdate") {
                // Task expired
            }
            defer {
                if identifier != .invalid {
                    UIApplication.shared.endBackgroundTask(identifier)
                }
            }
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
            self.manager?.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
            if status == .authorizedWhenInUse || status == .authorizedAlways {
                self.startUpdating()
            }
        }
    }
}
