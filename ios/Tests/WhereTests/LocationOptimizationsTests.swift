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
        
        var requestPermissionAndStartCalled = false
        func requestPermissionAndStart() { requestPermissionAndStartCalled = true }
        
        var requestImmediateLocationCalled = false
        func requestImmediateLocation() { requestImmediateLocationCalled = true }
        
        func sharingStateChanged() {}
    }

    @MainActor
    class MockLocationClient: LocationSyncServiceTests.MockLocationClient {
        var sendLocationCallCount = 0
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallCount += 1
            try await super.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
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

    func testMotionAdaptiveSettings() async throws {
        let locationManager = LocationManager.shared
        let manager = CLLocationManager()
        locationManager.manager = manager
        
        // 1. Simulate movement
        let fastLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            course: 0,
            speed: 5, // 5 m/s > 1.0 m/s
            timestamp: Date()
        )
        
        locationManager.locationManager(manager, didUpdateLocations: [fastLocation])
        
        // Wait for Task @MainActor
        try await Task.sleep(nanoseconds: 100_000_000)
        
        XCTAssertEqual(manager.distanceFilter, 20)
        XCTAssertEqual(manager.activityType, .automotiveNavigation)
        
        // 2. Simulate stationary
        let slowLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            course: 0,
            speed: 0.2, // 0.2 m/s < 1.0 m/s
            timestamp: Date()
        )
        
        locationManager.locationManager(manager, didUpdateLocations: [slowLocation])
        
        try await Task.sleep(nanoseconds: 100_000_000)
        
        XCTAssertEqual(manager.distanceFilter, kCLDistanceFilterNone)
        XCTAssertEqual(manager.activityType, .other)
    }

}
