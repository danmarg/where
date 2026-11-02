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
        func putString(key: String, value: String) throws {
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
        var isStationary: Bool = false
        var requestPermissionAndStartCalled = false
        func requestPermissionAndStart() {
            requestPermissionAndStartCalled = true
        }
        var requestImmediateLocationCalled = false
        func requestImmediateLocation() {
            requestImmediateLocationCalled = true
        }
        func sharingStateChanged() {}
    }

    var mockStorage: MockRawKeyValueStorage!
    var mockLocationProvider: MockLocationProvider!

    override func setUp() async throws {
        // The heartbeat waits this long for a fresh fix; mocks never deliver one on request.
        LocationSyncService.defaultLocationFixTimeout = 0
        self.mockStorage = MockRawKeyValueStorage()
        self.mockLocationProvider = MockLocationProvider()
        // A fresh database for each test
        let e2eeManager = makeTestE2eeManager()
        let userStore = Shared.UserStore(storage: mockStorage)
        self.service = LocationSyncService(e2eeManager: e2eeManager, userStore: userStore, locationClient: nil, locationProvider: mockLocationProvider)
        self.service.skipUpdateVisibleUsers = true
        self.service.beginBackgroundTask = { _, _ in .invalid }
        self.service.endBackgroundTask = { _ in }
        // Kill the background poll timer and suppress network-restore Tasks mid-test
        self.service.pollTimer?.invalidate()
        self.service.skipNetworkRestore = true
    }

    override func tearDown() async throws {
        service?.sendScheduler.close()
        LocationSyncService.defaultLocationFixTimeout = 10
    }

    @MainActor
    class MockLocationClient: LocationClientProtocol {
        var sendLocationCallback: (@Sendable () -> Void)?
        var sendLocationToFriendCallback: (@Sendable (String) -> Void)?
        private var _pollCallCount = 0
        var pollResult: [Shared.UserLocation] = []
        var pollCallCount: Int {
            return _pollCallCount
        }
        var lastStationary: Bool? = nil
        var lastLat: Double? = nil
        /// Stops sent so far; the stamp, as in Shared.LocationClient.
        var stopsIssued: Int64 = 0
        var onStop: (@MainActor () -> Void)?
        func sharingStamp() -> Int64 { stopsIssued }
        func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool, stamp: Int64) async throws {
            lastStationary = stationary
            lastLat = lat
            sendLocationCallback?()
        }
        func sendLocationToFriend(friendId: String, lat: Double, lng: Double, stationary: Bool, stamp: Int64) async throws {
            sendLocationToFriendCallback?(friendId)
            sendLocationCallback?()
        }
        func sendStoppedSharing(pausedFriendIds: Set<String>) async throws {
            stopsIssued += 1
            onStop?()
        }
        func sendStoppedSharingToFriend(friendId: String) async throws {
            stopsIssued += 1
            onStop?()
        }
        var removeFriendCalls: [(friendId: String, pausedFriendIds: Set<String>, sharingEnabled: Bool)] = []
        func removeFriend(friendId: String, pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws {
            removeFriendCalls.append((friendId, pausedFriendIds, sharingEnabled))
        }
        var processOrphanedOutboxesCallCount = 0
        func processOrphanedOutboxes() async throws {
            processOrphanedOutboxesCallCount += 1
        }
        var onPoll: (@MainActor () -> Void)?
        func poll(isForeground: Bool, pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws -> [Shared.UserLocation] {
            _pollCallCount += 1
            onPoll?()
            return pollResult
        }
        var pendingInviteResults: [Shared.PendingInviteResult] = []
        var pendingInvitePollCount = 0
        func pollPendingInvites() async throws -> [Shared.PendingInviteResult] {
            pendingInvitePollCount += 1
            return pendingInviteResults
        }
        func postKeyExchangeInit(friendId: String, qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws {
            // No-op
        }
        func syncNow(pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws {}
    }

    // MARK: - firePoll foreground/background gating

    func testFirePoll_ForegroundDoesPollFriends() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0, "Should poll friends when in foreground")
    }

    func testFirePoll_BackgroundStillPollsFriends() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isInForeground = { false }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should poll friends in background so locations stay fresh when stationary")
    }

    func testFirePoll_BackgroundPollsEvenWhenNotSharing() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isInForeground = { false }
        try await addFriend() // with no friends it's 30 min regardless of sharing
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should still poll for Ratchet Acks in background even when not sharing")
    }

    func testTimerInterval_BackgroundNotSharing_Is30min() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isInForeground = { false }
        try await addFriend() // with no friends it's 30 min regardless of sharing
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        let interval = service.targetPollInterval()
        XCTAssertEqual(interval, 30 * 60, accuracy: 0.1,
                       "Background maintenance poll (not sharing) should be 30 min")
    }

    func testFirePoll_RapidPollsEvenInBackground() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
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
        service.skipNetworkRestore = true
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        let interval = service.targetPollInterval()
        XCTAssertEqual(interval, 10, accuracy: 0.1,
                       "Foreground poll interval should be 10s")
    }

    func testTimerInterval_Background_Is5min() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isInForeground = { false }
        try await addFriend()
        service.startPolling()

        await service.firePoll()

        let interval = service.targetPollInterval()
        XCTAssertEqual(interval, 5 * 60, accuracy: 0.1,
                       "Background poll interval (sharing) should be 5 min")
    }

    func testRapidPollResetAfterFirstLocationUpdate() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true

        service.lastRapidPollTrigger = Date()
        let isRapidInitial = service.isRapidPolling()
        XCTAssertTrue(isRapidInitial)

        let existingFriendId = "existing_friend"
        let update1 = Shared.UserLocation(userId: existingFriendId, lat: 1.0, lng: 2.0, timestamp: 123)
        mockClient.pollResult = [update1]

        await service.confirmPendingInit(payload: Shared.KeyExchangeInitPayload(v: 1, ekPub: kotlinByteArray(from: Data([1])), keyConfirmation: kotlinByteArray(from: Data([2])), encryptedName: kotlinByteArray(from: Data(repeating: 0, count: 31)), suggestedName: "n", msgId: "m1"), name: "n")

        await service.pollAll(updateUi: true)

        let isRapidAfterPartial = service.isRapidPolling()
        XCTAssertTrue(isRapidAfterPartial)

        let addedFriendId = service.repo.friends.first?.id ?? ""
        if !addedFriendId.isEmpty {
            let update2 = Shared.UserLocation(userId: addedFriendId, lat: 3.0, lng: 4.0, timestamp: 456)
            mockClient.pollResult = [update2]

            await service.pollAll(updateUi: true)

            let isRapidAfterComplete = service.isRapidPolling()
            XCTAssertFalse(isRapidAfterComplete)
            XCTAssertEqual(service.lastRapidPollTrigger.timeIntervalSince1970, 0, accuracy: 0.1)
        }
    }

    // MARK: - Heartbeat-in-pollAll

    func testPollAllTriggersHeartbeatWhenOverdue() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true

        await service.pollAll(updateUi: false)

        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, "Should call requestImmediateLocation when heartbeat is due")
    }

    func testPollAllHeartbeat_SendsStationaryTrue_WhenStationaryCheckReturnsTrue() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isStationaryQuery = { true }
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let expectation = XCTestExpectation(description: "Stationary heartbeat send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertEqual(mockClient.lastStationary, true,
            "Heartbeat while stationary must send stationary: true")
    }

    func testPollAllHeartbeat_SendsStationaryFalse_WhenStationaryCheckReturnsFalse() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isStationaryQuery = { false }
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let expectation = XCTestExpectation(description: "Moving heartbeat send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertEqual(mockClient.lastStationary, false,
            "Heartbeat while moving must send stationary: false")
    }

    func testPollAllHeartbeat_ConvergesStationaryFlagIntoProvider() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isStationaryQuery = { true }
        mockLocationProvider.isStationary = false  // starts false
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        await service.pollAll(updateUi: false)

        XCTAssertTrue(mockLocationProvider.isStationary,
            "pollAll must write the CoreMotion result back into locationProvider.isStationary via the protocol, not a cast")
    }

    func testPollAllDoesNotTriggerHeartbeatWhenRecentlySent() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        // A send just now: no heartbeat due.
        service.reportLocation(lat: 37.7, lng: -122.4, accuracy: 10)
        await service.drainTask?.value
        mockLocationProvider.requestImmediateLocationCalled = false

        await service.pollAll(updateUi: false)

        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled, "Should NOT call requestImmediateLocation when heartbeat is NOT due")
    }

    // Regression tests for the stationary backstop: CoreMotion's stationary detection and the
    // underlying CLLocationUpdate stream/geofence (LocationManager) can each independently get
    // stuck, mirroring the Android isStill/Activity-Recognition gap - without this, re-reporting
    // the cached location forever would freeze a friend's view of us even though we're "healthy".

    func testPollAllHeartbeat_StationaryBackstop_ForcesFreshFixAfterInterval() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isStationaryQuery = { true }
        // Well past stationaryForceFixInterval - simulates a stuck stationary pipeline.
        service.lastForcedFixTime = Date(timeIntervalSinceNow: -(LocationSyncService.stationaryForceFixInterval + 1))
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let expectation = XCTestExpectation(description: "Stationary backstop heartbeat send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled,
            "Must force a fresh GPS fix once the backstop interval elapses, even while classified stationary")
        XCTAssertEqual(mockClient.lastStationary, true,
            "The backstop-forced send must still report stationary: true - the device genuinely is stationary")
    }

    func testPollAllHeartbeat_StationaryNoBackstop_DoesNotForceFixBeforeIntervalElapses() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isStationaryQuery = { true }
        // Well under stationaryForceFixInterval - must preserve stationary battery savings.
        service.lastForcedFixTime = Date(timeIntervalSinceNow: -60)
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let expectation = XCTestExpectation(description: "Stationary cached-location heartbeat send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.pollAll(updateUi: false)

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled,
            "Must NOT force a fresh fix before the backstop interval elapses - only re-report the cached location")
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
        service.repo.pausedFriendIds = ["friend1", "friend2"]
        let stored = mockStorage.getString(key: "paused_friends")
        XCTAssertNotNil(stored)
        XCTAssertTrue(stored?.contains("friend1") == true)
        XCTAssertTrue(stored?.contains("friend2") == true)
    }

    func testKeychainNotUserDefaults() async throws {
        service.displayName = "Sensitive Name"
        XCTAssertNil(UserDefaults.standard.string(forKey: "display_name"))
    }

    // MARK: - removeFriend

    /// Removal goes through LocationClient.removeFriend (which tells the friend we stopped
    /// sharing) with the current sharing state, rather than silently deleting them locally.
    func testRemoveFriend_GoesThroughLocationClientWithSharingState() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipUpdateVisibleUsers = true
        service.isSharingLocation = true
        service.repo.pausedFriendIds = ["carol"]

        await service.removeFriend(id: "bob")

        XCTAssertEqual(mockClient.removeFriendCalls.count, 1)
        XCTAssertEqual(mockClient.removeFriendCalls.first?.friendId, "bob")
        XCTAssertEqual(mockClient.removeFriendCalls.first?.pausedFriendIds, ["carol"])
        XCTAssertEqual(mockClient.removeFriendCalls.first?.sharingEnabled, true)
        XCTAssertEqual(mockClient.processOrphanedOutboxesCallCount, 1, "the queued StoppedSharing is delivered right away")
    }

    // MARK: - sendLocationOnBackground

    func testSendLocationOnBackground_SendsWhenSharingAndLocationAvailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)

        let expectation = XCTestExpectation(description: "Background send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()

        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testSendLocationOnBackground_SkipsWhenNotSharing() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
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
        service.skipNetworkRestore = true
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

    func testSendLocationOnBackground_PassesStationaryFlag_WhenProviderIsStationary() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)
        mockLocationProvider.isStationary = true

        let expectation = XCTestExpectation(description: "Background stationary send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertEqual(mockClient.lastStationary, true,
            "sendLocationOnBackground must pass stationary: true when the provider is stationary")
    }

    func testSendLocationOnBackground_PassesStationaryFalse_WhenProviderIsMoving() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        mockLocationProvider.location = CLLocation(latitude: 37.7749, longitude: -122.4194)
        mockLocationProvider.isStationary = false

        let expectation = XCTestExpectation(description: "Background moving send")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.sendLocationOnBackground()

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertEqual(mockClient.lastStationary, false,
            "sendLocationOnBackground must pass stationary: false when the provider is not stationary")
    }

    func testSharingTurnedOn_UsesLastReportedLocationWhenGpsUnavailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        // No GPS fix now, but one was reported earlier.
        service.reportLocation(lat: 37.1, lng: -122.1, accuracy: 10)
        await service.drainTask?.value
        mockClient.lastLat = nil

        // Off → on forces a send of the best available location: the last reported one.
        service.isSharingLocation = false
        service.isSharingLocation = true
        await service.drainTask?.value

        XCTAssertEqual(mockClient.lastLat, 37.1)
    }

    // MARK: - onForegroundEntry

    func testOnForegroundEntry_RequestsImmediateLocation() async throws {
        service.onForegroundEntry()
        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled,
            "onForegroundEntry must request an immediate GPS fix")
    }

    func testOnForegroundEntry_SendsLocationWhenSharingAndAvailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        // Specify horizontalAccuracy explicitly: CLLocation(latitude:longitude:) gives
        // horizontalAccuracy = -1 (invalid), which bestAvailableLocation would reject.
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let expectation = XCTestExpectation(description: "Location sent on foreground entry")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        service.onForegroundEntry()
        await fulfillment(of: [expectation], timeout: 1.0)
    }

    func testOnForegroundEntry_NodoubleSendWhenHeartbeatAlsoDue() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        // Heartbeat due (fresh scheduler), so pollAll would also try to send.
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let sendCount = SendCountBox()
        mockClient.sendLocationCallback = { sendCount.increment() }

        service.onForegroundEntry()
        await service.drainTask?.value
        await service.foregroundPollTask?.value

        XCTAssertEqual(sendCount.getCount(), 1,
            "the foreground send counts as the heartbeat's attempt, so pollAll mustn't send it again")
    }

    func testOnForegroundEntry_FiresImmediatePollBypassingInterval() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isInForeground = { true }
        // Set lastPollTime to 5s ago — normally foreground interval (10s) would block a poll.
        service.lastPollTime = Date(timeIntervalSinceNow: -5)
        let before = mockClient.pollCallCount

        service.onForegroundEntry()
        await service.foregroundPollTask?.value

        XCTAssertGreaterThan(mockClient.pollCallCount, before,
            "onForegroundEntry must poll immediately even if the foreground interval hasn't elapsed")
    }

    func testOnForegroundEntry_ResetsLastPollTimeToDistantPast() async throws {
        // lastPollTime = .distantPast is what causes the 90s stuck-poll guard to trigger
        // and lets a new poll proceed even if isPollInFlight was true.
        service.lastPollTime = Date()

        service.onForegroundEntry()

        XCTAssertEqual(service.lastPollTime.timeIntervalSince1970, Date.distantPast.timeIntervalSince1970, accuracy: 1.0,
            "onForegroundEntry must reset lastPollTime so the stuck-poll guard fires immediately")
    }

    func testIsRapidPolling() async throws {
        service.lastRapidPollTrigger = Date(timeIntervalSinceNow: -10)
        let isRapid1 = service.isRapidPolling()
        XCTAssertTrue(isRapid1)

        service.lastRapidPollTrigger = Date(timeIntervalSinceNow: -301)
        let isRapid2 = service.isRapidPolling()
        XCTAssertFalse(isRapid2)
    }

    func testAwaitingFirstUpdateStopsForcingRapidPollAfterTimeout() async throws {
        // Regression test for #336: a friend who never sends a first location update must not
        // pin the client at rapid polling forever.
        service.awaitingFirstUpdateIds = ["stale_friend": Date(timeIntervalSinceNow: -301)]
        XCTAssertFalse(service.isRapidPolling(), "An awaiting-first-update entry past the timeout must stop forcing rapid polling")

        service.awaitingFirstUpdateIds = ["fresh_friend": Date(timeIntervalSinceNow: -10)]
        XCTAssertTrue(service.isRapidPolling(), "A recently-added awaiting-first-update entry should still force rapid polling")
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

    /// Regression for #405: dismissing the invite sheet must keep an unexported invite, otherwise a
    /// scan that arrives after the sheet closes is dropped and the scanner stays "Pending" forever.
    func testDismissInvite_keepsUnexportedInvite() async throws {
        let qr = try await service.e2eeManager.createInvite(suggestedName: "Alice")
        service.isInviteSheetShowing = true
        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)

        service.dismissInvite()

        XCTAssertFalse(service.isInviteSheetShowing)
        XCTAssertTrue(service.repo.inviteState is Shared.InviteState.None)
        let invites = try await service.e2eeManager.listPendingInvites()
        XCTAssertTrue(
            invites.contains { $0.qrPayload.ekPub == qr.ekPub },
            "dismissing the sheet must not delete an unexported invite"
        )
    }

    /// An invite past its `expires_at` is rejected at scan time with a clear error, not a naming dialog.
    func testProcessQrUrl_expiredInvite_isRejected() async throws {
        let created = try await service.e2eeManager.createInvite(suggestedName: "Alice")
        let expired = Shared.QrPayload(
            protocolVersion: created.protocolVersion,
            ekPub: created.ekPub,
            suggestedName: created.suggestedName,
            discoverySecret: created.discoverySecret,
            expiresAt: KotlinLong(value: Int64(Date().timeIntervalSince1970) - 3600)
        )

        XCTAssertFalse(service.processQrUrl(expired.toUrl()))
        XCTAssertNil(service.repo.pendingQrForNaming)
    }

    /// An invite from an older client has no `expires_at` and must still be accepted.
    func testProcessQrUrl_inviteWithoutExpiry_isAccepted() async throws {
        let created = try await service.e2eeManager.createInvite(suggestedName: "Alice")
        let legacy = Shared.QrPayload(
            protocolVersion: created.protocolVersion,
            ekPub: created.ekPub,
            suggestedName: created.suggestedName,
            discoverySecret: created.discoverySecret,
            expiresAt: nil
        )

        XCTAssertTrue(service.processQrUrl(legacy.toUrl()))
        XCTAssertNotNil(service.repo.pendingQrForNaming)
    }

    /// Regenerating the shown invite (name edit) replaces it instead of piling up invites.
    func testCreateInvite_whileShowingOne_renamesItInPlace() async throws {
        await service.createInvite()
        await service.createInvite()
        await service.createInvite()

        let invites = try await service.e2eeManager.listPendingInvites()
        XCTAssertEqual(invites.count, 1)
    }

    func testUiStateSynchronization_DismissalFlows() async throws {
        let qr = try await service.e2eeManager.createInvite(suggestedName: "Alice")

        // Test 1: Scanning a QR code dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)

        let qrUrl = qr.toUrl()
        service.processQrUrl(qrUrl)

        XCTAssertFalse(service.isInviteSheetShowing, "processQrUrl should dismiss the invite sheet")
        XCTAssertTrue(service.repo.inviteState is Shared.InviteState.None, "processQrUrl should reset inviteState")

        // Test 2: Confirming a QR scan dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)

        await service.confirmQrScan(qr: qr, friendName: "Alice")

        XCTAssertFalse(service.isInviteSheetShowing, "confirmQrScan should dismiss the invite sheet")
        XCTAssertTrue(service.repo.inviteState is Shared.InviteState.None, "confirmQrScan should reset inviteState")

        // Test 3: Canceling a QR scan dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)

        await service.clearInvite()

        XCTAssertFalse(service.isInviteSheetShowing, "clearInvite should dismiss the invite sheet")
        XCTAssertTrue(service.repo.inviteState is Shared.InviteState.None, "clearInvite should reset inviteState")
    }

    // MARK: - QR sheet dismissal regression

    /// Regression test for bug 1: the QR sheet must be dismissed when a scan arrives.
    ///
    /// Before the fix, LocationClient.poll() (KMM shared code) silently consumed the
    /// pending invite internally. When LocationSyncService.pollPendingInvites() ran
    /// afterwards, it found an empty list and never set isInviteSheetShowing = false.
    /// This test verifies the full dismissal path via pollAll(updateUi:true).
    func testPollAll_DismissesQrSheetWhenScanArrives() async throws {
        // 1. Alice creates a real invite so the ekPub filter in pollPendingInvites() matches.
        let qr = try await service.e2eeManager.createInvite(suggestedName: "Alice")

        // 2. Build a fake PendingInviteResult whose inviteEkPub matches Alice's real ekPub.
        //    The payload fields don't need to be cryptographically valid for this UI test.
        let fakePayload = Shared.KeyExchangeInitPayload(
            v: Shared.ProtocolConstantsKt.PROTOCOL_VERSION,
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            encryptedName: kotlinByteArray(from: Data(repeating: 0, count: 31)),
            suggestedName: "Bob",
            msgId: "msg-001"
        )
        let pendingResult = Shared.PendingInviteResult(
            payload: fakePayload,
            inviteEkPub: qr.ekPub,
            multipleScansDetected: false,
            pairingError: nil
        )

        // 3. Recreate the service with a mock client that returns the pending result.
        let mockClient = MockLocationClient()
        mockClient.pendingInviteResults = [pendingResult]
        service = LocationSyncService(
            e2eeManager: service.e2eeManager,
            userStore: service.userStore,
            locationClient: mockClient,
            locationProvider: mockLocationProvider
        )
        service.skipNetworkRestore = true
        service.skipUpdateVisibleUsers = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }

        // 4. Simulate the QR sheet being open.
        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)
        service.isInviteSheetShowing = true

        // 5. Foreground poll — this is the path that must dismiss the sheet.
        await service.pollAll(updateUi: true)

        // 6. Sheet must be dismissed and naming dialog must be offered.
        XCTAssertFalse(
            service.isInviteSheetShowing,
            "QR sheet must be dismissed when a scan arrives during pollAll(updateUi:true)"
        )
        XCTAssertNotNil(
            service.repo.pendingInitPayload,
            "pendingInitPayload must be set so the naming dialog appears after the sheet closes"
        )
    }

    /// Regression test for Fix 1: pollPendingInvites must run even when updateUi is false,
    /// as long as isInviteSheetShowing is true.
    ///
    /// Background location wakeups call pollAll(updateUi: false). Without the fix, the QR sheet
    /// would stay open indefinitely because pollPendingInvites() was guarded by `updateUi` alone.
    func testPollAll_DismissesQrSheetOnBackgroundWakeupWhenSheetIsShowing() async throws {
        let qr = try await service.e2eeManager.createInvite(suggestedName: "Alice")

        let fakePayload = Shared.KeyExchangeInitPayload(
            v: Shared.ProtocolConstantsKt.PROTOCOL_VERSION,
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            encryptedName: kotlinByteArray(from: Data(repeating: 0, count: 31)),
            suggestedName: "Bob",
            msgId: "msg-002"
        )
        let pendingResult = Shared.PendingInviteResult(
            payload: fakePayload,
            inviteEkPub: qr.ekPub,
            multipleScansDetected: false,
            pairingError: nil
        )

        let mockClient = MockLocationClient()
        mockClient.pendingInviteResults = [pendingResult]
        service = LocationSyncService(
            e2eeManager: service.e2eeManager,
            userStore: service.userStore,
            locationClient: mockClient,
            locationProvider: mockLocationProvider
        )
        service.skipNetworkRestore = true
        service.skipUpdateVisibleUsers = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }

        service.repo.inviteState = Shared.InviteState.Pending(qr: qr)
        service.isInviteSheetShowing = true

        // Background wakeup path: updateUi is false, but isInviteSheetShowing is true.
        await service.pollAll(updateUi: false)

        XCTAssertFalse(
            service.isInviteSheetShowing,
            "QR sheet must be dismissed even when pollAll(updateUi:false) if isInviteSheetShowing is true"
        )
        XCTAssertNotNil(
            service.repo.pendingInitPayload,
            "pendingInitPayload must be set so the naming dialog appears"
        )
    }

    /// Regression test for Fix 2: when location was unavailable at pairing time,
    /// the first subsequent location fix must be sent directly to the new friend
    /// (bypassing the confirmed-only filter in the broadcast sendLocation).
    ///
    /// Without the fix, the new friend (Android peer) stays isConfirmed=false indefinitely
    /// because no encrypted location message ever arrives to trigger confirmation.
    func testSendLocation_FiresForcedSendToNewFriendWhenPendingFriendIdIsSet() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(
            e2eeManager: service.e2eeManager,
            userStore: service.userStore,
            locationClient: mockClient,
            locationProvider: mockLocationProvider
        )
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }

        let expectedFriendId = "friend-bob-456"
        service.pendingForcedSendFriendId = expectedFriendId

        class StringBox: @unchecked Sendable {
            private let lock = NSLock()
            private var value: String? = nil
            func set(_ v: String) { lock.lock(); defer { lock.unlock() }; value = v }
            func get() -> String? { lock.lock(); defer { lock.unlock() }; return value }
        }
        let capturedFriendIdBox = StringBox()
        let expectation = XCTestExpectation(description: "Friend-specific forced send")
        mockClient.sendLocationToFriendCallback = { friendId in
            capturedFriendIdBox.set(friendId)
            expectation.fulfill()
        }

        service.reportLocation(lat: 37.0, lng: -122.0, accuracy: 10)
        await fulfillment(of: [expectation], timeout: 1.0)

        XCTAssertEqual(capturedFriendIdBox.get(), expectedFriendId,
            "reportLocation must fire sendLocationToFriend for the pending friend ID set during pairing")
        XCTAssertNil(service.pendingForcedSendFriendId,
            "pendingForcedSendFriendId must be cleared after the forced send fires")
    }

    // MARK: - handleNetworkRestored

    func testNetworkRestore_FreshLocation_DoesNotRequestAFix() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10, timestamp: Date()
        )

        await service.handleNetworkRestored()
        await service.drainTask?.value

        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled,
            "a fresh fix needs no new one; a send that failed offline retries through the scheduler")
    }

    func testNetworkRestore_StaleLocation_RequestsFreshFix() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10,
            timestamp: Date(timeIntervalSinceNow: -90)
        )

        await service.handleNetworkRestored()

        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, "Should request a fresh GPS fix")
    }

    func testNetworkRestore_StaleLocation_Stationary_DoesNotRequestAFix() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10,
            timestamp: Date(timeIntervalSinceNow: -90)
        )
        mockLocationProvider.isStationary = true

        await service.handleNetworkRestored()

        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled,
            "Must not request a fresh fix when stationary — that resets isStationary to false")
    }

    // MARK: - Background task expiration

    /// Records begin/end calls; the expiration handler is kept so tests can fire it.
    final class BackgroundTaskRecorder: @unchecked Sendable {
        private let lock = NSLock()
        private var handler: (@Sendable () -> Void)?
        private var _ended: [UIBackgroundTaskIdentifier] = []
        var ended: [UIBackgroundTaskIdentifier] { lock.withLock { _ended } }

        func begin(_ handler: @escaping @Sendable () -> Void) -> UIBackgroundTaskIdentifier {
            lock.withLock { self.handler = handler }
            return UIBackgroundTaskIdentifier(rawValue: 42)
        }

        func end(_ id: UIBackgroundTaskIdentifier) { lock.withLock { _ended.append(id) } }

        func fireExpiration() { lock.withLock { handler }?() }
    }

    func testSendBackgroundTaskEndsOnceEvenIfExpirationFiresLate() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        let recorder = BackgroundTaskRecorder()
        service.beginBackgroundTask = { _, handler in recorder.begin(handler) }
        service.endBackgroundTask = { id in recorder.end(id) }

        service.reportLocation(lat: 37.7, lng: -122.4, accuracy: 10, force: true)
        await service.drainTask?.value
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
        recorder.fireExpiration()
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
    }

    func testPollAllBackgroundTaskEndsOnceEvenIfExpirationFiresLate() async throws {
        // Hermetic: no real network client or CoreMotion query.
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: MockLocationClient(), locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isStationaryQuery = { false }
        let recorder = BackgroundTaskRecorder()
        service.beginBackgroundTask = { _, handler in recorder.begin(handler) }
        service.endBackgroundTask = { id in recorder.end(id) }

        await service.pollAll(updateUi: false)
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
        recorder.fireExpiration()
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
    }

    private func pollAllHeartbeatDueService(_ mockClient: MockLocationClient) -> BackgroundTaskRecorder {
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.isStationaryQuery = { false }
        let recorder = BackgroundTaskRecorder()
        service.beginBackgroundTask = { _, handler in recorder.begin(handler) }
        service.endBackgroundTask = { id in recorder.end(id) }
        return recorder
    }

    /// Control for the expiry test below: without expiry this poll does reach the invite poll
    /// and the heartbeat (which, while moving, requests a fresh fix).
    func testPollAllWithoutExpiryRunsInvitePollAndHeartbeat() async throws {
        let mockClient = MockLocationClient()
        _ = pollAllHeartbeatDueService(mockClient)
        await service.pollAll(updateUi: true)
        XCTAssertEqual(mockClient.pendingInvitePollCount, 1)
        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, "the moving heartbeat asks for a fresh fix")
    }

    /// If the background task expires mid-poll, the remaining network steps are skipped rather
    /// than started with no budget left.
    func testPollAllExpiryMidPollSkipsInvitePollAndHeartbeat() async throws {
        let mockClient = MockLocationClient()
        let recorder = pollAllHeartbeatDueService(mockClient)
        mockClient.onPoll = { recorder.fireExpiration() }
        await service.pollAll(updateUi: true)
        XCTAssertEqual(mockClient.pendingInvitePollCount, 0)
        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled, "no heartbeat after expiry")
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
    }

    /// Expiration delivered while normal completion is already ending the task (here: from
    /// inside endBackgroundTask itself, the tightest interleaving the main actor allows) must not
    /// end it a second time.
    func testBackgroundTaskExpirationDuringCompletionEndsOnce() async throws {
        let recorder = BackgroundTaskRecorder()
        let token = BackgroundTaskToken(
            name: "Test",
            begin: { _, handler in recorder.begin(handler) },
            end: { id in
                recorder.end(id)
                recorder.fireExpiration()
            }
        )
        token.end()
        token.end()
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
    }

    func testBackgroundTaskTokenOnExpireRunsOnceThenEnds() {
        let recorder = BackgroundTaskRecorder()
        let expiries = SendCountBox()
        let token = BackgroundTaskToken(
            name: "Test",
            begin: { _, handler in recorder.begin(handler) },
            end: { id in recorder.end(id) },
            onExpire: { expiries.increment() }
        )
        recorder.fireExpiration()
        recorder.fireExpiration()
        token.end()
        XCTAssertEqual(expiries.getCount(), 1)
        XCTAssertTrue(token.didExpire)
        XCTAssertEqual(recorder.ended, [UIBackgroundTaskIdentifier(rawValue: 42)])
    }

    func testBackgroundTaskTokenOnExpireDoesNotRunAfterNormalEnd() {
        let recorder = BackgroundTaskRecorder()
        let expiries = SendCountBox()
        let token = BackgroundTaskToken(
            name: "Test",
            begin: { _, handler in recorder.begin(handler) },
            end: { id in recorder.end(id) },
            onExpire: { expiries.increment() }
        )
        token.end()
        recorder.fireExpiration()
        XCTAssertEqual(expiries.getCount(), 0)
        XCTAssertFalse(token.didExpire)
    }

    // MARK: - Readings before the DB has loaded

    /// The production policy behind LocationManager.shouldHandleReadings (tests otherwise use
    /// isTrackingOverride). Before hydration, a paused user's reading must be dropped, not
    /// broadcast or used to arm the geofence; a sharing user's cold-wake reading is handled.
    func testShouldHandleReadings_PreloadUsesPersistedSharingToggle() {
        typealias LM = LocationManager
        XCTAssertFalse(LM.shouldHandleReadings(isTracking: false, isDataLoaded: false, isSharing: false), "paused, pre-load: drop")
        XCTAssertTrue(LM.shouldHandleReadings(isTracking: false, isDataLoaded: false, isSharing: true), "sharing, pre-load: cold wake")
        XCTAssertFalse(LM.shouldHandleReadings(isTracking: false, isDataLoaded: true, isSharing: true), "loaded, not tracking: drop")
        XCTAssertFalse(LM.shouldHandleReadings(isTracking: false, isDataLoaded: true, isSharing: false))
        XCTAssertTrue(LM.shouldHandleReadings(isTracking: true, isDataLoaded: true, isSharing: true))
    }

    /// When iOS refuses a background task (.invalid), the send still runs (unprotected from
    /// suspension) and nothing tries to end a task that was never granted.
    func testSendLocationStillRunsWhenBackgroundTaskIsRefused() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        let sendCount = SendCountBox()
        mockClient.sendLocationCallback = { sendCount.increment() }
        let ended = BackgroundTaskRecorder()
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { id in ended.end(id) }

        service.reportLocation(lat: 37.7, lng: -122.4, accuracy: 10, force: true)
        await service.drainTask?.value

        XCTAssertEqual(sendCount.getCount(), 1)
        XCTAssertEqual(ended.ended, [])
    }

    func testSharingOff_ResetsTheScheduler() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        let sendCount = SendCountBox()
        mockClient.sendLocationCallback = { sendCount.increment() }

        service.reportLocation(lat: 37.7, lng: -122.4, accuracy: 10)
        await service.drainTask?.value
        XCTAssertEqual(sendCount.getCount(), 1)

        // Off then on: the new session sends the same fix again rather than treating it as
        // already delivered.
        service.isSharingLocation = false
        service.isSharingLocation = true
        await service.drainTask?.value
        XCTAssertEqual(sendCount.getCount(), 2)
    }

    /// The scheduler reads stationarity when it sends, not when the fix was reported.
    func testSendReadsStationaryAtSendTime() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.beginBackgroundTask = { _, _ in .invalid }
        service.endBackgroundTask = { _ in }
        service.isSharingLocation = true
        mockLocationProvider.isStationary = false
        service.reportLocation(lat: 37.7, lng: -122.4, accuracy: 10)
        mockLocationProvider.isStationary = true  // before the worker gets to it
        await service.drainTask?.value
        XCTAssertEqual(mockClient.lastStationary, true)
    }

    // MARK: - Sharing status

    func testSharingStatus_ReducedAccuracyOnlyWarnsWhenItBlocksSending() {
        typealias S = ContentView.SharingStatus
        let usable = LocationSyncService.minBroadcastAccuracyMeters
        XCTAssertEqual(S.derive(isSharing: false, authorization: .authorizedAlways, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: nil), .paused)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .denied, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: nil), .sharingOff)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: 1500), .preciseLocationOff)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: nil), .preciseLocationOff)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: usable), .sharing)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .fullAccuracy, lastFixAccuracy: nil), .sharing)
    }

    /// Sharing, a tap always stops - limited or not; it once showed only the background rationale
    /// on When-In-Use, leaving no way to stop. Not sharing, it starts and offers whatever would lift
    /// the limit. Without permission it only opens Settings, leaving the toggle to resume sharing.
    func testSharingToggle() {
        typealias A = ContentView.SharingToggleAction
        func derive(_ isSharing: Bool, _ auth: CLAuthorizationStatus, _ accuracy: CLAccuracyAuthorization = .fullAccuracy) -> A {
            A.derive(isSharing: isSharing, authorization: auth, accuracyAuthorization: accuracy, lastFixAccuracy: nil)
        }
        for auth: CLAuthorizationStatus in [.authorizedAlways, .authorizedWhenInUse, .notDetermined] {
            XCTAssertEqual(derive(true, auth), .toggle, "auth \(auth.rawValue)")
            XCTAssertEqual(derive(true, auth, .reducedAccuracy), .toggle, "auth \(auth.rawValue), reduced")
        }
        XCTAssertEqual(derive(false, .authorizedAlways), .toggle)
        XCTAssertEqual(derive(false, .authorizedWhenInUse), .startAndRequestAlways)
        XCTAssertEqual(derive(false, .authorizedAlways, .reducedAccuracy), .startAndOpenSettings)
        XCTAssertEqual(derive(false, .authorizedWhenInUse, .reducedAccuracy), .startAndOpenSettings, "precise location is the blocking problem")
        XCTAssertEqual(derive(false, .notDetermined), .requestPermissionAndStart)
        for isSharing in [true, false] {
            XCTAssertEqual(derive(isSharing, .denied), .openSettings)
            XCTAssertEqual(derive(isSharing, .restricted), .openSettings)
        }
    }

    /// The background prompt comes up by itself once, when sharing is limited by When-In-Use.
    func testBackgroundPromptOfferedOnceWhenLimited() {
        func offer(_ auth: CLAuthorizationStatus, sharing: Bool = true, offered: Bool = false, modal: Bool = false) -> Bool {
            ContentView.shouldOfferBackgroundOnce(authorization: auth, isSharing: sharing, alreadyOffered: offered, otherModalShowing: modal)
        }
        XCTAssertTrue(offer(.authorizedWhenInUse))
        XCTAssertFalse(offer(.authorizedWhenInUse, offered: true), "only once")
        XCTAssertFalse(offer(.authorizedWhenInUse, sharing: false), "not while paused; starting offers it")
        XCTAssertFalse(offer(.authorizedWhenInUse, modal: true), "waits for other modals, which would swallow it")
        for auth: CLAuthorizationStatus in [.authorizedAlways, .denied, .notDetermined] {
            XCTAssertFalse(offer(auth), "auth \(auth.rawValue)")
        }
    }

    /// Without permission nothing can be shared, so the pill says so whatever the toggle is.
    func testSharingStatus_NoPermissionIsSharingOff() {
        typealias S = ContentView.SharingStatus
        for isSharing in [true, false] {
            for auth: CLAuthorizationStatus in [.denied, .restricted] {
                XCTAssertEqual(S.derive(isSharing: isSharing, authorization: auth, accuracyAuthorization: .fullAccuracy, lastFixAccuracy: nil), .sharingOff)
            }
        }
    }

    /// When-In-Use shares only until iOS terminates the app, so the pill says it's limited.
    func testSharingStatus_WhenInUseIsSharingLimited() {
        typealias S = ContentView.SharingStatus
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedWhenInUse, accuracyAuthorization: .fullAccuracy, lastFixAccuracy: nil), .sharingLimited)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .fullAccuracy, lastFixAccuracy: nil), .sharing)
        XCTAssertEqual(S.derive(isSharing: false, authorization: .authorizedWhenInUse, accuracyAuthorization: .fullAccuracy, lastFixAccuracy: nil), .paused)
        XCTAssertEqual(S.derive(isSharing: true, authorization: .authorizedWhenInUse, accuracyAuthorization: .reducedAccuracy, lastFixAccuracy: nil), .preciseLocationOff, "the blocking problem wins")
    }

    /// A precise fix from before Precise Location was turned off (e.g. restored at launch) must
    /// not make the pill claim "Sharing": only fixes taken under the current setting count.
    func testFixAccuracy_IgnoresFixesFromBeforeTheAccuracySettingChanged() {
        let changedAt = Date()
        let before = CLLocation(coordinate: .init(latitude: 1, longitude: 2), altitude: 0, horizontalAccuracy: 10, verticalAccuracy: -1, timestamp: changedAt.addingTimeInterval(-60))
        let after = CLLocation(coordinate: .init(latitude: 1, longitude: 2), altitude: 0, horizontalAccuracy: 1500, verticalAccuracy: -1, timestamp: changedAt.addingTimeInterval(5))
        XCTAssertNil(LocationManager.fixAccuracy(before, takenSince: changedAt))
        XCTAssertNil(LocationManager.fixAccuracy(nil, takenSince: changedAt))
        XCTAssertEqual(LocationManager.fixAccuracy(after, takenSince: changedAt), 1500)
        XCTAssertEqual(
            ContentView.SharingStatus.derive(
                isSharing: true, authorization: .authorizedAlways, accuracyAuthorization: .reducedAccuracy,
                lastFixAccuracy: LocationManager.fixAccuracy(before, takenSince: changedAt)
            ),
            .preciseLocationOff
        )
    }

    // MARK: - Restored location

    func testRestoredLocationKeepsOriginalTimestampAndAccuracy() {
        let ts = Date().addingTimeInterval(-3 * 24 * 3600).timeIntervalSince1970
        let loc = LocationManager.restoredLocation(lat: 1, lng: 2, timestamp: ts, accuracy: 35)
        XCTAssertEqual(loc.timestamp.timeIntervalSince1970, ts, accuracy: 0.001)
        XCTAssertEqual(loc.horizontalAccuracy, 35)
    }

    func testRestoredLocationWithoutMetadataIsTreatedAsStaleAndImprecise() {
        let loc = LocationManager.restoredLocation(lat: 1, lng: 2, timestamp: nil, accuracy: nil)
        XCTAssertEqual(loc.timestamp, .distantPast)
        XCTAssertGreaterThan(loc.horizontalAccuracy, LocationSyncService.minBroadcastAccuracyMeters)
    }

    func testDiscardedSessionsNoticePersistsUntilDismissed() {
        // Written by openDatabase(), possibly during a background relaunch with no UI.
        let key = LocationSyncService.discardedSessionNamesKey
        UserDefaults.standard.set(["Alice", "Bob"], forKey: key)
        defer { UserDefaults.standard.removeObject(forKey: key) }

        let relaunched = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: nil, locationProvider: mockLocationProvider)
        relaunched.pollTimer?.invalidate()
        relaunched.skipNetworkRestore = true
        XCTAssertEqual(relaunched.discardedSessionNames, ["Alice", "Bob"])

        relaunched.dismissDiscardedSessionsNotice()
        XCTAssertEqual(relaunched.discardedSessionNames, [])
        XCTAssertNil(UserDefaults.standard.stringArray(forKey: key))
    }

    /// Pairs the service with a fresh peer, so it has a friend (several behaviors depend on that).
    private func addFriend() async throws {
        let peer = makeTestE2eeManager()
        _ = try await service.e2eeManager.processScannedQr(qr: try await peer.createInvite(suggestedName: "Peer"), bobSuggestedName: "Me")
        service.repo.friends = try await service.e2eeManager.listFriends()
    }

    // MARK: - Invite replay

    /// A friend already paired from this invite (e.g. before an upgrade) blocks the same link.
    func testProcessQrUrl_AlreadyAcceptedInvite_IsRejectedWithAlert() async throws {
        let alice = makeTestE2eeManager()
        let qr = try await alice.createInvite(suggestedName: "Alice")
        _ = try await service.e2eeManager.processScannedQr(qr: qr, bobSuggestedName: "Bob")
        service.repo.friends = try await service.e2eeManager.listFriends()

        XCTAssertFalse(service.processQrUrl(qr.toUrl()))
        XCTAssertNil(service.repo.pendingQrForNaming, "no naming dialog for an already-accepted invite")
        XCTAssertEqual(service.inviteError, MR.strings().invite_already_used.localized())
    }

    /// A new invite from the same person still opens the naming dialog.
    func testProcessQrUrl_NewInviteFromSamePerson_IsAccepted() async throws {
        let alice = makeTestE2eeManager()
        _ = try await service.e2eeManager.processScannedQr(qr: try await alice.createInvite(suggestedName: "Alice"), bobSuggestedName: "Bob")
        service.repo.friends = try await service.e2eeManager.listFriends()

        let second = try await alice.createInvite(suggestedName: "Alice")
        XCTAssertTrue(service.processQrUrl(second.toUrl()))
        XCTAssertNotNil(service.repo.pendingQrForNaming)
        XCTAssertNil(service.inviteError)
    }

    /// Caught at confirm time (friend list not loaded when the link was opened): alert, no second friend.
    func testConfirmQrScan_AlreadyAcceptedInvite_ShowsAlertAndDoesNotPair() async throws {
        let alice = makeTestE2eeManager()
        let qr = try await alice.createInvite(suggestedName: "Alice")
        _ = try await service.e2eeManager.processScannedQr(qr: qr, bobSuggestedName: "Bob")

        await service.confirmQrScan(qr: qr, friendName: "Alice")
        XCTAssertEqual(service.inviteError, MR.strings().invite_already_used.localized())
        let friends = try await service.e2eeManager.listFriends()
        XCTAssertEqual(friends.count, 1)
    }

    /// Invite errors stay until dismissed; a successful poll doesn't clear them.
    func testInviteError_SurvivesStatusResetUntilDismissed() {
        XCTAssertFalse(service.processQrUrl("not a where invite"))
        XCTAssertEqual(service.inviteError, MR.strings().invalid_qr_code.localized())
        service.connectionStatus = Shared.ConnectionStatus.Ok()
        XCTAssertNotNil(service.inviteError)
        service.dismissInviteError()
        XCTAssertNil(service.inviteError)
    }

    // MARK: - Stop ordering
    //
    // LocationClient drops a Location whose stamp predates a stop to that friend. That is only
    // right if the state a send reads already shows the pause by the time the stop is issued, so
    // each stop path must flip the state first.

    func testStopSharing_SharingIsOffBeforeTheStopGoesOut() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        let svc: LocationSyncService = service
        svc.isSharingLocation = true

        let stopped = XCTestExpectation(description: "stop sent")
        var sharingAtStop: Bool?
        mockClient.onStop = {
            sharingAtStop = svc.isSharingLocation
            stopped.fulfill()
        }
        svc.stopSharing()
        await fulfillment(of: [stopped], timeout: 2.0)
        XCTAssertEqual(sharingAtStop, false)
    }

    func testTogglePauseFriend_FriendIsPausedBeforeTheStopGoesOut() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        let svc: LocationSyncService = service
        svc.isSharingLocation = true

        let stopped = XCTestExpectation(description: "stop sent")
        var pausedAtStop: Bool?
        mockClient.onStop = {
            pausedAtStop = svc.effectivelyPausedIds().contains("friend-1")
            stopped.fulfill()
        }
        svc.togglePauseFriend(id: "friend-1")
        await fulfillment(of: [stopped], timeout: 2.0)
        XCTAssertEqual(pausedAtStop, true)
    }
}
