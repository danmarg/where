import CoreLocation
import Combine
import UIKit

@MainActor
protocol LocationProviding: AnyObject {
    var locationPublisher: AnyPublisher<CLLocation?, Never> { get }
    var lastLocation: CLLocation? { get }
    func requestPermissionAndStart()
    func requestImmediateLocation()
    func sharingStateChanged()
}

@MainActor
final class LocationManager: NSObject, ObservableObject, CLLocationManagerDelegate, LocationProviding {
    static let shared = LocationManager()

    @Published var location: CLLocation?
    @Published var heading: Double?
    var lastLocation: CLLocation? { location }
    var locationPublisher: AnyPublisher<CLLocation?, Never> {
        $location.eraseToAnyPublisher()
    }
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined

    internal var manager: CLLocationManager?
    private var stationaryStart: Date?
    private var geofenceRegion: CLCircularRegion?

    private static let lastLatKey = "location_last_lat"
    private static let lastLngKey = "location_last_lng"

    override init() {
        if NSClassFromString("XCTestCase") != nil {
            self.manager = nil
            super.init()
            return
        }
        let m = CLLocationManager()
        self.manager = m
        // Restore last known location so heartbeat has a value immediately on fresh launch.
        let lat = UserDefaults.standard.double(forKey: Self.lastLatKey)
        let lng = UserDefaults.standard.double(forKey: Self.lastLngKey)
        if lat != 0 || lng != 0 {
            self.location = CLLocation(latitude: lat, longitude: lng)
        }
        super.init()
        m.delegate = self
        m.desiredAccuracy = kCLLocationAccuracyHundredMeters
        m.distanceFilter = kCLDistanceFilterNone // deliver every fix so app stays awake in background
        m.headingFilter = 5 // degrees
    }

    func requestPermissionAndStart() {
        guard let manager = manager else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            updateRegistration()
        default:
            break
        }
    }

    func sharingStateChanged() {
        updateRegistration()
    }

    func updateRegistration() {
        guard let manager = manager else { return }
        let status = manager.authorizationStatus
        let isSharing = LocationSyncService.shared.isSharingLocation

        if (status == .authorizedWhenInUse || status == .authorizedAlways) && isSharing {
            startUpdating()
        } else {
            stopUpdating()
        }
    }

    func stopUpdating() {
        guard let manager = manager else { return }
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        manager.stopMonitoringVisits()
        manager.stopUpdatingHeading()
    }

    func requestAlwaysPermission() {
        guard let manager = manager else { return }
        if manager.authorizationStatus == .authorizedWhenInUse {
            manager.requestAlwaysAuthorization()
        }
    }

    func requestImmediateLocation() {
        guard let manager = manager else { return }
        // requestLocation() performs a single high-accuracy location fix.
        // It calls locationManager(_:didUpdateLocations:) when finished.
        manager.requestLocation()
    }

    private func startUpdating() {
        guard let manager = manager else { return }
        manager.allowsBackgroundLocationUpdates = (manager.authorizationStatus == .authorizedAlways)
        manager.pausesLocationUpdatesAutomatically = false
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
        manager.startMonitoringVisits()
        manager.startUpdatingHeading()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let coordinate = loc.coordinate
        let speed = loc.speed

        // Acquire the background task synchronously before yielding to MainActor.
        // If we deferred this into the Task body, iOS could suspend the process in the
        // gap between the delegate callback returning and the Task actually executing.
        let identifier = MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: "LocationUpdate") { }
        }

        Task { @MainActor in
            defer {
                if identifier != .invalid {
                    UIApplication.shared.endBackgroundTask(identifier)
                }
            }
            self.location = loc
            UserDefaults.standard.set(coordinate.latitude, forKey: Self.lastLatKey)
            UserDefaults.standard.set(coordinate.longitude, forKey: Self.lastLngKey)

            // MOTION-ADAPTIVE SETTINGS (§2.2): Adjust fidelity based on speed.
            // speed < 0 means unavailable; treat as slow/stationary.
            if speed > 1.0 {
                // Moving: ensure geofence is removed and tracking is active.
                if self.geofenceRegion != nil {
                    self.removeGeofence()
                    self.manager?.startUpdatingLocation()
                }
                self.stationaryStart = nil
                self.manager?.distanceFilter = 20
                self.manager?.activityType = .automotiveNavigation
            } else {
                // Stationary or walking.
                self.manager?.distanceFilter = kCLDistanceFilterNone
                self.manager?.activityType = .other

                if speed >= 0 && speed < 0.5 {
                    if self.stationaryStart == nil {
                        self.stationaryStart = Date()
                    } else if self.geofenceRegion == nil && Date().timeIntervalSince(self.stationaryStart!) > 300 {
                        // Stationary for 5 minutes: set exit geofence and pulse GPS.
                        self.setGeofence(at: coordinate)
                    }
                } else {
                    self.stationaryStart = nil
                }
            }

            LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .locationUpdate)
            // Don't call pollAll here — tick() fires within 1s and will poll if the
            // interval has elapsed, without over-polling on every position jitter.
        }
    }

    private func setGeofence(at coordinate: CLLocationCoordinate2D) {
        guard let manager = manager else { return }
        let region = CLCircularRegion(center: coordinate, radius: 100, identifier: "stationary_fence")
        region.notifyOnExit = true
        region.notifyOnEntry = false
        manager.startMonitoring(for: region)
        self.geofenceRegion = region

        // HYBRID HEARTBEAT (§3.3): Stop continuous GPS. The 5-minute timer in
        // LocationSyncService will continue to call requestLocation() to
        // provide heartbeats while stationary.
        manager.stopUpdatingLocation()
        LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Stationary: Geofence set, GPS throttled")
    }

    private func removeGeofence() {
        guard let manager = manager, let region = geofenceRegion else { return }
        manager.stopMonitoring(for: region)
        self.geofenceRegion = nil
        self.stationaryStart = nil
        LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Moving: Geofence removed")
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        if region.identifier == "stationary_fence" {
            Task { @MainActor in
                self.removeGeofence()
                self.manager?.startUpdatingLocation()
                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Wake: Geofence Exit")
                LocationSyncService.shared.wakePoll()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        let coordinate = visit.coordinate
        Task { @MainActor in
            // VISIT MONITORING (§2.1): Trigger a broadcast on arrival/departure.
            let identifier = UIApplication.shared.beginBackgroundTask(withName: "VisitUpdate") { }
            defer {
                if identifier != .invalid {
                    UIApplication.shared.endBackgroundTask(identifier)
                }
            }
            LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .visit)
            await LocationSyncService.shared.pollAll(updateUi: false, source: .visit)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Required for requestLocation(). The system retries automatically on
        // kCLErrorLocationUnknown; other errors mean the fix was permanently unavailable,
        // and the heartbeat fallback will send lastSentLocation instead.
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let trueHeading = newHeading.trueHeading
        let magneticHeading = newHeading.magneticHeading
        Task { @MainActor in
            self.heading = trueHeading >= 0 ? trueHeading : magneticHeading
            if let loc = self.location {
                LocationSyncService.shared.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, heading: self.heading, source: .locationUpdate)
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            self.manager?.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
            self.updateRegistration()
        }
    }
}
