import XCTest
import Shared
import Combine
@testable import Where

// MARK: - Mock Doubles

/**
 * MockLocationClient records all calls and allows test control of responses.
 * Conforms to LocationClient's public interface (poll, sendLocation, sendLocationToFriend, postOpkBundle).
 */
class MockLocationClient {
    var pollCalls: [(token: String)] = []
    var sendLocationCalls: [(lat: Double, lng: Double, pausedFriendIds: Set<String>)] = []
    var sendLocationToFriendCalls: [(friendId: String, lat: Double, lng: Double)] = []
    var postOpkBundleCalls: [(friendId: String)] = []

    var pollError: Error?
    var sendLocationError: Error?
    var sendLocationToFriendError: Error?
    var postOpkBundleError: Error?

    var pollReturns: [Shared.UserLocation] = []
}

/**
 * MockE2eeStore allows test control of E2EE operations.
 * Records calls to key methods used in pairing and polling flows.
 */
class MockE2eeStore {
    var listFriendsReturns: [Shared.FriendEntry] = []
    private(set) var pendingQrPayloadValue: Shared.QrPayload? = nil
    var createInviteCalls: [(suggestedName: String)] = []
    private(set) var clearInviteCallCount: Int = 0
    var processScannedQrCalls: [(qr: Shared.QrPayload, bobSuggestedName: String)] = []
    var processKeyExchangeInitCalls: [(payload: Shared.KeyExchangeInitPayload)] = []

    var pendingQrPayload: Shared.QrPayload? {
        get { pendingQrPayloadValue }
        set { pendingQrPayloadValue = newValue }
    }

    func listFriends() -> [Shared.FriendEntry] {
        return listFriendsReturns
    }

    func createInvite(suggestedName: String) -> Shared.QrPayload {
        createInviteCalls.append((suggestedName: suggestedName))
        let qr = Shared.QrPayload(
            ekPub: kotlinByteArray(from: Data([1, 2, 3])),
            suggestedName: suggestedName,
            fingerprint: "mock_fp_\(suggestedName)"
        )
        pendingQrPayloadValue = qr
        return qr
    }

    func clearInvite() {
        clearInviteCallCount += 1
        pendingQrPayloadValue = nil
    }
}

// MARK: - Foundation Tests

@MainActor
final class LocationSyncServiceFoundationTests: XCTestCase {

    var syncService: LocationSyncService!
    var mockStore: MockE2eeStore!

    @MainActor
    override func setUp() {
        super.setUp()

        // Create mock doubles
        mockStore = MockE2eeStore()

        // Initialize LocationSyncService with mock store
        syncService = LocationSyncService(e2eeStore: mockStore)

        // Set display name for invite tests
        syncService.displayName = "Alice"
    }

    override func tearDown() {
        syncService = nil
        mockStore = nil
        super.tearDown()
    }

    // MARK: - InviteState Transitions

    @MainActor
    func testInviteState_InitialState() {
        // Verify initial state is .none
        if case .none = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should start as .none")
        }
    }

    @MainActor
    func testInviteState_PendingAfterCreateInvite() {
        // Call createInvite
        syncService.createInvite()

        // Verify state transitions to .pending with the QR payload
        if case .pending(let qr) = syncService.inviteState {
            XCTAssertEqual(qr.suggestedName, "Alice")
            XCTAssertEqual(qr.fingerprint, "mock_fp_Alice")
        } else {
            XCTFail("inviteState should be .pending after createInvite")
        }
    }

    @MainActor
    func testInviteState_ConsumedAfterPoll() {
        // 1. Create invite
        syncService.createInvite()
        if case .pending = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should be .pending after createInvite")
        }

        let qrBeforeConsume: Shared.QrPayload
        if case .pending(let qr) = syncService.inviteState {
            qrBeforeConsume = qr
        } else {
            XCTFail("Could not extract QR from pending state")
            return
        }

        // 2. Simulate poll consuming the invite by marking it as consumed
        syncService.inviteState = .consumed(qrBeforeConsume)

        // Verify state is now .consumed
        if case .consumed(let qr) = syncService.inviteState {
            XCTAssertEqual(qr.suggestedName, qrBeforeConsume.suggestedName)
        } else {
            XCTFail("inviteState should be .consumed after poll processes init payload")
        }
    }

    @MainActor
    func testAutoClearedInvite_ClearAfterConsumed() {
        // 1. Create invite
        syncService.createInvite()

        // Capture QR payload
        let qrPayload: Shared.QrPayload
        if case .pending(let qr) = syncService.inviteState {
            qrPayload = qr
        } else {
            XCTFail("Could not extract QR")
            return
        }

        // 2. Simulate poll consuming the invite (transitions to .consumed)
        syncService.inviteState = .consumed(qrPayload)

        // Verify mockStore call count before clearInvite
        let clearCountBeforeClearInvite = mockStore.clearInviteCallCount

        // 3. Call clearInvite on the service
        syncService.clearInvite()

        // 4. CRITICAL: Verify that clearInvite() does NOT call through to store
        // when the invite is already .consumed. The store was already cleared during the poll.
        let clearCountAfterClearInvite = mockStore.clearInviteCallCount

        // The difference should be 0 — store.clearInvite() was not called
        XCTAssertEqual(
            clearCountAfterClearInvite - clearCountBeforeClearInvite,
            0,
            "clearInvite() should not call store.clearInvite() when invite is already .consumed"
        )

        // Verify inviteState transitions to .none
        if case .none = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should transition to .none after clearInvite()")
        }
    }

    @MainActor
    func testAutoClearedInvite_StoreCallOnlyWhenPending() {
        // 1. Create invite
        let clearCountAfterInit = mockStore.clearInviteCallCount

        syncService.createInvite()

        // 2. Call clearInvite while still in .pending state
        syncService.clearInvite()

        // 3. Verify store.clearInvite() WAS called this time
        XCTAssertEqual(
            mockStore.clearInviteCallCount - clearCountAfterInit,
            1,
            "clearInvite() should call store.clearInvite() when invite is in .pending state"
        )

        // Verify inviteState is .none
        if case .none = syncService.inviteState {
            XCTAssert(true)
        } else {
            XCTFail("inviteState should be .none after clearInvite()")
        }
    }
}
