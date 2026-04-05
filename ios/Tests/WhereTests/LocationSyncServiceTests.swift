import XCTest
import Combine
import UIKit
@preconcurrency import Shared
@testable import Where

@MainActor
class LocationSyncServiceTests: XCTestCase {
    var service: LocationSyncService!

    override func setUp() {
        super.setUp()
        service = LocationSyncService(e2eeStore: nil, locationClient: nil)
    }

    @MainActor
    func testThrottleLogic() async {
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
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallback?()
        }
    }

    @MainActor
    func testIsRapidPolling() async {
        let isRapid = await service.isRapidPolling()
        XCTAssertFalse(isRapid)

        await service.createInvite()
        let isRapidAfterInvite = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterInvite)

        await service.clearInvite()
        // It remains rapid for 5 minutes due to the trigger
        let isRapidAfterClear = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterClear)
    }

    @MainActor
    func testBackgroundTaskExpiry() async {
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
