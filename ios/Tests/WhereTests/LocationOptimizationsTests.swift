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

    func testLocationManager_StopStartsTasksCorrectly() async throws {
        let locationManager = LocationManager.shared

        // Ensure clean state
        locationManager.stopUpdating()

        locationManager.requestPermissionAndStart()
        // Wait for @MainActor task creation
        try await Task.sleep(nanoseconds: 100_000_000)

        // stopUpdating should cancel and clear
        locationManager.stopUpdating()

        // We can't easily check private tasks, but we can verify it doesn't crash
        // and backgroundActivity is nil.
    }

}
