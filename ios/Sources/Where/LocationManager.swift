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

    // Modern API state
    private var backgroundActivity: CLBackgroundActivitySession?
    private var updatesTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?

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
        self.authorizationStatus = m.authorizationStatus
        m.desiredAccuracy = kCLLocationAccuracyHundredMeters
        m.distanceFilter = kCLDistanceFilterNone
        m.headingFilter = 5
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
        updatesTask?.cancel()
        updatesTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        backgroundActivity?.invalidate()
        backgroundActivity = nil

        // Stop legacy monitoring
        manager?.stopMonitoringVisits()
        manager?.stopUpdatingLocation()
        manager?.stopUpdatingHeading()
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
        guard updatesTask == nil else { return }
        guard let manager = manager else { return }

        // Start background activity session to keep the app active for location updates.
        if #available(iOS 17.0, *), backgroundActivity == nil {
            backgroundActivity = CLBackgroundActivitySession()
        }

        let status = manager.authorizationStatus
        manager.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
        manager.showsBackgroundLocationIndicator = (status == .authorizedAlways)

        // Main location updates loop using the modern async API.
        updatesTask = Task { @MainActor in
            while !Task.isCancelled {
                if #available(iOS 17.0, *) {
                    do {
                        // We use .default configuration. For more rapid updates when moving,
                        // iOS 17+ manages this automatically based on the activity and system state.
                        for try await update in CLLocationUpdate.liveUpdates() {
                            if Task.isCancelled { break }

                            guard let loc = update.location else { continue }
                            self.location = loc

                            let coordinate = loc.coordinate
                            UserDefaults.standard.set(coordinate.latitude, forKey: Self.lastLatKey)
                            UserDefaults.standard.set(coordinate.longitude, forKey: Self.lastLngKey)

                            if update.isStationary {
                                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Stationary (System)")
                            } else {
                                if loc.horizontalAccuracy <= LocationSyncService.minBroadcastAccuracyMeters {
                                    LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .locationUpdate)
                                }
                            }
                        }
                    } catch {
                        LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Live updates error: \(error.localizedDescription)")
                    }
                } else {
                    // Fallback for earlier versions if deployment target was lower,
                    // though project targets 17.0+.
                    manager.startUpdatingLocation()
                    break
                }

                if Task.isCancelled { break }
                // Exponential backoff or simple delay before retry on error.
                try? await Task.sleep(nanoseconds: 5 * 1_000_000_000)
            }
        }

        // Heartbeat task: ensures heartbeats are sent even when stationary and liveUpdates() is not yielding.
        heartbeatTask = Task { @MainActor in
            while !Task.isCancelled {
                // 5 minute interval for heartbeats.
                do {
                    try await Task.sleep(nanoseconds: 300 * 1_000_000_000)
                } catch {
                    // Task was cancelled
                    break
                }

                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Heartbeat (Modern)")
                await LocationSyncService.shared.pollAll(source: .heartbeat)
            }
        }

        // Continue monitoring visits and heading (legacy delegate-based API).
        manager.startMonitoringVisits()
        manager.startUpdatingHeading()
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // Still called by requestLocation() or other legacy components.
        guard let loc = locations.last else { return }
        let identifier = MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: "LocationUpdate") { }
        }
        Task { @MainActor in
            defer {
                if identifier != .invalid {
                    UIApplication.shared.endBackgroundTask(identifier)
                }
            }
            // Only broadcast if this fix was not already handled by liveUpdates.
            // requestLocation() results often have a very recent timestamp.
            if let lastLoc = self.location, loc.timestamp.timeIntervalSince(lastLoc.timestamp) <= 0 {
                return
            }
            self.location = loc
            let coordinate = loc.coordinate
            if loc.horizontalAccuracy <= LocationSyncService.minBroadcastAccuracyMeters {
                LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .locationUpdate)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        let coordinate = visit.coordinate
        let identifier = MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: "VisitUpdate") { }
        }
        Task { @MainActor in
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
        LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Location manager error: \(error.localizedDescription)")
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let trueHeading = newHeading.trueHeading
        let magneticHeading = newHeading.magneticHeading
        let identifier = MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: "HeadingUpdate") { }
        }
        Task { @MainActor in
            defer {
                if identifier != .invalid {
                    UIApplication.shared.endBackgroundTask(identifier)
                }
            }
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
            self.manager?.showsBackgroundLocationIndicator = (status == .authorizedAlways)
            self.updateRegistration()
        }
    }
}
