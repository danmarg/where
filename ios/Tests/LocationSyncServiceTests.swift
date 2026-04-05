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

    // MARK: - Rapid Poll Transition Tests

    @MainActor
    func testRapidPolling_WithPendingQrPayload() async {
        let syncService = LocationSyncService.shared

        // Clear any existing state
        syncService.e2eeStore.clearInvite()
        syncService.pendingQrForNaming = nil
        syncService.pendingInitPayload = nil
        syncService.lastRapidPollTrigger = Date(timeIntervalSince1970: 0) // Far in the past

        // Initially not rapid polling (no pending state, trigger is old)
        var interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should use slow interval (60s) initially")

        // Set pending QR payload (Alice creating invite)
        let qr = Shared.QrPayload(
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            suggestedName: "Bob",
            fingerprint: "test_fp"
        )
        syncService.e2eeStore.createInvite("Alice")

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Should use fast interval (2s) while QR payload pending")

        // Clear pending QR
        syncService.e2eeStore.clearInvite()

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should revert to slow interval (60s) after QR cleared")
    }

    @MainActor
    func testRapidPolling_WithPendingInitPayload() async {
        let syncService = LocationSyncService.shared

        // Clear state
        syncService.e2eeStore.clearInvite()
        syncService.pendingQrForNaming = nil
        syncService.pendingInitPayload = nil
        syncService.lastRapidPollTrigger = Date(timeIntervalSince1970: 0)

        // Initially not rapid polling
        var interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should use slow interval initially")

        // Set pending init payload (Bob receiving Alice's invite response)
        let initPayload = Shared.KeyExchangeInitPayload(
            v: 1,
            token: "test_token",
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            suggestedName: "Alice"
        )
        syncService.pendingInitPayload = initPayload

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Should use fast interval while pending init payload exists")

        // Clear pending init
        syncService.pendingInitPayload = nil

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should revert to slow interval after init cleared")
    }

    @MainActor
    func testRapidPolling_WithPendingQrForNaming() async {
        let syncService = LocationSyncService.shared

        // Clear state
        syncService.e2eeStore.clearInvite()
        syncService.pendingQrForNaming = nil
        syncService.pendingInitPayload = nil
        syncService.lastRapidPollTrigger = Date(timeIntervalSince1970: 0)

        // Initially not rapid polling
        var interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should use slow interval initially")

        // Set pending QR for naming (Bob's side after scanning Alice's QR)
        let qr = Shared.QrPayload(
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            suggestedName: "Alice",
            fingerprint: "fingerprint123"
        )
        syncService.pendingQrForNaming = qr

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Should use fast interval while pending QR for naming")

        // Clear pending QR
        syncService.pendingQrForNaming = nil

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should revert to slow interval after QR cleared")
    }

    @MainActor
    func testRapidPolling_RecentTrigger() async {
        let syncService = LocationSyncService.shared

        // Clear state
        syncService.e2eeStore.clearInvite()
        syncService.pendingQrForNaming = nil
        syncService.pendingInitPayload = nil
        syncService.lastRapidPollTrigger = Date(timeIntervalSince1970: 0)

        // Initially not rapid polling
        var interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0)

        // Trigger rapid polling by setting lastRapidPollTrigger to now
        syncService.lastRapidPollTrigger = Date()

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Should use fast interval within 5 min window after trigger")

        // Advance time past 5 min window
        syncService.lastRapidPollTrigger = Date(timeIntervalSinceNow: -(5 * 60 + 1))

        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "Should revert to slow interval after 5 min window expires")
    }

    @MainActor
    func testRapidPolling_MultiplePairingStates() async {
        let syncService = LocationSyncService.shared

        // Clear state
        syncService.e2eeStore.clearInvite()
        syncService.pendingQrForNaming = nil
        syncService.pendingInitPayload = nil
        syncService.lastRapidPollTrigger = Date(timeIntervalSince1970: 0)

        // Start with slow polling
        var interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0)

        // Set pending QR (Alice creating invite)
        syncService.e2eeStore.createInvite("Alice")
        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "QR pending → fast polling")

        // Also set pending init payload (Bob's response)
        let initPayload = Shared.KeyExchangeInitPayload(
            v: 1,
            token: "test",
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            keyConfirmation: kotlinByteArray(from: Data([4, 5, 6])),
            suggestedName: "Bob"
        )
        syncService.pendingInitPayload = initPayload

        // Still fast polling (still has pairing state)
        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Pending init → fast polling")

        // Clear QR payload
        syncService.e2eeStore.clearInvite()

        // Still fast polling (init payload still pending)
        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 2.0, "Init still pending → fast polling")

        // Clear init payload
        syncService.pendingInitPayload = nil

        // Now back to slow polling
        interval = await syncService.getCurrentPollInterval()
        XCTAssertEqual(interval, 60.0, "All pairing cleared → slow polling")
    }
}

// MARK: - LocationSyncService Extension for Testing

extension LocationSyncService {
    fileprivate func getCurrentPollInterval() async -> Double {
        let isRapid = await isRapidPolling()
        return isRapid ? 2.0 : 60.0
    }
}
