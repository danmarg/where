import XCTest
import Combine
import UIKit
import CoreLocation
@preconcurrency import Shared
@testable import Where

@MainActor
class LocationSyncServiceTests: XCTestCase {
    var service: LocationSyncService!

    class MockRawKeyValueStorage: Shared.RawKeyValueStorage, @unchecked Sendable {
        private let lock = NSLock()
        private var data: [String: String] = [:]
        func getString(key: String) -> String? {
            lock.lock(); defer { lock.unlock() }
            return data[key]
        }
        func putString(key: String, value: String) {
            lock.lock(); defer { lock.unlock() }
            data[key] = value
        }
    }

    @MainActor
    class MockLocationProvider: LocationProviding {
        @Published var location: CLLocation? = nil
        var locationPublisher: AnyPublisher<CLLocation?, Never> {
            $location.eraseToAnyPublisher()
        }
        var lastLocation: CLLocation? { location }
        var requestPermissionAndStartCalled = false
        func requestPermissionAndStart() {
            requestPermissionAndStartCalled = true
        }
    }

    var mockStorage: MockRawKeyValueStorage!
    var mockLocationProvider: MockLocationProvider!

    override func setUp() async throws {
        self.mockStorage = MockRawKeyValueStorage()
        self.mockLocationProvider = MockLocationProvider()
        let e2eeManager = Shared.E2eeManager(storage: mockStorage)
        let userStore = Shared.UserStore(storage: mockStorage)
        self.service = LocationSyncService(e2eeManager: e2eeManager, userStore: userStore, locationClient: nil, locationProvider: mockLocationProvider)
        self.service.skipUpdateVisibleUsers = true
        self.service.beginBackgroundTask = { _, _ in .invalid }
        self.service.endBackgroundTask = { _ in }
    }

    func testThrottleLogic() async throws {
        let lat = 37.7749
        let lng = -122.4194

        let expectation1 = XCTestExpectation(description: "Initial send")
        let mockClient = MockLocationClient()
        mockClient.sendLocationCallback = {
            expectation1.fulfill()
        }

        // Re-init service with mock client and mock location provider
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)

        // Initial send
        service.sendLocation(lat: lat, lng: lng)
        await fulfillment(of: [expectation1], timeout: 1.0)

        // Immediate second send should be throttled
        let expectation2 = XCTestExpectation(description: "Throttled send")
        expectation2.isInverted = true
        mockClient.sendLocationCallback = {
            expectation2.fulfill()
        }
        service.sendLocation(lat: lat + 0.1, lng: lng + 0.1)
        await fulfillment(of: [expectation2], timeout: 0.1)

        // Forced send should bypass throttle
        let expectation3 = XCTestExpectation(description: "Forced send")
        mockClient.sendLocationCallback = {
            expectation3.fulfill()
        }
        service.sendLocation(lat: lat + 0.2, lng: lng + 0.2, force: true)
        await fulfillment(of: [expectation3], timeout: 1.0)
    }

    @MainActor
    class MockLocationClient: LocationClientProtocol {
        var sendLocationCallback: (@Sendable () -> Void)?
        private var _pollCallCount = 0
        var pollResult: [Shared.UserLocation] = []
        var pollCallCount: Int {
            return _pollCallCount
        }
        func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallback?()
        }
        func sendLocationToFriend(friendId: String, lat: Double, lng: Double) async throws {
            sendLocationCallback?()
        }
        func poll(isForeground: Bool, pausedFriendIds: Set<String>) async throws -> [Shared.UserLocation] {
            _pollCallCount += 1
            return pollResult
        }
        func pollPendingInvites() async throws -> [Shared.PendingInviteResult] {
            return []
        }
        func postKeyExchangeInit(qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws {
            // No-op
        }
    }

    // MARK: - firePoll foreground/background gating

    func testFirePoll_ForegroundDoesPollFriends() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0, "Should poll friends when in foreground")
    }

    func testFirePoll_BackgroundStillPollsFriends() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { false }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should poll friends in background so locations stay fresh when stationary")
    }

    func testFirePoll_BackgroundPollsEvenWhenNotSharing() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { false }
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should still poll for Ratchet Acks in background even when not sharing")
    }

    func testTimerInterval_BackgroundNotSharing_Is30min() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { false }
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        let interval = await service.targetPollInterval()
        XCTAssertEqual(interval, 30 * 60, accuracy: 0.1,
                       "Background maintenance poll (not sharing) should be 30 min")
    }

    func testFirePoll_RapidPollsEvenInBackground() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { false }
        service.startPolling()
        service.lastRapidPollTrigger = Date()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0, "Should poll friends during rapid mode even in background")
    }

    // MARK: - Timer interval selection

    func testTimerInterval_Foreground_Is10s() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        let interval = await service.targetPollInterval()
        XCTAssertEqual(interval, 10, accuracy: 0.1,
                       "Foreground poll interval should be 10s")
    }

    func testTimerInterval_Background_Is5min() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isInForeground = { false }
        service.startPolling()

        await service.firePoll()

        let interval = await service.targetPollInterval()
        XCTAssertEqual(interval, 5 * 60, accuracy: 0.1,
                       "Background poll interval (sharing) should be 5 min")
    }

    func testRapidPollResetAfterFirstLocationUpdate() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)

        service.lastRapidPollTrigger = Date()
        let isRapidInitial = await service.isRapidPolling()
        XCTAssertTrue(isRapidInitial)

        let existingFriendId = "existing_friend"
        let update1 = Shared.UserLocation(userId: existingFriendId, lat: 1.0, lng: 2.0, timestamp: 123)
        mockClient.pollResult = [update1]

        await service.confirmPendingInit(payload: Shared.KeyExchangeInitPayload(v: 1, token: "t", ekPub: kotlinByteArray(from: Data([1])), keyConfirmation: kotlinByteArray(from: Data([2])), suggestedName: "n"), name: "n")

        await service.pollAll(updateUi: true)

        let isRapidAfterPartial = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterPartial)

        let addedFriendId = service.friends.first?.id ?? ""
        if !addedFriendId.isEmpty {
            let update2 = Shared.UserLocation(userId: addedFriendId, lat: 3.0, lng: 4.0, timestamp: 456)
            mockClient.pollResult = [update2]

            await service.pollAll(updateUi: true)

            let isRapidAfterComplete = await service.isRapidPolling()
            XCTAssertFalse(isRapidAfterComplete)
            XCTAssertEqual(service.lastRapidPollTrigger.timeIntervalSince1970, 0, accuracy: 0.1)
        }
    }

    // MARK: - Heartbeat-in-pollAll

    func testPollAllTriggersHeartbeatWhenOverdue() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSinceNow: -400) // > 300s ago — heartbeat due
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)

        let expectation = XCTestExpectation(description: "Heartbeat send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testPollAllSkipsHeartbeatWhenNoLocationAvailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSinceNow: -400) // > 300s — heartbeat due
        // mockLocationProvider.location is nil (simulates first-ever launch before any GPS fix)

        let expectation = XCTestExpectation(description: "No heartbeat send")
        expectation.isInverted = true
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)
        await fulfillment(of: [expectation], timeout: 0.1)
    }

    func testPollAllDoesNotTriggerHeartbeatWhenRecentlySent() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSinceNow: -10) // just 10s ago — no heartbeat due
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)

        let expectation = XCTestExpectation(description: "No heartbeat send")
        expectation.isInverted = true
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)
        await fulfillment(of: [expectation], timeout: 0.1)
    }

    // MARK: - Persistent Storage Tests

    func testDisplayNameStoredInKeychain() async throws {
        service.displayName = "Test User"
        XCTAssertEqual(mockStorage.getString(key: "display_name"), "Test User")
    }

    func testIsSharingLocationStoredInKeychain() async throws {
        service.isSharingLocation = false
        XCTAssertEqual(mockStorage.getString(key: "is_sharing"), "false")
        service.isSharingLocation = true
        XCTAssertEqual(mockStorage.getString(key: "is_sharing"), "true")
    }

    func testPausedFriendIdsStoredInKeychain() async throws {
        service.pausedFriendIds = ["friend1", "friend2"]
        let stored = mockStorage.getString(key: "paused_friends")
        XCTAssertNotNil(stored)
        XCTAssertTrue(stored?.contains("friend1") == true)
        XCTAssertTrue(stored?.contains("friend2") == true)
    }

    func testKeychainNotUserDefaults() async throws {
        service.displayName = "Sensitive Name"
        XCTAssertNil(UserDefaults.standard.string(forKey: "display_name"))
    }

    // MARK: - sendLocationOnBackground

    func testSendLocationOnBackground_SendsWhenSharingAndLocationAvailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.lastSentTime = Date(timeIntervalSinceNow: -60) // > 30s ago, not throttled
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)

        let expectation = XCTestExpectation(description: "Background send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()

        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testSendLocationOnBackground_SkipsWhenNotSharing() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = false
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)

        let expectation = XCTestExpectation(description: "No background send")
        expectation.isInverted = true
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()
        await fulfillment(of: [expectation], timeout: 0.1)
    }

    func testSendLocationOnBackground_SkipsWhenNoLocation() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        // mockLocationProvider.location is nil and no prior send

        let expectation = XCTestExpectation(description: "No background send")
        expectation.isInverted = true
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()
        await fulfillment(of: [expectation], timeout: 0.1)
    }

    func testSendLocationOnBackground_UsesLastSentLocationWhenGpsUnavailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        // No GPS fix
        // Simulate a prior send having happened
        service.sendLocation(lat: 37.1, lng: -122.1)
        // Advance lastSentTime so throttle doesn't block next call
        service.lastSentTime = Date(timeIntervalSinceNow: -60)

        let expectation = XCTestExpectation(description: "Background fallback send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()

        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testHeartbeatFallsBackToLastSentLocationWhenGpsUnavailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        // No GPS fix, but we have a prior send
        service.sendLocation(lat: 37.1, lng: -122.1)
        // Make heartbeat due
        service.lastSentTime = Date(timeIntervalSinceNow: -400)

        let expectation = XCTestExpectation(description: "Heartbeat fallback send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testIsRapidPolling() async throws {
        service.lastRapidPollTrigger = Date(timeIntervalSinceNow: -10)
        let isRapid1 = await service.isRapidPolling()
        XCTAssertTrue(isRapid1)

        service.lastRapidPollTrigger = Date(timeIntervalSinceNow: -301)
        let isRapid2 = await service.isRapidPolling()
        XCTAssertFalse(isRapid2)
    }

    class SendCountBox: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func increment() {
            lock.lock()
            defer { lock.unlock() }
            count += 1
        }
        func getCount() -> Int {
            lock.lock()
            defer { lock.unlock() }
            return count
        }
    }

    class ExpiryHandlerBox: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func increment() {
            lock.lock()
            defer { lock.unlock() }
            count += 1
        }
        func getCount() -> Int {
            lock.lock()
            defer { lock.unlock() }
            return count
        }
    }
}
