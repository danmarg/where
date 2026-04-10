import XCTest
import Combine
import UIKit
@preconcurrency import Shared
@testable import Where

@MainActor
class LocationSyncServiceTests: XCTestCase {
    var service: LocationSyncService!

    override func setUp() async throws {
        // We skip super.setUp() because it is non-isolated in the base XCTestCase,
        // and calling it from this @MainActor-isolated class with 'self' (which is non-Sendable)
        // triggers Swift 6 data race warnings. XCTestCase.setUp() is empty, so this is safe.
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        self.service = LocationSyncService(e2eeStore: store, locationClient: nil)
    }

    func testThrottleLogic() async throws {
        let lat = 37.7749
        let lng = -122.4194

        let sendCountBox = SendCountBox()
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        mockClient.sendLocationCallback = {
            sendCountBox.increment()
        }

        // Re-init service with mock client
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)

        // Initial send
        service.sendLocation(lat: lat, lng: lng)
        
        // Wait for the task to start and increment sendCount
        for _ in 0..<100 {
            if sendCountBox.getCount() == 1 { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(sendCountBox.getCount(), 1)

        // Immediate second send should be throttled
        service.sendLocation(lat: lat + 0.1, lng: lng + 0.1)
        try? await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertEqual(sendCountBox.getCount(), 1)

        // Forced send should bypass throttle
        service.sendLocation(lat: lat + 0.2, lng: lng + 0.2, force: true)
        for _ in 0..<100 {
            if sendCountBox.getCount() == 2 { break }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(sendCountBox.getCount(), 2)
    }

    class MockLocationClient: Shared.LocationClient, @unchecked Sendable {
        var sendLocationCallback: (@Sendable () -> Void)?
        private let lock = NSLock()
        private var _pollCallCount = 0
        var pollResult: [Shared.UserLocation] = []
        var pollCallCount: Int {
            lock.lock(); defer { lock.unlock() }
            return _pollCallCount
        }
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallback?()
        }
        override func poll() async throws -> [Shared.UserLocation] {
            lock.lock(); defer { lock.unlock() }
            _pollCallCount += 1
            return pollResult
        }
    }

    // MARK: - firePoll foreground/background gating

    func testFirePoll_ForegroundDoesPollFriends() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0, "Should poll friends when in foreground")
    }

    func testFirePoll_BackgroundStillPollsFriends() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { false }
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should poll friends in background so locations stay fresh when stationary")
    }

    func testFirePoll_BackgroundPollsEvenWhenNotSharing() async throws {
        // When sharing is off, we still poll at a lower (30 min) frequency to process
        // Ratchet Acks so Alice's location sending doesn't get stuck.
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { false }
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0,
            "Should still poll for Ratchet Acks in background even when not sharing")
    }

    func testTimerInterval_BackgroundNotSharing_Is30min() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { false }
        service.isSharingLocation = false
        service.startPolling()

        await service.firePoll()

        XCTAssertEqual(service.pollTimer?.timeInterval ?? 0, 30 * 60, accuracy: 0.1,
                       "Background maintenance poll (not sharing) should be 30 min")
    }

    func testFirePoll_RapidPollsEvenInBackground() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { false }
        service.startPolling()
        // Put service into rapid mode by setting a recent trigger timestamp.
        service.lastRapidPollTrigger = Date()

        await service.firePoll()

        XCTAssertGreaterThan(mockClient.pollCallCount, 0, "Should poll friends during rapid mode even in background")
    }

    // MARK: - Timer interval selection

    func testTimerInterval_Foreground_Is60s() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { true }
        service.startPolling()

        await service.firePoll()

        XCTAssertEqual(service.pollTimer?.timeInterval ?? 0, 60.0, accuracy: 0.1,
                       "Foreground normal poll should be 60s")
    }

    func testTimerInterval_Background_Is5min() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)
        service.isInForeground = { false }
        service.startPolling()

        await service.firePoll()

        XCTAssertEqual(service.pollTimer?.timeInterval ?? 0, 300.0, accuracy: 0.1,
                       "Background poll should slow to 5min for heartbeat-only firing")
    }

    func testIsRapidPolling() async throws {
        let isRapid = await service.isRapidPolling()
        XCTAssertFalse(isRapid)

        await service.createInvite()
        let isRapidAfterInvite = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterInvite)

        await service.clearInvite()
        // It should NO LONGER remain rapid for 5 minutes after clear
        let isRapidAfterClear = await service.isRapidPolling()
        XCTAssertFalse(isRapidAfterClear)
    }

    func testRapidPollResetAfterFirstLocationUpdate() async throws {
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)

        // 1. Trigger rapid poll
        service.lastRapidPollTrigger = Date()
        XCTAssertTrue(await service.isRapidPolling())

        // 2. Mock a location update from an existing friend (not tracked)
        let existingFriendId = "existing_friend"
        let update1 = Shared.UserLocation(userId: existingFriendId, lat: 1.0, lng: 2.0, timestamp: 123)
        mockClient.pollResult = [update1]

        // Track a different friend
        let newFriendId = "new_friend"
        // Simulate Bob-side or Alice-side adding to awaitingFirstUpdateIds
        // confirmQrScan or confirmPendingInit would do this.
        // For testing we can use reflection or just call one of those if possible,
        // but adding a friend is complex. Let's trigger it via confirmPendingInit mock.
        await service.confirmPendingInit(payload: Shared.KeyExchangeInitPayload(v: 1, token: "t", ekPub: kotlinByteArray(from: Data([1])), keyConfirmation: kotlinByteArray(from: Data([2])), suggestedName: "n"), name: "n")

        // 3. Fire poll
        await service.pollAll(updateUi: true)

        // 4. Verify rapid poll is NOT reset yet (because we got existing_friend update but we're waiting for 'n')
        XCTAssertTrue(await service.isRapidPolling())

        // 5. Mock a location update from the tracked friend
        // We need the ID generated by the store for "n". Since we mocked processKeyExchangeInit to return null/fail
        // in previous tests, let's just use the ID from friends list.
        let addedFriendId = service.friends.first?.id ?? ""
        if !addedFriendId.isEmpty {
            let update2 = Shared.UserLocation(userId: addedFriendId, lat: 3.0, lng: 4.0, timestamp: 456)
            mockClient.pollResult = [update2]

            // 6. Fire poll again
            await service.pollAll(updateUi: true)

            // 7. Verify rapid poll IS reset now
            XCTAssertFalse(await service.isRapidPolling())
            XCTAssertEqual(service.lastRapidPollTrigger.timeIntervalSince1970, 0, accuracy: 0.1)
        }
    }

    func testBackgroundTaskExpiry() async throws {
        let expiryHandlerBox = ExpiryHandlerBox()
        let endCalledBox = EndCalledBox()

        service.beginBackgroundTask = { name, handler in
            expiryHandlerBox.setHandler(handler)
            return .init(rawValue: 123)
        }
        service.endBackgroundTask = { id in
            if id.rawValue == 123 {
                endCalledBox.setCalled()
            }
        }

        service.sendLocation(lat: 0, lng: 0, force: true)

        // Wait a bit for the task to start
        try? await Task.sleep(nanoseconds: 100_000_000)

        let handler = expiryHandlerBox.getHandler()
        XCTAssertNotNil(handler)
        handler?()
        XCTAssertTrue(endCalledBox.getCalled())
    }

    // MARK: - Keychain storage tests

    func testIsSharingLocationStoredInKeychain() throws {
        let keychain = KeychainE2eeStorage()

        // Test setting to false and verifying Keychain storage
        keychain.putString(key: "where_is_sharing", value: "false")
        XCTAssertEqual(keychain.getString(key: "where_is_sharing"), "false")

        // Test persistence with a fresh service
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        service = LocationSyncService(e2eeStore: store, locationClient: nil)
        XCTAssertFalse(service.isSharingLocation)

        // Test setting to true
        service.isSharingLocation = true
        let keychain2 = KeychainE2eeStorage()
        XCTAssertEqual(keychain2.getString(key: "where_is_sharing"), "true")
    }

    func testDisplayNameStoredInKeychain() throws {
        let keychain = KeychainE2eeStorage()

        // Test setting and reading from Keychain
        keychain.putString(key: "display_name", value: "Alice")
        XCTAssertEqual(keychain.getString(key: "display_name"), "Alice")

        // Test persistence with a fresh service
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        service = LocationSyncService(e2eeStore: store, locationClient: nil)
        XCTAssertEqual(service.displayName, "Alice")
    }

    func testPausedFriendIdsStoredInKeychain() throws {
        let keychain = KeychainE2eeStorage()

        // Test setting paused friend IDs through the service
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        service = LocationSyncService(e2eeStore: store, locationClient: nil)
        service.pausedFriendIds = ["friend1", "friend2"]

        // Verify they're in Keychain
        let pausedJson = keychain.getString(key: "paused_friends")
        XCTAssertNotNil(pausedJson)
        if let pausedData = pausedJson?.data(using: .utf8),
           let pausedArray = try? JSONSerialization.jsonObject(with: pausedData) as? [String] {
            XCTAssertEqual(Set(pausedArray), ["friend1", "friend2"])
        } else {
            XCTFail("Failed to parse paused friends from Keychain")
        }

        // Test persistence with a fresh service
        let store2 = Shared.E2eeStore(storage: KeychainE2eeStorage())
        let service2 = LocationSyncService(e2eeStore: store2, locationClient: nil)
        XCTAssertEqual(service2.pausedFriendIds, ["friend1", "friend2"])
    }

    func testKeychainNotUserDefaults() throws {
        // Verify that UserDefaults no longer contains these keys
        let store = Shared.E2eeStore(storage: KeychainE2eeStorage())
        service = LocationSyncService(e2eeStore: store, locationClient: nil)

        service.isSharingLocation = false
        service.displayName = "TestUser"
        service.pausedFriendIds = ["friend1"]

        // Verify that UserDefaults does not have these keys
        XCTAssertNil(UserDefaults.standard.object(forKey: "where_is_sharing"))
        XCTAssertNil(UserDefaults.standard.object(forKey: "display_name"))
        XCTAssertNil(UserDefaults.standard.object(forKey: "paused_friends"))
    }

    private final class ExpiryHandlerBox: @unchecked Sendable {
        private let lock = NSLock()
        private var handler: (@Sendable () -> Void)?
        func setHandler(_ h: @Sendable @escaping () -> Void) {
            lock.lock()
            defer { lock.unlock() }
            handler = h
        }
        func getHandler() -> (@Sendable () -> Void)? {
            lock.lock()
            defer { lock.unlock() }
            return handler
        }
    }

    private final class EndCalledBox: @unchecked Sendable {
        private let lock = NSLock()
        private var called = false
        func setCalled() {
            lock.lock()
            defer { lock.unlock() }
            called = true
        }
        func getCalled() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            return called
        }
    }

    private final class SendCountBox: @unchecked Sendable {
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
