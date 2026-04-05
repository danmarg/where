import XCTest
import Combine
import UIKit
@testable import Where

class LocationSyncServiceTests: XCTestCase {
    var service: LocationSyncService!

    @MainActor
    override func setUp() {
        super.setUp()
        service = LocationSyncService(e2eeStore: nil, locationClient: nil)
    }

    @MainActor
    func testThrottleLogic() async {
        let lat = 37.7749
        let lng = -122.4194

        var sendCount = 0
        let store = Shared.E2eeStore(storage: UserDefaultsE2eeStorage())
        let mockClient = MockLocationClient(baseUrl: "", store: store)
        mockClient.sendLocationCallback = {
            sendCount += 1
        }

        // Re-init service with mock client
        service = LocationSyncService(e2eeStore: store, locationClient: mockClient)

        // Initial send
        service.sendLocation(lat: lat, lng: lng)
        XCTAssertEqual(sendCount, 1)

        // Immediate second send should be throttled
        service.sendLocation(lat: lat + 0.1, lng: lng + 0.1)
        XCTAssertEqual(sendCount, 1)

        // Forced send should bypass throttle
        service.sendLocation(lat: lat + 0.2, lng: lng + 0.2, force: true)
        XCTAssertEqual(sendCount, 2)
    }

    class MockLocationClient: Shared.LocationClient {
        var sendLocationCallback: (() -> Void)?
        override func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws {
            sendLocationCallback?()
        }
    }

    @MainActor
    func testIsRapidPolling() async {
        let isRapid = await service.isRapidPolling()
        XCTAssertFalse(isRapid)

        service.createInvite()
        let isRapidAfterInvite = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterInvite)

        service.clearInvite()
        // It remains rapid for 5 minutes due to the trigger
        let isRapidAfterClear = await service.isRapidPolling()
        XCTAssertTrue(isRapidAfterClear)
    }

    @MainActor
    func testBackgroundTaskExpiry() {
        var expiryHandler: (() -> Void)?
        var endCalled = false

        service.beginBackgroundTask = { name, handler in
            expiryHandler = handler
            return .init(rawValue: 123)
        }
        service.endBackgroundTask = { id in
            if id.rawValue == 123 {
                endCalled = true
            }
        }

        service.sendLocation(lat: 0, lng: 0, force: true)

        XCTAssertNotNil(expiryHandler)
        expiryHandler?()
        XCTAssertTrue(endCalled)
    }
}
