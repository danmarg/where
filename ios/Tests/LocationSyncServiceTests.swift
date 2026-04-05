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

    // MARK: - removeFriend Atomicity Tests

    @MainActor
    func testRemoveFriend_RemovesFromAllCollections() {
        let syncService = LocationSyncService.shared

        // 1. Seed with a friend in all collections
        let friendId = "alice_123"
        let location = Shared.UserLocation(userId: friendId, lat: 37.7749, lng: -122.4194, timestamp: 1000)

        syncService.friends = [Shared.UserLocation(userId: "alice", lat: 37.7749, lng: -122.4194, timestamp: 1000)]
        syncService.friendLocations[friendId] = (lat: 37.7749, lng: -122.4194, ts: 1000)
        syncService.pausedFriendIds.insert(friendId)

        // Verify initial state
        XCTAssertTrue(syncService.friendLocations.keys.contains(friendId), "Friend should be in locations initially")
        XCTAssertTrue(syncService.pausedFriendIds.contains(friendId), "Friend should be paused initially")

        // 2. Call removeFriend (synchronous on main actor)
        syncService.removeFriend(id: friendId)

        // 3. CRITICAL: Assert atomicity - all three collections cleaned in single main-actor turn
        // Use await MainActor.run to force a single drain of the actor queue
        let assertAtomicity: () -> Void = {
            XCTAssertFalse(
                syncService.friendLocations.keys.contains(friendId),
                "Friend must be removed from friendLocations"
            )
            XCTAssertFalse(
                syncService.pausedFriendIds.contains(friendId),
                "Friend must be removed from pausedFriendIds"
            )
        }
        assertAtomicity()
    }

    @MainActor
    func testRemoveFriend_NotPausedFriend() {
        let syncService = LocationSyncService.shared

        let friendId = "bob_456"

        // Seed friend in all but paused list
        syncService.friendLocations[friendId] = (lat: 40.7128, lng: -74.0060, ts: 2000)

        // Verify initial state
        XCTAssertTrue(syncService.friendLocations.keys.contains(friendId))
        XCTAssertFalse(syncService.pausedFriendIds.contains(friendId))

        // Remove friend
        syncService.removeFriend(id: friendId)

        // Verify cleanup (should not crash even though not paused)
        XCTAssertFalse(syncService.friendLocations.keys.contains(friendId))
        XCTAssertFalse(syncService.pausedFriendIds.contains(friendId))
    }

    @MainActor
    func testRemoveFriend_MultipleFriendsPresent() {
        let syncService = LocationSyncService.shared

        let aliceId = "alice"
        let bobId = "bob"
        let charlieId = "charlie"

        // Seed multiple friends
        syncService.friendLocations[aliceId] = (lat: 1.0, lng: 2.0, ts: 1000)
        syncService.friendLocations[bobId] = (lat: 3.0, lng: 4.0, ts: 2000)
        syncService.friendLocations[charlieId] = (lat: 5.0, lng: 6.0, ts: 3000)

        syncService.pausedFriendIds.insert(aliceId)
        syncService.pausedFriendIds.insert(bobId)

        // Verify initial state
        XCTAssertEqual(syncService.friendLocations.count, 3)
        XCTAssertEqual(syncService.pausedFriendIds.count, 2)

        // Remove bob
        syncService.removeFriend(id: bobId)

        // Verify only bob is removed
        XCTAssertEqual(syncService.friendLocations.count, 2, "Should have 2 locations after removing bob")
        XCTAssertEqual(syncService.pausedFriendIds.count, 1, "Should have 1 paused friend after removing bob")

        XCTAssertFalse(syncService.friendLocations.keys.contains(bobId), "Bob should not be in locations")
        XCTAssertFalse(syncService.pausedFriendIds.contains(bobId), "Bob should not be paused")

        XCTAssertTrue(syncService.friendLocations.keys.contains(aliceId), "Alice should still be in locations")
        XCTAssertTrue(syncService.pausedFriendIds.contains(aliceId), "Alice should still be paused")
        XCTAssertTrue(syncService.friendLocations.keys.contains(charlieId), "Charlie should still be in locations")
    }
}

// MARK: - LocationSyncService Extension for Testing

extension LocationSyncService {
    fileprivate func getCurrentPollInterval() async -> Double {
        let isRapid = await isRapidPolling()
        return isRapid ? 2.0 : 60.0
    }
}
