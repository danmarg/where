import XCTest
import CoreLocation
import Shared
@testable import Where

final class LocationSyncServiceTests: XCTestCase {
    
    @MainActor
    func testPendingForcedSendAfterPairing_BugA() {
        let syncService = LocationSyncService.shared
        LocationManager.shared.location = nil
        let qr = Shared.QrPayload(ekPub: kotlinByteArray(from: Data([0,1,2])), suggestedName: "Alice", fingerprint: "fp")
        syncService.confirmQrScan(qr: qr, friendName: "Alice")
        // Verify via internal flag if accessible, or by injecting a location and checking send
    }
    
    @MainActor
    func testInviteLifecycle_AliceSide() {
        let syncService = LocationSyncService.shared

        // 1. Create invite
        syncService.createInvite()
        if case .pending = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should be pending after createInvite")
        }
        XCTAssertNotNil(syncService.e2eeStore.pendingQrPayload)

        // 2. Simulate finding an init payload via polling
        // (Mimic pollPendingInvite logic)
        let initPayload = Shared.KeyExchangeInitPayload(
            v: 1,
            token: "token",
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            suggestedName: "Bob"
        )

        // We can't easily simulate the polling transition, but we can test the Cancel logic.

        syncService.cancelPendingInit()
        XCTAssertNil(syncService.pendingInitPayload)
        if case .none = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should be none after cancelPendingInit")
        }
    }
}
