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
        self.mockStorage = MockRawKeyValueStorage()
        self.mockLocationProvider = MockLocationProvider()
        // Use :memory: to create a fresh in-memory database for each test
        let e2eeManager = Shared.E2eeManager(sqlDriver: Shared.IosSqlDriverKt.createIosSqlDriver(name: ":memory:"))
        let userStore = Shared.UserStore(storage: mockStorage)
        self.service = LocationSyncService(e2eeManager: e2eeManager, userStore: userStore, locationClient: nil, locationProvider: mockLocationProvider)
        self.service.skipUpdateVisibleUsers = true
        self.service.beginBackgroundTask = { _, _ in .invalid }
        self.service.endBackgroundTask = { _ in }
        // Kill the background poll timer and suppress network-restore Tasks mid-test
        self.service.pollTimer?.invalidate()
        self.service.skipNetworkRestore = true
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
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true

        // Initial send. Reset state to ensure clean start for throttle test.
        service.forceNextLocationUpdate = false
        service.lastSentTime = Date(timeIntervalSince1970: 0)
        service.sendLocation(lat: lat, lng: lng)
        await fulfillment(of: [expectation1], timeout: 2.0)

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
        var sendLocationToFriendCallback: (@Sendable (String) -> Void)?
        private var _pollCallCount = 0
        var pollResult: [Shared.UserLocation] = []
        var pollCallCount: Int {
            return _pollCallCount
        }
        func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallback?()
        }
        func sendLocationToFriend(friendId: String, lat: Double, lng: Double) async throws {
            sendLocationToFriendCallback?(friendId)
            sendLocationCallback?()
        }
        func poll(isForeground: Bool, pausedFriendIds: Set<String>) async throws -> [Shared.UserLocation] {
            _pollCallCount += 1
            return pollResult
        }
        var pendingInviteResults: [Shared.PendingInviteResult] = []
        func pollPendingInvites() async throws -> [Shared.PendingInviteResult] {
            return pendingInviteResults
        }
        func postKeyExchangeInit(friendId: String, qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws {
            // No-op
        }
        func syncNow() async throws {}
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

        await service.confirmPendingInit(payload: Shared.KeyExchangeInitPayload(v: 1, token: "t", ekPub: kotlinByteArray(from: Data([1])), keyConfirmation: kotlinByteArray(from: Data([2])), encryptedName: kotlinByteArray(from: Data()), suggestedName: "n", msgId: "m1"), name: "n")

        await service.pollAll(updateUi: true)

        let isRapidAfterPartial = service.isRapidPolling()
        XCTAssertTrue(isRapidAfterPartial)

        let addedFriendId = service.friends.first?.id ?? ""
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
        service.lastSentTime = Date(timeIntervalSinceNow: -400) // > 300s ago — heartbeat due

        await service.pollAll(updateUi: false)

        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, "Should call requestImmediateLocation when heartbeat is due")
    }

    func testPollAllDoesNotTriggerHeartbeatWhenRecentlySent() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSinceNow: -10) // just 10s ago — no heartbeat due

        await service.pollAll(updateUi: false)

        XCTAssertFalse(mockLocationProvider.requestImmediateLocationCalled, "Should NOT call requestImmediateLocation when heartbeat is NOT due")
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
        service.skipNetworkRestore = true
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

    func testSendLocationOnBackground_UsesLastSentLocationWhenGpsUnavailable() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
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
        service.lastSentTime = Date(timeIntervalSinceNow: -60)
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
        // lastSentTime > 300s ago so the pollAll heartbeat would also try to send.
        service.lastSentTime = Date(timeIntervalSinceNow: -400)
        mockLocationProvider.location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            altitude: 0, horizontalAccuracy: 50, verticalAccuracy: 50, timestamp: Date()
        )

        let sendCount = SendCountBox()
        mockClient.sendLocationCallback = { sendCount.increment() }

        service.onForegroundEntry()
        try await Task.sleep(nanoseconds: 300_000_000)

        XCTAssertEqual(sendCount.getCount(), 1,
            "sendLocation() updates lastSentTime synchronously, so pollAll's heartbeat must not fire a second send")
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
        try await Task.sleep(nanoseconds: 200_000_000)

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

    func testUiStateSynchronization_DismissalFlows() async throws {
        let qr = try await service.e2eeManager.createInvite(suggestedName: "Alice")

        // Test 1: Scanning a QR code dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.inviteState = Shared.InviteState.Pending(qr: qr)

        let qrUrl = qr.toUrl()
        service.processQrUrl(qrUrl)

        XCTAssertFalse(service.isInviteSheetShowing, "processQrUrl should dismiss the invite sheet")
        XCTAssertTrue(service.inviteState is Shared.InviteState.None, "processQrUrl should reset inviteState")

        // Test 2: Confirming a QR scan dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.inviteState = Shared.InviteState.Pending(qr: qr)

        await service.confirmQrScan(qr: qr, friendName: "Alice")

        XCTAssertFalse(service.isInviteSheetShowing, "confirmQrScan should dismiss the invite sheet")
        XCTAssertTrue(service.inviteState is Shared.InviteState.None, "confirmQrScan should reset inviteState")

        // Test 3: Canceling a QR scan dismisses the invite sheet
        service.isInviteSheetShowing = true
        service.inviteState = Shared.InviteState.Pending(qr: qr)

        await service.clearInvite()

        XCTAssertFalse(service.isInviteSheetShowing, "clearInvite should dismiss the invite sheet")
        XCTAssertTrue(service.inviteState is Shared.InviteState.None, "clearInvite should reset inviteState")
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
            token: "deadbeef",
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            encryptedName: kotlinByteArray(from: Data()),
            suggestedName: "Bob",
            msgId: "msg-001"
        )
        let pendingResult = Shared.PendingInviteResult(
            payload: fakePayload,
            scannerEkPub: kotlinByteArray(from: Data([1, 2, 3])),
            inviteEkPub: qr.ekPub,
            multipleScansDetected: false
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
        service.inviteState = Shared.InviteState.Pending(qr: qr)
        service.isInviteSheetShowing = true

        // 5. Foreground poll — this is the path that must dismiss the sheet.
        await service.pollAll(updateUi: true)

        // 6. Sheet must be dismissed and naming dialog must be offered.
        XCTAssertFalse(
            service.isInviteSheetShowing,
            "QR sheet must be dismissed when a scan arrives during pollAll(updateUi:true)"
        )
        XCTAssertNotNil(
            service.pendingInitPayload,
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
            token: "deadbeef",
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            encryptedName: kotlinByteArray(from: Data()),
            suggestedName: "Bob",
            msgId: "msg-002"
        )
        let pendingResult = Shared.PendingInviteResult(
            payload: fakePayload,
            scannerEkPub: kotlinByteArray(from: Data([1, 2, 3])),
            inviteEkPub: qr.ekPub,
            multipleScansDetected: false
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

        service.inviteState = Shared.InviteState.Pending(qr: qr)
        service.isInviteSheetShowing = true

        // Background wakeup path: updateUi is false, but isInviteSheetShowing is true.
        await service.pollAll(updateUi: false)

        XCTAssertFalse(
            service.isInviteSheetShowing,
            "QR sheet must be dismissed even when pollAll(updateUi:false) if isInviteSheetShowing is true"
        )
        XCTAssertNotNil(
            service.pendingInitPayload,
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
        service.pendingForcedSendAfterPairing = true
        // Throttle must not block: set lastSentTime far in the past.
        service.lastSentTime = Date(timeIntervalSinceNow: -60)

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

        service.sendLocation(lat: 37.0, lng: -122.0)
        await fulfillment(of: [expectation], timeout: 1.0)

        XCTAssertEqual(capturedFriendIdBox.get(), expectedFriendId,
            "sendLocation must fire sendLocationToFriend for the pending friend ID set during pairing")
        XCTAssertNil(service.pendingForcedSendFriendId,
            "pendingForcedSendFriendId must be cleared after the forced send fires")
    }

    // MARK: - handleNetworkRestored

    func testNetworkRestore_FreshLocation_SendsImmediately() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.lastSentTime = Date(timeIntervalSince1970: 0)

        // Fresh location (less than 60s old)
        mockLocationProvider.location = CLLocation(latitude: 37.0, longitude: -122.0)

        let expectation = XCTestExpectation(description: "Sends location on network restore with fresh fix")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.handleNetworkRestored()

        await fulfillment(of: [expectation], timeout: 1.0)
        XCTAssertFalse(service.forceNextLocationUpdate, "forceNextLocationUpdate should not be set when fix is fresh")
    }

    func testNetworkRestore_StaleLocation_RequestsFreshFix() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.locationFixTimeout = 60.0  // prevent timeout from firing during this test

        // Stale location (90s old)
        let staleLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10,
            timestamp: Date(timeIntervalSinceNow: -90)
        )
        mockLocationProvider.location = staleLocation

        let notSent = XCTestExpectation(description: "Should not send immediately on stale fix")
        notSent.isInverted = true
        mockClient.sendLocationCallback = { notSent.fulfill() }

        await service.handleNetworkRestored()
        await fulfillment(of: [notSent], timeout: 0.2)

        XCTAssertTrue(service.forceNextLocationUpdate, "Should arm forceNextLocationUpdate so the next GPS fix bypasses throttle")
        XCTAssertTrue(mockLocationProvider.requestImmediateLocationCalled, "Should request a fresh GPS fix")
    }

    func testNetworkRestore_StaleLocation_TimeoutFallback() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.locationFixTimeout = 0.05  // short timeout so test doesn't take 10s
        service.lastSentTime = Date(timeIntervalSince1970: 0)

        // Stale location that will be used as the fallback
        let staleLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10,
            timestamp: Date(timeIntervalSinceNow: -90)
        )
        mockLocationProvider.location = staleLocation

        let expectation = XCTestExpectation(description: "Fallback sends stale location after GPS timeout")
        mockClient.sendLocationCallback = { expectation.fulfill() }

        await service.handleNetworkRestored()
        await fulfillment(of: [expectation], timeout: 1.0)

        XCTAssertFalse(service.forceNextLocationUpdate, "forceNextLocationUpdate should be cleared after fallback send")
    }

    func testNetworkRestore_RapidFlap_OnlyOneSend() async throws {
        let mockClient = MockLocationClient()
        service = LocationSyncService(e2eeManager: service.e2eeManager, userStore: service.userStore, locationClient: mockClient, locationProvider: mockLocationProvider)
        service.skipNetworkRestore = true
        service.skipNetworkRestore = true
        service.isSharingLocation = true
        service.locationFixTimeout = 0.1
        service.lastSentTime = Date(timeIntervalSince1970: 0)

        let staleLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0),
            altitude: 0, horizontalAccuracy: 10, verticalAccuracy: 10,
            timestamp: Date(timeIntervalSinceNow: -90)
        )
        mockLocationProvider.location = staleLocation

        let sendCount = SendCountBox()
        let firstSend = XCTestExpectation(description: "First send from timeout fallback")
        mockClient.sendLocationCallback = {
            sendCount.increment()
            if sendCount.getCount() == 1 { firstSend.fulfill() }
        }

        // Simulate rapid network flap: first restore arms a timeout, second cancels it and arms a fresh one
        await service.handleNetworkRestored()
        await service.handleNetworkRestored()

        // Wait until the timeout fallback fires (up to 2s), then check for duplicates
        await fulfillment(of: [firstSend], timeout: 2.0)
        try await Task.sleep(nanoseconds: 100_000_000)

        XCTAssertEqual(sendCount.getCount(), 1, "Rapid network flap should not produce duplicate sends")
    }
}
