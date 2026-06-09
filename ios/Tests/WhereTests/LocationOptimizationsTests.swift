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
    }

    @MainActor
    class MockLocationClient: LocationSyncServiceTests.MockLocationClient {
        var sendLocationCallCount = 0
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool) async throws {
            sendLocationCallCount += 1
            try await super.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds, stationary: stationary)
        }
    }

    override func setUp() async throws {
        self.mockLocationProvider = MockLocationProvider()
        self.mockLocationClient = MockLocationClient()
        let e2eeManager = Shared.E2eeManager(sqlDriver: Shared.IosSqlDriverKt.createIosSqlDriver(name: ":memory:"))
        let userStore = Shared.UserStore(storage: LocationSyncServiceTests.MockRawKeyValueStorage())
        self.service = LocationSyncService(e2eeManager: e2eeManager, userStore: userStore, locationClient: mockLocationClient, locationProvider: mockLocationProvider)
        self.service.beginBackgroundTask = { _, _ in .invalid }
        self.service.endBackgroundTask = { _ in }
    }

    func testHeartbeat_CallsRequestImmediateLocation() async throws {
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSinceNow: -301) // > 5 mins
        
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

    func testLocationManager_StationaryDebounce_JitterDoesNotCancelTimer() async throws {
        let locationManager = LocationManager.shared
        let anchor = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        // Arm a stationary reading to set the anchor and start the timer.
        locationManager.handleStationarityUpdate(anchor, stationary: true)
        XCTAssertNotNil(locationManager.stationaryTask, "stationaryTask must be created on first stationary reading")
        XCTAssertTrue(locationManager.isStationary)
        let originalTask = locationManager.stationaryTask!

        // Feed a non-stationary fix 50 m from the anchor — this is GPS jitter.
        let jitterFix = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7753, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        XCTAssertLessThan(jitterFix.distance(from: anchor), LocationSyncService.minimumReportingDistanceMeters,
            "Test precondition: jitter fix must be < 200m from anchor")

        locationManager.handleStationarityUpdate(jitterFix, stationary: false)

        XCTAssertFalse(originalTask.isCancelled,
            "stationaryTask must not be cancelled by a sub-200m jitter reading")
        XCTAssertTrue(locationManager.isStationary,
            "isStationary must remain true for a jitter reading")
        XCTAssertNotNil(locationManager.stationaryAnchor,
            "stationaryAnchor must be preserved for a jitter reading")

        // Clean up
        locationManager.stationaryTask?.cancel()
        locationManager.stationaryTask = nil
        locationManager.stationaryAnchor = nil
        locationManager.isStationary = false
    }

    func testLocationManager_StationaryDebounce_LargeMoveCancelsTimer() async throws {
        let locationManager = LocationManager.shared
        let anchor = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        // Arm stationary state.
        locationManager.handleStationarityUpdate(anchor, stationary: true)
        XCTAssertNotNil(locationManager.stationaryTask)
        let originalTask = locationManager.stationaryTask!

        // Feed a non-stationary fix 300 m away — genuine movement.
        let farFix = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7776, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        XCTAssertGreaterThanOrEqual(farFix.distance(from: anchor), LocationSyncService.minimumReportingDistanceMeters,
            "Test precondition: far fix must be >= 200m from anchor")

        locationManager.handleStationarityUpdate(farFix, stationary: false)

        XCTAssertTrue(originalTask.isCancelled,
            "stationaryTask must be cancelled when movement >= 200m from anchor")
        XCTAssertFalse(locationManager.isStationary,
            "isStationary must be false after genuine movement")
        XCTAssertNil(locationManager.stationaryAnchor,
            "stationaryAnchor must be cleared after genuine movement")
        XCTAssertNil(locationManager.stationaryTask,
            "stationaryTask must be nil after genuine movement")
    }

    func testLocationManager_StationaryUpdate_SetsAnchorOnFirstStationaryReading() async throws {
        let locationManager = LocationManager.shared
        // Ensure clean state.
        locationManager.stationaryTask?.cancel()
        locationManager.stationaryTask = nil
        locationManager.stationaryAnchor = nil
        locationManager.isStationary = false

        let loc = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.handleStationarityUpdate(loc, stationary: true)

        XCTAssertNotNil(locationManager.stationaryTask, "stationaryTask must be started on first stationary reading")
        XCTAssertNotNil(locationManager.stationaryAnchor, "stationaryAnchor must be set on first stationary reading")
        XCTAssertTrue(locationManager.isStationary)

        // A second stationary reading must not replace the existing task or anchor.
        let anchor1Lat = locationManager.stationaryAnchor?.coordinate.latitude
        let loc2 = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7750, longitude: -122.4194),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )
        locationManager.handleStationarityUpdate(loc2, stationary: true)

        XCTAssertNotNil(locationManager.stationaryTask,
            "stationaryTask must still exist after a second stationary reading")
        XCTAssertFalse(locationManager.stationaryTask!.isCancelled,
            "stationaryTask must not be cancelled by a second stationary reading")
        XCTAssertEqual(locationManager.stationaryAnchor?.coordinate.latitude, anchor1Lat,
            "stationaryAnchor must not shift on subsequent stationary readings")

        // Clean up
        locationManager.stationaryTask?.cancel()
        locationManager.stationaryTask = nil
        locationManager.stationaryAnchor = nil
        locationManager.isStationary = false
    }

    func testSendLocation_SoftwareDistanceFilter() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.lastSentTime = Date(timeIntervalSinceNow: -60) // Not throttled by time

        // 1. Initial send (no lastSentLocation yet)
        service.sendLocation(lat: 37.0, lng: -122.0)
        await service.currentSendTask?.value
        XCTAssertEqual(mockClient.sendLocationCallCount, 1)

        // 2. Send location 10m away -> Should be filtered (returns sync, no task to await)
        service.sendLocation(lat: 37.00009, lng: -122.0) // ~10m North
        XCTAssertEqual(mockClient.sendLocationCallCount, 1, "Should filter 10m move")

        // 3. Send location 250m away -> Should be sent
        service.lastSentTime = Date(timeIntervalSinceNow: -60) // reset throttle window
        service.sendLocation(lat: 37.0023, lng: -122.0) // ~255m North
        await service.currentSendTask?.value
        XCTAssertEqual(mockClient.sendLocationCallCount, 2, "Should allow 250m move")

        // 4. Forced heartbeat with 0m move -> Should be sent
        service.sendLocation(lat: 37.0023, lng: -122.0, force: true)
        await service.currentSendTask?.value
        XCTAssertEqual(mockClient.sendLocationCallCount, 3, "Should allow forced 0m move")
    }

}
