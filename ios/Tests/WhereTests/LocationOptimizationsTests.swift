import XCTest
import CoreLocation
import Combine
@preconcurrency import Shared
@testable import Where

@MainActor
class LocationOptimizationsTests: XCTestCase {
    var service: LocationSyncService!
    var mockLocationProvider: MockLocationProvider!
    var mockLocationClient: MockLocationClient!

    @MainActor
    class MockLocationProvider: LocationProviding {
        @Published var location: CLLocation? = nil
        var locationPublisher: AnyPublisher<CLLocation?, Never> { $location.eraseToAnyPublisher() }
        var lastLocation: CLLocation? { location }
        var isStationary: Bool = false

        var requestPermissionAndStartCalled = false
        func requestPermissionAndStart() { requestPermissionAndStartCalled = true }

        var requestImmediateLocationCalled = false
        func requestImmediateLocation() { requestImmediateLocationCalled = true }

        func sharingStateChanged() {}
        var hasLocationPermission = true
    }

    @MainActor
    class MockLocationClient: LocationSyncServiceTests.MockLocationClient {
        var sendLocationCallCount = 0
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool, stamp: Int64) async throws {
            sendLocationCallCount += 1
            try await super.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds, stationary: stationary, stamp: stamp)
        }
    }

    override func setUp() async throws {
        LocationSyncService.defaultLocationFixTimeout = 0
        UserDefaults.standard.removeObject(forKey: LocationSyncService.stoppedForMissingPermissionKey)
        resetLocationManagerShared()
        // Default to "tracking" so tests aren't at the mercy of whether the shared service's
        // DB has loaded; not-tracking tests set this to false explicitly.
        LocationManager.shared.isTrackingOverride = true
        self.mockLocationProvider = MockLocationProvider()
        self.mockLocationClient = MockLocationClient()
        let e2eeManager = makeTestE2eeManager()
        let userStore = Shared.UserStore(storage: LocationSyncServiceTests.MockRawKeyValueStorage())
        self.service = LocationSyncService(e2eeManager: e2eeManager, userStore: userStore, locationClient: mockLocationClient, locationProvider: mockLocationProvider)
        self.service.beginBackgroundTask = { _, _ in .invalid }
        self.service.endBackgroundTask = { _ in }
    }

    override func tearDown() async throws {
        resetLocationManagerShared()
        service?.sendScheduler.close()
        LocationSyncService.defaultLocationFixTimeout = 10
    }

    private func resetLocationManagerShared() {
        LocationManager.shared.geofenceCenter = nil
        LocationManager.shared.geofenceIsMoving = nil
        LocationManager.shared.isStationary = false
        LocationManager.shared.sendLocationOverride = nil
        LocationManager.shared.isTrackingOverride = nil
    }

    func testHeartbeat_CallsRequestImmediateLocation() async throws {
        service.isSharingLocation = true  // a fresh scheduler's heartbeat is due
        
        // This is tricky because tick() runs in a loop with Timer.
        // We can manually call tick() to simulate the timer fire.
        // But tick() is private. We can test the pollAll path instead.
        
        await service.pollAll(updateUi: false)
        
        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, 
            "pollAll should call requestImmediateLocation if heartbeat is due")
    }

    func testLocationManager_DeduplicatesLegacyUpdates() async throws {
        let locationManager = LocationManager.shared
        let manager = CLLocationManager()

        let now = Date()
        let loc1 = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 1), altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: now)

        // Set internal state
        locationManager.location = loc1

        // Simulate a legacy update with the same timestamp
        locationManager.locationManager(manager, didUpdateLocations: [loc1])

        try await Task.sleep(nanoseconds: 100_000_000)

        // loc1 should remain as the current location, and no duplicate broadcast should happen.
        // We can't easily verify the broadcast without more mocking, but we verify it doesn't crash.
        XCTAssertEqual(locationManager.location?.timestamp, now)
    }

    // Regression test: didUpdateLocations() used to call sendLocation() without a
    // `stationary` argument, defaulting to false and clobbering an in-progress "here
    // since" signal whenever a forced re-fix (e.g. requestImmediateLocation() on
    // foreground entry) arrived while the device was genuinely stationary.
    func testLocationManager_DidUpdateLocations_PreservesStationaryFlag() async throws {
        let locationManager = LocationManager.shared
        let manager = CLLocationManager()

        let earlier = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 1),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date(timeIntervalSinceNow: -60)
        )
        locationManager.location = earlier
        locationManager.isStationary = true
        locationManager.isTrackingOverride = true
        defer { locationManager.isTrackingOverride = nil }

        var capturedStationary: Bool?
        locationManager.sendLocationOverride = { _, _, _, stationary in
            capturedStationary = stationary
        }

        let fresh = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.locationManager(manager, didUpdateLocations: [fresh])

        try await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(capturedStationary, true,
            "A forced re-fix delivered via didUpdateLocations while stationary must not clobber the stationary flag")
    }

    // H6 regression: one-shot fixes from requestImmediateLocation() (foreground entry,
    // heartbeat) arrive via didUpdateLocations even while sharing is paused. When tracking is
    // off (and the DB has loaded), they must not be broadcast or start tracking/geofencing.
    func testLocationManager_DidUpdateLocations_DoesNotBroadcastWhenNotTracking() async throws {
        let locationManager = LocationManager.shared
        locationManager.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 1, longitude: 1),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date(timeIntervalSinceNow: -60)
        )
        locationManager.isTrackingOverride = false
        locationManager.geofenceCenter = nil
        defer { locationManager.isTrackingOverride = nil }

        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }
        defer { locationManager.sendLocationOverride = nil }

        let fresh = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.locationManager(CLLocationManager(), didUpdateLocations: [fresh])
        try await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(broadcasts, 0, "A one-shot fix while not tracking must not be broadcast")
        XCTAssertNil(locationManager.geofenceCenter, "...nor arm the fallback geofence")
    }

    // A live update can still be delivered after stopUpdating() cancels the stream (cancellation
    // is cooperative). Arriving while not tracking, neither a stationary transition nor a moving
    // fix may broadcast or arm the fallback geofence.
    func testLiveUpdateArrivingAfterPause_DoesNotBroadcastOrArmGeofence() {
        let locationManager = LocationManager.shared
        locationManager.isTrackingOverride = false
        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }
        let loc = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        locationManager.handleStationarityUpdate(loc, stationary: true)
        locationManager.handleStationarityUpdate(loc, stationary: false)

        XCTAssertEqual(broadcasts, 0, "an in-flight live update after pause must not be broadcast")
        XCTAssertNil(locationManager.geofenceCenter, "...nor arm the fallback geofence")
        XCTAssertFalse(locationManager.isStationary, "...nor change stationarity state")
    }

    func testVisitArrivingAfterPause_DoesNotBroadcast() {
        let locationManager = LocationManager.shared
        locationManager.isTrackingOverride = false
        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }

        locationManager.handleVisitUpdate(lat: 37.7749, lng: -122.4194)

        XCTAssertEqual(broadcasts, 0, "a delayed visit after pause must not be broadcast")
    }

    func testHeadingWhileNotTracking_UpdatesHeadingButDoesNotBroadcast() {
        let locationManager = LocationManager.shared
        locationManager.isTrackingOverride = false
        locationManager.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }

        locationManager.handleHeadingUpdate(trueHeading: 90, magneticHeading: 90)

        XCTAssertEqual(broadcasts, 0, "a heading change must not re-send the cached location while not tracking")
        XCTAssertEqual(locationManager.heading, 90)
    }

    func testVisitWhileTracking_BroadcastsButHeadingDoesNot() {
        let locationManager = LocationManager.shared
        locationManager.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }

        locationManager.handleVisitUpdate(lat: 37.7749, lng: -122.4194)
        locationManager.handleHeadingUpdate(trueHeading: 90, magneticHeading: 90)

        // Heading isn't in the Location payload: it only turns our own map arrow.
        XCTAssertEqual(broadcasts, 1)
    }

    // Heading updates fire continuously from compass jitter, even while stationary; they used
    // to re-send the cached location (once clobbering "here since"). Now they never send.
    func testLocationManager_HeadingUpdate_DoesNotBroadcast() {
        let locationManager = LocationManager.shared
        locationManager.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.isStationary = true
        var broadcasts = 0
        locationManager.sendLocationOverride = { _, _, _, _ in broadcasts += 1 }

        locationManager.handleHeadingUpdate(trueHeading: 42.0, magneticHeading: 42.0)

        XCTAssertEqual(broadcasts, 0)
        XCTAssertEqual(locationManager.heading, 42.0)
    }

    func testLocationManager_StationaryDebounce_JitterDoesNotMoveGeofence() async throws {
        let locationManager = LocationManager.shared
        let anchor = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        // First stationary reading arms the fallback geofence immediately (no debounce).
        locationManager.handleStationarityUpdate(anchor, stationary: true)
        XCTAssertTrue(locationManager.isStationary)
        XCTAssertNotNil(locationManager.geofenceCenter, "geofence must be armed on first stationary reading")
        let originalCenterLat = locationManager.geofenceCenter?.coordinate.latitude

        // Feed a non-stationary fix 50 m from the anchor — this is GPS jitter.
        let jitterFix = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7753, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        XCTAssertLessThan(jitterFix.distance(from: anchor), LocationSyncService.minimumReportingDistanceMeters,
            "Test precondition: jitter fix must be < 200m from anchor")

        locationManager.handleStationarityUpdate(jitterFix, stationary: false)

        XCTAssertTrue(locationManager.isStationary,
            "isStationary must remain true for a sub-200m jitter reading")
        XCTAssertEqual(locationManager.geofenceCenter?.coordinate.latitude, originalCenterLat,
            "geofence center must not shift for a jitter reading")
    }

    func testLocationManager_StationaryDebounce_LargeMoveClearsStationaryAndRecenters() async throws {
        let locationManager = LocationManager.shared
        let anchor = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        locationManager.handleStationarityUpdate(anchor, stationary: true)
        XCTAssertTrue(locationManager.isStationary)

        // Genuine movement, far enough to clear both the 200m jitter gate and the
        // moving-radius recenter threshold.
        let farFix = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7776, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        XCTAssertGreaterThanOrEqual(farFix.distance(from: anchor), LocationSyncService.minimumReportingDistanceMeters,
            "Test precondition: far fix must be >= 200m from anchor")

        locationManager.handleStationarityUpdate(farFix, stationary: false)

        XCTAssertFalse(locationManager.isStationary,
            "isStationary must be false after genuine movement")
        XCTAssertEqual(locationManager.geofenceCenter?.coordinate.latitude, farFix.coordinate.latitude,
            "geofence must be re-centered at the new position after genuine movement")
    }

    func testLocationManager_StationaryUpdate_ArmsGeofenceOnFirstStationaryReading() async throws {
        let locationManager = LocationManager.shared
        let loc = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.handleStationarityUpdate(loc, stationary: true)

        XCTAssertNotNil(locationManager.geofenceCenter, "geofence must be armed on first stationary reading")
        XCTAssertTrue(locationManager.isStationary)

        // A second, nearby stationary reading must not need to move the geofence.
        let centerLat = locationManager.geofenceCenter?.coordinate.latitude
        let loc2 = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7750, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.handleStationarityUpdate(loc2, stationary: true)

        XCTAssertTrue(locationManager.isStationary)
        XCTAssertEqual(locationManager.geofenceCenter?.coordinate.latitude, centerLat,
            "geofence center must not shift for a nearby subsequent stationary reading")
    }

    func testLocationManager_ModeChangeAlwaysRecentersGeofence_EvenWithoutDrift() async throws {
        // Regression test: a moving -> stationary (or vice versa) transition must re-arm the
        // geofence at the new radius immediately, even when the device hasn't drifted far
        // enough from the old center for distance-based recentering to trigger on its own -
        // otherwise a device that stops shortly after the last re-center could keep the
        // larger moving-radius fence indefinitely, defeating "tighten once stationary".
        let locationManager = LocationManager.shared
        let movingLoc = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.handleStationarityUpdate(movingLoc, stationary: false)
        XCTAssertEqual(locationManager.geofenceIsMoving, true)
        XCTAssertNotNil(locationManager.geofenceCenter)

        // Become stationary very close to the same spot - well under any drift threshold.
        let nearbyLoc = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.77491, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        XCTAssertLessThan(nearbyLoc.distance(from: movingLoc), 50,
            "Test precondition: nearby fix must be well under any recenter threshold")

        locationManager.handleStationarityUpdate(nearbyLoc, stationary: true)

        XCTAssertEqual(locationManager.geofenceIsMoving, false,
            "geofence must re-arm at the stationary radius on a mode change, even without drift")
        XCTAssertEqual(locationManager.geofenceCenter?.coordinate.latitude, nearbyLoc.coordinate.latitude)
    }

}
