import CoreLocation
import Combine
import UIKit

private let stationaryGeofenceId = "stationary_fence"

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

    // Reliability loop state
    private var isLowPowerMode = false
    private var stationaryTask: Task<Void, Never>?

    /// When true, fully tear down the background activity session and live-updates
    /// stream once the user has been stationary for 5+ minutes. The app then relies
    /// solely on geofence-exit, visit monitoring, and BGAppRefreshTask to wake up —
    /// minimal battery, but heartbeat cadence becomes whatever iOS decides (often
    /// nothing overnight).
    ///
    /// When false (default), keep `CLBackgroundActivitySession` and the
    /// `CLLocationUpdate.liveUpdates()` stream alive while stationary so the
    /// in-process 1Hz Timer keeps firing and `pollAll()` can send the 5-minute
    /// heartbeat. The radio cost is modest because liveUpdates throttles itself
    /// while the device is stationary, and stationary users are frequently
    /// plugged in.
    static var deepSleepWhenStationary = false
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
        m.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        m.distanceFilter = LocationSyncService.minimumReportingDistanceMeters
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
        let isDataLoaded = LocationSyncService.shared.isDataLoaded
        let hasRelationships = !LocationSyncService.shared.friends.isEmpty || !LocationSyncService.shared.pendingInvites.isEmpty

        // Defer decision if relationships are still loading from the database
        guard isDataLoaded else { return }

        if (status == .authorizedWhenInUse || status == .authorizedAlways) && isSharing && hasRelationships {
            startUpdating()
        } else {
            stopUpdating()
        }
    }

    func stopUpdating() {
        stationaryTask?.cancel()
        stationaryTask = nil
        isLowPowerMode = false

        updatesTask?.cancel()
        updatesTask = nil
        backgroundActivity?.invalidate()
        backgroundActivity = nil

        if let manager = manager {
            manager.stopMonitoringSignificantLocationChanges()
            for region in manager.monitoredRegions {
                if region.identifier == stationaryGeofenceId {
                    manager.stopMonitoring(for: region)
                }
            }
        }

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
        guard let manager = manager else { return }

        // Clean up reliability loop state before starting high-fidelity tracking
        isLowPowerMode = false
        stationaryTask?.cancel()
        stationaryTask = nil
        for region in manager.monitoredRegions {
            if region.identifier == stationaryGeofenceId {
                manager.stopMonitoring(for: region)
            }
        }

        // Start background activity session to keep the app active for location updates.
        if #available(iOS 17.0, *), backgroundActivity == nil {
            backgroundActivity = CLBackgroundActivitySession()
        }

        let status = manager.authorizationStatus
        manager.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
        manager.showsBackgroundLocationIndicator = (status == .authorizedAlways)

        guard updatesTask == nil else { return }

        // Main location updates loop using the modern async API.
        // Heartbeats are handled by LocationSyncService.pollAll() which is driven by
        // the existing tick() timer, so no separate heartbeat task is needed here.
        updatesTask = Task { @MainActor in
            var retryDelay: UInt64 = 5_000_000_000
            while !Task.isCancelled {
                do {
                    for try await update in CLLocationUpdate.liveUpdates() {
                        if Task.isCancelled { break }
                        retryDelay = 5_000_000_000  // reset on successful stream

                        guard let loc = update.location else { continue }
                        self.location = loc

                        let coordinate = loc.coordinate
                        UserDefaults.standard.set(coordinate.latitude, forKey: Self.lastLatKey)
                        UserDefaults.standard.set(coordinate.longitude, forKey: Self.lastLngKey)

                        let stationary: Bool
                        if #available(iOS 18.0, *) {
                            stationary = update.stationary
                        } else {
                            stationary = update.isStationary
                        }

                        if stationary {
                            if self.stationaryTask == nil {
                                self.stationaryTask = Task { @MainActor in
                                    do {
                                        try await Task.sleep(nanoseconds: 5 * 60 * 1_000_000_000)
                                        if !Task.isCancelled {
                                            self.enterLowPowerMode(at: loc)
                                        }
                                    } catch {
                                        // Cancelled
                                    }
                                }
                            }
                            LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Stationary (System)")
                        } else {
                            self.stationaryTask?.cancel()
                            self.stationaryTask = nil
                            if self.isLowPowerMode {
                                self.resumeHighFidelityTracking()
                            }

                            if loc.horizontalAccuracy <= LocationSyncService.minBroadcastAccuracyMeters {
                                LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .locationUpdate)
                            }
                        }
                    }
                } catch let error as CLError where error.code == .denied {
                    // Authorization was revoked; no point retrying.
                    LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Live updates stopped: authorization denied")
                    break
                } catch {
                    LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Live updates error: \(error.localizedDescription)")
                }

                if Task.isCancelled { break }
                try? await Task.sleep(nanoseconds: retryDelay)
                retryDelay = min(retryDelay * 2, 60_000_000_000)  // cap at 60s
            }
        }

        manager.startMonitoringSignificantLocationChanges()
        manager.startMonitoringVisits()
        manager.startUpdatingHeading()
    }

    private func enterLowPowerMode(at location: CLLocation) {
        guard !isLowPowerMode else { return }
        isLowPowerMode = true

        if let manager = manager {
            let region = CLCircularRegion(center: location.coordinate, radius: 200, identifier: stationaryGeofenceId)
            region.notifyOnEntry = false
            region.notifyOnExit = true
            manager.startMonitoring(for: region)
        }

        stationaryTask = nil

        if Self.deepSleepWhenStationary {
            LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Entering low-power mode (stationary > 5m, deep sleep)")
            updatesTask?.cancel()
            updatesTask = nil
            backgroundActivity?.invalidate()
            backgroundActivity = nil
            manager?.stopMonitoringSignificantLocationChanges()
        } else {
            // Keep CLBackgroundActivitySession and the liveUpdates stream alive so
            // the in-process Timer keeps ticking and pollAll() can fire the 5-minute
            // heartbeat. The geofence above is redundant for movement detection in
            // this mode but is retained as a fallback in case the OS suspends us
            // anyway under memory pressure.
            LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Entering low-power mode (stationary > 5m, keepalive)")
        }
    }

    private func resumeHighFidelityTracking() {
        guard isLowPowerMode else { return }
        LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Resuming high-fidelity tracking")
        startUpdating()
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
            if self.isLowPowerMode {
                self.resumeHighFidelityTracking()
            } else {
                self.startUpdating()
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

    nonisolated func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        if region.identifier == stationaryGeofenceId {
            let identifier = MainActor.assumeIsolated {
                UIApplication.shared.beginBackgroundTask(withName: "GeofenceExit") { }
            }
            Task { @MainActor in
                defer {
                    if identifier != .invalid {
                        UIApplication.shared.endBackgroundTask(identifier)
                    }
                }
                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Exited stationary geofence")
                self.resumeHighFidelityTracking()
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
        Task { @MainActor in
            LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Location manager error: \(error.localizedDescription)")
        }
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
