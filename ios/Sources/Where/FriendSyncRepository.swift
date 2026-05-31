import Foundation
@preconcurrency import Shared
import os

private let logger = Logger(subsystem: "net.af0.where", category: "FriendSync")

/// State store for friend-sync data: friends, pending invites, pairing-flow UI state,
/// mirrors of UserStore values. Peer of `LocationSyncService` — `LocationSyncService`
/// keeps GPS, sharing, polling, and the network/expiry timers, and drives mutations
/// against this repo via direct property access. Mirrors Android's `LocationRepository`.
///
/// All state is read-only from the UI's perspective; mutations flow through
/// `LocationSyncService`'s action methods.
@MainActor
final class FriendSyncRepository: ObservableObject {
    @Published var friends: [Shared.FriendEntry] = []
    @Published var pendingInvites: [Shared.PendingInviteView] = []
    @Published var diagnosticLog: [String] = []

    @Published var inviteState: Shared.InviteState = Shared.InviteState.None()
    @Published var pendingQrForNaming: Shared.QrPayload? = nil
    @Published var pendingInitPayload: Shared.KeyExchangeInitPayload? = nil
    @Published var pendingInitAliceEkPub: Data? = nil
    @Published var multipleScansDetected: Bool = false
    @Published var isExchanging: Bool = false

    /// Mirror of `UserStore.pausedFriendIds` — writes flow back through `didSet`.
    @Published var pausedFriendIds: Set<String> {
        didSet {
            userStore.setPausedFriends(ids: pausedFriendIds)
        }
    }

    /// Mirror of `UserStore.friendExpiresAt`. Mutated by `LocationSyncService.setFriendExpiry`
    /// which writes through to UserStore and reschedules the expiry timer.
    @Published var friendExpiresAt: [String: Int64]

    private let e2eeManager: Shared.E2eeManager
    private let userStore: Shared.UserStore

    init(e2eeManager: Shared.E2eeManager, userStore: Shared.UserStore) {
        self.e2eeManager = e2eeManager
        self.userStore = userStore
        self.pausedFriendIds = Set(userStore.pausedFriendIds.value as? Set<String> ?? [])
        self.friendExpiresAt = Self.decodeFriendExpiresAt(userStore.friendExpiresAt.value)
    }

    /// Initial hydration from persistent store. Called once during `LocationSyncService` init.
    func loadInitialState() async {
        do {
            try await e2eeManager.cleanupExpiredInvites(expirySeconds: 48 * 3600)
            self.friends = try await e2eeManager.listFriends()
            self.pendingInvites = try await e2eeManager.listPendingInvites()
        } catch {
            logger.error("Failed to load initial friends: \(error.localizedDescription)")
        }
    }

    /// Re-read friends, pending invites, and diagnostic log from the store. Called after
    /// any mutation that may have changed friend rows (poll batch, rename, pair, remove).
    func refreshFromStore() async throws {
        friends = try await e2eeManager.listFriends()
        diagnosticLog = e2eeManager.diagnosticLogSnapshot()
        pendingInvites = try await e2eeManager.listPendingInvites()
    }

    /// Source-of-truth set of friend ids that must not receive Location messages, combining
    /// the user's explicit pause list with any elapsed per-friend timer.
    func effectivelyPausedIds() -> Set<String> {
        let nowSec = Int64(Date().timeIntervalSince1970)
        let expired = friendExpiresAt.filter { nowSec >= $0.value }.map { $0.key }
        return expired.isEmpty ? pausedFriendIds : pausedFriendIds.union(expired)
    }

    private static func decodeFriendExpiresAt(_ raw: Any?) -> [String: Int64] {
        guard let dict = raw as? [String: Any] else { return [:] }
        var out: [String: Int64] = [:]
        for (k, v) in dict {
            if let kl = v as? Shared.KotlinLong {
                out[k] = kl.int64Value
            } else if let n = v as? Int64 {
                out[k] = n
            } else if let n = v as? Int {
                out[k] = Int64(n)
            }
        }
        return out
    }
}
