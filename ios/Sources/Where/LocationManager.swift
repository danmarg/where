import CoreLocation
import Combine
import UIKit
@preconcurrency import Shared

private let stationaryGeofenceId = "stationary_fence"

@MainActor
protocol LocationProviding: AnyObject {
    var locationPublisher: AnyPublisher<CLLocation?, Never> { get }
    var lastLocation: CLLocation? { get }
    /// Cached stationarity flag. True when the device is known to be stationary,
    /// false otherwise. Set by the liveUpdates loop and the CoreMotion heartbeat check.
    /// Best-effort: defaults to false on cold wake until the first liveUpdates reading
    /// or heartbeat CoreMotion check runs; early sends conservatively report moving.
    var isStationary: Bool { get set }
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

    /// Cached stationarity flag readable by any send path without an async query.
    var isStationary: Bool = false

    /// Center of the fallback "wake me if I leave" geofence currently armed with
    /// CoreLocation, if any. Internal for testability. See GeofencePolicy (Shared) for
    /// the radius/re-centering rules.
    var geofenceCenter: CLLocation? = nil

    /// Whether the currently-armed geofence (if any) was sized for "moving" or
    /// "stationary". Tracked separately from [geofenceCenter] so a moving→stationary (or
    /// vice versa) transition always re-arms at the new radius, even when the device
    /// hasn't drifted far enough from the old center for `shouldRecenter` to trigger on
    /// distance alone — otherwise a device that stops shortly after the last re-center
    /// could keep the larger moving-radius fence indefinitely, delaying wake-on-departure.
    var geofenceIsMoving: Bool? = nil

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
        let hasRelationships = !LocationSyncService.shared.repo.friends.isEmpty || !LocationSyncService.shared.repo.pendingInvites.isEmpty

        // Defer decision if relationships are still loading from the database
        guard isDataLoaded else { return }

        if (status == .authorizedWhenInUse || status == .authorizedAlways) && isSharing && hasRelationships {
            startUpdating()
        } else {
            stopUpdating()
        }
    }

    func stopUpdating() {
        isStationary = false
        geofenceCenter = nil
        geofenceIsMoving = nil

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

        // Start background activity session to keep the app active for location updates.
        if #available(iOS 17.0, *), backgroundActivity == nil {
            backgroundActivity = CLBackgroundActivitySession()
        }

        let status = manager.authorizationStatus
        manager.allowsBackgroundLocationUpdates = (status == .authorizedAlways)
        manager.showsBackgroundLocationIndicator = (status == .authorizedAlways)

        guard updatesTask == nil else { return }

        // Fresh start of the live-updates stream (not just a redundant call while it's
        // already running) - reset reliability-loop state and any stale fallback geofence.
        isStationary = false
        geofenceCenter = nil
        geofenceIsMoving = nil
        for region in manager.monitoredRegions where region.identifier == stationaryGeofenceId {
            manager.stopMonitoring(for: region)
        }

        // Main location updates loop using the modern async API.
        // Heartbeats are handled by LocationSyncService.pollAll() which is driven by
        // the existing tick() timer, so no separate heartbeat task is needed here.
        updatesTask = Task { @MainActor in
            var retryDelay: Duration = .seconds(5)
            while !Task.isCancelled {
                do {
                    for try await update in CLLocationUpdate.liveUpdates() {
                        if Task.isCancelled { break }
                        retryDelay = .seconds(5)  // reset on successful stream

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

                        self.handleStationarityUpdate(loc, stationary: stationary)
                    }
                } catch let error as CLError where error.code == .denied {
                    // Authorization was revoked; no point retrying.
                    LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Live updates stopped: authorization denied", coalesceKey: nil)
                    break
                } catch {
                    LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Live updates error: \(error.localizedDescription)", coalesceKey: nil)
                }

                if Task.isCancelled { break }
                try? await Task.sleep(for: retryDelay)
                retryDelay = min(retryDelay * 2, .seconds(60))  // cap at 60s
            }
        }

        manager.startMonitoringSignificantLocationChanges()
        manager.startMonitoringVisits()
        manager.startUpdatingHeading()
    }

    /// Processes one stationarity reading from the liveUpdates stream.
    /// Extracted from the stream loop so tests can call it directly.
    func handleStationarityUpdate(_ loc: CLLocation, stationary: Bool) {
        if stationary {
            if !self.isStationary {
                self.isStationary = true
                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Stationary (System)", coalesceKey: nil)
                // Emit the stationary flag immediately, not after a debounce - the whole
                // point is to warn the peer *before* we might go dark, so the recipient
                // can render "here since HH:mm" instead of the ambiguous "last seen Xh
                // ago" if we do get suspended. A previous version delayed this behind a
                // live in-process 5-minute Task.sleep, which died silently (never firing
                // the message at all) if the app was suspended before it completed - see
                // the "here since" background-triggering investigation. A brief false
                // positive here (if the system's own stationary signal flickers) costs
                // one line in the peer's UI and self-corrects on the very next
                // non-stationary reading below.
                LocationSyncService.shared.sendLocation(
                    lat: loc.coordinate.latitude,
                    lng: loc.coordinate.longitude,
                    force: true,
                    source: .locationUpdate,
                    stationary: true,
                )
            }
            armGeofenceIfNeeded(at: loc, isMoving: false)
        } else {
            // Debounce: sub-200m fixes near the last-known stationary center are GPS
            // jitter — don't flip back to "moving" for them.
            let isJitter: Bool
            if isStationary, let center = geofenceCenter {
                isJitter = loc.distance(from: center) < LocationSyncService.minimumReportingDistanceMeters
            } else {
                isJitter = false
            }

            if !isJitter {
                self.isStationary = false

                let coordinate = loc.coordinate
                if loc.horizontalAccuracy <= LocationSyncService.minBroadcastAccuracyMeters {
                    LocationSyncService.shared.sendLocation(lat: coordinate.latitude, lng: coordinate.longitude, heading: self.heading, source: .locationUpdate)
                }
                armGeofenceIfNeeded(at: loc, isMoving: true)
            }
        }
    }

    /// Arms or re-centers the fallback "wake me if I leave" geofence. Unlike the old
    /// stationary-only design, this is a standing backstop maintained on every fix
    /// (moving or stationary) — CoreLocation watches it independent of our own process,
    /// so it's the one wake source that survives us getting suspended entirely, including
    /// mid-motion (not just after 5 confirmed-stationary minutes, which left a real gap:
    /// a suspension while still moving had no backstop at all). Re-centers only when
    /// GeofencePolicy (Shared) says drift is large enough, so we don't re-register the
    /// region - and reset the OS's boundary-crossing confirmation window - on every fix.
    private func armGeofenceIfNeeded(at loc: CLLocation, isMoving: Bool) {
        // A moving/stationary mode change always re-arms, regardless of drift distance -
        // otherwise a device that stops shortly after the last re-center could keep the
        // larger moving-radius fence indefinitely, defeating the "tighten once stationary"
        // policy. Distance-based `shouldRecenter` only governs re-centering *within* an
        // unchanged mode.
        let modeChanged = geofenceIsMoving != isMoving
        let distance = geofenceCenter.map { loc.distance(from: $0) } ?? .greatestFiniteMagnitude
        let due = geofenceCenter == nil || modeChanged ||
            GeofencePolicy.shared.shouldRecenter(distanceFromCenterMeters: distance, isMoving: isMoving)
        guard due else { return }

        geofenceCenter = loc
        geofenceIsMoving = isMoving
        guard let manager = manager else { return }
        let radius = GeofencePolicy.shared.radiusMeters(isMoving: isMoving)
        let region = CLCircularRegion(center: loc.coordinate, radius: radius, identifier: stationaryGeofenceId)
        region.notifyOnEntry = false
        region.notifyOnExit = true
        manager.startMonitoring(for: region)
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
            // Idempotent while the live-updates stream is already running (guarded by
            // `updatesTask == nil` inside) - just makes sure it's alive after a wake.
            self.startUpdating()
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
            // Treat as "moving" conservatively — a real stationarity reading from the
            // liveUpdates stream (handleStationarityUpdate) will tighten this back down
            // once it resumes.
            self.armGeofenceIfNeeded(at: loc, isMoving: true)
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
                LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Exited stationary geofence", coalesceKey: nil)
                self.isStationary = false
                self.geofenceCenter = nil
                self.geofenceIsMoving = nil
                self.startUpdating()
                self.requestImmediateLocation()
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
            LocationSyncService.shared.e2eeManager.addDiagnosticEvent(message: "Location manager error: \(error.localizedDescription)", coalesceKey: nil)
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
