import Foundation
@preconcurrency import Shared
import os
import UIKit
import CoreLocation
import Combine
private let logger = Logger(subsystem: "net.af0.where", category: "LocationSync")

private class RawStringDesc: NSObject, Shared.ResourcesStringDesc {
    private let value: String
    init(_ value: String) { self.value = value }
    func localized() -> String { value }
}

@MainActor
protocol LocationClientProtocol: AnyObject, Sendable {
    func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>) async throws
    func sendLocationToFriend(friendId: String, lat: Double, lng: Double) async throws
    func poll(isForeground: Bool) async throws -> [Shared.UserLocation]
    func pollPendingInvite() async throws -> Shared.PendingInviteResult?
    func postKeyExchangeInit(qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws
}

extension Shared.LocationClient: LocationClientProtocol {}

@inline(__always)
private func debugLog(_ msg: () -> String) {
    #if DEBUG
    let message = msg()
    logger.debug("\(message)")
    #endif
}

@MainActor
final class LocationSyncService: ObservableObject {
    static let shared = LocationSyncService()

    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
    @Published var friendLastPing: [String: Date] = [:]
    @Published var connectionStatus: Shared.ConnectionStatus = Shared.ConnectionStatus.Ok()
    @Published var friends: [Shared.FriendEntry] = []
    @Published var inviteState: Shared.InviteState = Shared.InviteState.None()
    @Published var isSharingLocation: Bool {
        didSet {
            userStore.setSharing(sharing: isSharingLocation)
        }
    }
    @Published var displayName: String {
        didSet {
            userStore.setDisplayName(name: displayName)
            if isInviteActive {
                Task { await createInvite() }
            }
        }
    }
    @Published var pausedFriendIds: Set<String> {
        didSet {
            userStore.setPausedFriends(ids: pausedFriendIds)
        }
    }

    @Published var pendingQrForNaming: Shared.QrPayload? = nil
    @Published var pendingInitPayload: Shared.KeyExchangeInitPayload? = nil
    @Published var multipleScansDetected: Bool = false
    @Published var isExchanging: Bool = false
    private var inviteTask: Task<Void, Never>? = nil
    @Published var visibleUsers: [Shared.UserLocation] = []
    var isInviteActive: Bool { inviteState is Shared.InviteState.Pending }
    var skipUpdateVisibleUsers: Bool = false

    var lastRapidPollTrigger: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var lastPollTime: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var pollTimer: Timer?  // internal for testing
    private var isPollInFlight = false
    /// Overridable in tests to simulate foreground/background without UIKit.
    var isInForeground: () -> Bool = { UIApplication.shared.applicationState == .active }
    private static let rapidPollInterval: TimeInterval = 1.0
    private static let foregroundPollInterval: TimeInterval = 10.0
    private static let normalPollInterval: TimeInterval = 300.0 // 5 min (background sharing)
    private static let maintenancePollInterval: TimeInterval = 30 * 60  // ack-only when not sharing
    private var visibleUsersCancellables = Set<AnyCancellable>()

    private var lastSentLocation: (lat: Double, lng: Double)? = nil

    /// Best available location for heartbeat sends: live GPS first, then last sent.
    private var bestAvailableLocation: (lat: Double, lng: Double)? {
        if let loc = locationProvider.lastLocation {
            return (lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
        return lastSentLocation
    }
    var lastSentTime: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var pendingForcedSendAfterPairing: Bool = false
    private var currentSendTask: Task<Void, Never>? = nil
    private var awaitingFirstUpdateIds: Set<String> = []
    // Monotonically increasing counter used to prevent stale task cleanup from
    // clearing a newer task's state. Incremented each time a new send task is created.
    private var sendTaskGeneration: Int = 0

    // Injected for testing. Closures are @Sendable and use MainActor.assumeIsolated internally
    // to interact with UIApplication.shared, which is required for background tasks in Swift 6.
    var beginBackgroundTask: @Sendable (String, @Sendable @escaping () -> Void) -> UIBackgroundTaskIdentifier = { name, handler in
        MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: handler)
        }
    }
    var endBackgroundTask: @Sendable (UIBackgroundTaskIdentifier) -> Void = { identifier in
        MainActor.assumeIsolated {
            UIApplication.shared.endBackgroundTask(identifier)
        }
    }

    let e2eeStore: Shared.E2eeStore
    let userStore: Shared.UserStore
    let locationClient: LocationClientProtocol
    let locationProvider: LocationProviding

    init(e2eeStore: Shared.E2eeStore? = nil, userStore: Shared.UserStore? = nil, locationClient: LocationClientProtocol? = nil, locationProvider: LocationProviding? = nil) {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")

        let keychain = KeychainE2eeStorage()
        let store = e2eeStore ?? Shared.E2eeStore(storage: keychain)
        self.e2eeStore = store
        let userStoreValue = userStore ?? Shared.UserStore(storage: keychain)
        self.userStore = userStoreValue
        self.locationClient = locationClient ?? Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)
        self.locationProvider = locationProvider ?? LocationManager.shared

        self.isSharingLocation = (userStoreValue.isSharingLocation.value as? Shared.KotlinBoolean)?.boolValue ?? true
        self.displayName = userStoreValue.displayName.value as? String ?? ""
        self.pausedFriendIds = Set(userStoreValue.pausedFriendIds.value as? Set<String> ?? [])

        Task { @MainActor in
            do {
                self.friends = try await store.listFriends()
                var initialLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
                var initialLastPing: [String: Date] = [:]
                for friend in self.friends {
                    if let lat = friend.lastLat?.doubleValue, let lng = friend.lastLng?.doubleValue, let ts = friend.lastTs?.int64Value {
                        initialLocations[friend.id] = (lat: lat, lng: lng, ts: ts)
                        initialLastPing[friend.id] = Date(timeIntervalSince1970: TimeInterval(ts))
                    }
                }
                self.friendLocations = initialLocations
                self.friendLastPing = initialLastPing
                self.updateVisibleUsers()
            } catch {
                logger.error("Failed to load initial friends: \(error.localizedDescription)")
            }
        }

        // Subscribe to updates on friendLocations, isSharingLocation, and user location
        // to keep visibleUsers in sync.
        Publishers.CombineLatest3($friendLocations, $isSharingLocation, self.locationProvider.locationPublisher)
            .sink { [weak self] _, _, _ in
                self?.updateVisibleUsers()
            }
            .store(in: &visibleUsersCancellables)

        Task {
            // Clear any stale invite from a previous session before first poll.
            if (try? await store.pendingQrPayload()) ?? nil != nil {
                try? await store.clearInvite()
            }
            startPolling()
        }
    }

    func setDisplayName(name: String) {
        displayName = name
    }

    func toggleSharing() {
        isSharingLocation.toggle()
    }

    func togglePauseFriend(id: String) {
        if pausedFriendIds.contains(id) {
            pausedFriendIds.remove(id)
        } else {
            pausedFriendIds.insert(id)
        }
    }

    func createInvite() async {
        guard pendingInitPayload == nil && pendingQrForNaming == nil else {
            logger.debug("createInvite: pairing already in flight, skipping")
            return
        }
        do {
            let qr = try await e2eeStore.createInvite(suggestedName: displayName)
            inviteState = Shared.InviteState.Pending(qr: qr)
            triggerRapidPoll()
        } catch {
            logger.error("Failed to create invite: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    private func resetRapidPoll() {
        lastRapidPollTrigger = Date(timeIntervalSince1970: 0)
        awaitingFirstUpdateIds.removeAll()
    }

    func triggerRapidPoll() {
        lastRapidPollTrigger = Date()
        wakePoll()
    }

    func wakePoll() {
        if isPollInFlight { return }
        pollTimer?.fire()
    }

    func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in
                self.tick()
            }
        }
    }

    private func tick() {
        if isPollInFlight { return }

        let now = Date()
        let interval = targetPollInterval()

        if now.timeIntervalSince(lastPollTime) >= interval {
            Task {
                await pollAll()
            }
        }

        // Heartbeat: Send location periodically if sharing.
        // We use a 5-minute threshold for stationary updates, similar to Android.
        if isSharingLocation {
            let heartbeatInterval: TimeInterval = 300.0 // 5 minutes
            if now.timeIntervalSince(lastSentTime) >= heartbeatInterval {
                if let loc = bestAvailableLocation {
                    logger.info("tick: stationary heartbeat — sending location")
                    self.sendLocation(lat: loc.lat, lng: loc.lng)
                } else {
                    logger.info("tick: heartbeat due but no location available")
                }
            }
        }
    }

    func targetPollInterval() -> TimeInterval {
        if isRapidPolling() {
            return Self.rapidPollInterval
        }
        if isInForeground() {
            return Self.foregroundPollInterval
        }
        return isSharingLocation ? Self.normalPollInterval : Self.maintenancePollInterval
    }

    func isRapidPolling() -> Bool {
        if !awaitingFirstUpdateIds.isEmpty { return true }
        return Date().timeIntervalSince(lastRapidPollTrigger) < 60.0
    }

    func firePoll() async {
        await pollAll()
    }

    private func onFriendLocationReceived(friendId: String) {
        if awaitingFirstUpdateIds.remove(friendId) != nil {
            if awaitingFirstUpdateIds.isEmpty {
                resetRapidPoll()
            }
        }
    }

    func pollAll(updateUi: Bool = true) async {
        if isPollInFlight { return }
        isPollInFlight = true
        lastPollTime = Date()
        logger.debug("Polling for location updates (updateUi=\(updateUi))")
        let identifier = self.beginBackgroundTask("PollAll") {
            // Task expired
        }

        defer {
            isPollInFlight = false
            if identifier != .invalid {
                self.endBackgroundTask(identifier)
            }
        }
        do {
            let updates = try await locationClient.poll(isForeground: isInForeground())
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                try? await e2eeStore.updateLastLocation(id: update.userId, lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLastPing[update.userId] = Date(timeIntervalSince1970: TimeInterval(update.timestamp))
                onFriendLocationReceived(friendId: update.userId)
            }

            friends = try await e2eeStore.listFriends()
            // Always update visibleUsers to ensure map is fresh when returning to foreground.
            updateVisibleUsers()

            if updateUi {
                try await pollPendingInvite()
            }

            // Heartbeat: if we're awake enough to poll, also send location if one is due.
            // This covers wakeups that don't go through tick() (e.g. didUpdateLocations,
            // background-app-refresh, or any direct pollAll() call).
            let heartbeatInterval: TimeInterval = 300.0
            let elapsed = Date().timeIntervalSince(lastSentTime)
            let hasLocation = locationProvider.lastLocation != nil
            let sharing = isSharingLocation
            logger.info("pollAll: sharing=\(sharing) elapsed=\(Int(elapsed))s hasLocation=\(hasLocation)")
            if isSharingLocation {
                if elapsed >= heartbeatInterval {
                    if let loc = bestAvailableLocation {
                        logger.info("pollAll: heartbeat due — sending location")
                        sendLocation(lat: loc.lat, lng: loc.lng)
                    } else {
                        logger.info("pollAll: heartbeat due but no location available — no GPS fix and no prior send")
                    }
                }
            }

            updateStatus(nil)
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    private func pollPendingInvite() async throws {
        if pendingInitPayload != nil { return }
        if let result = try await locationClient.pollPendingInvite() {
            pendingInitPayload = result.payload
            multipleScansDetected = result.multipleScansDetected
            inviteState = Shared.InviteState.None()
            triggerRapidPoll()
        }
    }

    func clearInvite() async {
        do {
            try await e2eeStore.clearInvite()
        } catch {
            logger.error("Failed to clear invite: \(error.localizedDescription)")
        }
        resetRapidPoll()
        inviteState = Shared.InviteState.None()
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = Shared.QrPayload.companion.fromUrl(url: url) else {
            updateStatus(NSError(domain: "Where", code: 400, userInfo: [NSLocalizedDescriptionKey: MR.strings().invalid_qr_code.localized()]))
            return false
        }
        pendingQrForNaming = qr
        triggerRapidPoll()
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) async {
        pendingQrForNaming = nil
        let qrWithName = Shared.QrPayload(protocolVersion: Shared.ProtocolConstantsKt.PROTOCOL_VERSION, 
            ekPub: qr.ekPub,
            suggestedName: friendName,
            fingerprint: qr.fingerprint,
            discoverySecret: qr.discoverySecret
        )
        debugLog { "Scanning QR: discovery=\(qrWithName.discoveryToken().toHex()), friendName=\(friendName)" }
        inviteTask?.cancel()
        isExchanging = true
        triggerRapidPoll()

        defer { isExchanging = false }
        do {
            let result = try await e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName)
            guard let initPayload = result.first, let bobEntry = result.second else {
                logger.error("processScannedQr returned nil components")
                updateStatus(NSError(domain: "Where", code: -1, userInfo: [NSLocalizedDescriptionKey: MR.strings().pairing_failed.localized()]))
                return
            }

            await clearInvite()
            // Insert AFTER clearInvite: clearInvite calls resetRapidPoll which clears
            // awaitingFirstUpdateIds, so inserting before it is a no-op.
            awaitingFirstUpdateIds.insert(bobEntry.id)
            friends = try await e2eeStore.listFriends()
            updateVisibleUsers()

            do {
                debugLog { "Posting KeyExchangeInit" }
                try await locationClient.postKeyExchangeInit(qr: qrWithName, initPayload: initPayload)
                debugLog { "KeyExchangeInit posted successfully" }

                if let last = locationProvider.lastLocation {
                    // Proactively send our first location update to Alice to trigger confirmation on her side.
                    // We use sendLocationToFriend here to bypass the !isConfirmed check in sendLocation().
                    try? await locationClient.sendLocationToFriend(friendId: bobEntry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude)
                    self.lastSentTime = Date()
                } else {
                    logger.debug("confirmQrScan: lastLocation is nil, setting pendingForcedSendAfterPairing")
                    self.pendingForcedSendAfterPairing = true
                    locationProvider.requestPermissionAndStart()
                }
                await pollAll(updateUi: true)
                friends = try await e2eeStore.listFriends()
                updateVisibleUsers()
                triggerRapidPoll()

                updateStatus(nil)
            } catch {
                logger.error("Failed to post init: \(error.localizedDescription)")
                updateStatus(error)
            }
        } catch {
            logger.error("confirmQrScan failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func confirmPendingInit(payload: Shared.KeyExchangeInitPayload, name: String) async {
        isExchanging = true
        pendingInitPayload = nil
        multipleScansDetected = false

        defer { isExchanging = false }
        do {
            let result = try await e2eeStore.processKeyExchangeInit(payload: payload, bobName: name)

            if let entry = result ?? nil {
                awaitingFirstUpdateIds.insert(entry.id)
                friends = try await e2eeStore.listFriends()
                updateVisibleUsers()

                if let last = locationProvider.lastLocation {
                    // Proactively send our first location update to Bob to trigger confirmation on his side.
                    try? await locationClient.sendLocationToFriend(friendId: entry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude)
                    self.lastSentTime = Date()
                } else {
                    self.pendingForcedSendAfterPairing = true
                    locationProvider.requestPermissionAndStart()
                }
            }
            triggerRapidPoll()
            friends = try await e2eeStore.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("confirmPendingInit failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func cancelPendingInit() async {
        let hasInviteState = !(inviteState is Shared.InviteState.None)
        guard pendingInitPayload != nil || hasInviteState else { return }
        await clearInvite()
        pendingInitPayload = nil
        multipleScansDetected = false
    }

    func renameFriend(id: String, newName: String) async {
        do {
            try await e2eeStore.renameFriend(id: id, newName: newName)
            friends = try await e2eeStore.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("Failed to rename friend: \(error.localizedDescription)")
        }
    }

    func removeFriend(id: String) async {
        do {
            try await e2eeStore.deleteFriend(id: id)
            pausedFriendIds.remove(id)
            friends = try await e2eeStore.listFriends()
            friendLocations.removeValue(forKey: id)
            friendLastPing.removeValue(forKey: id)
            updateVisibleUsers()
        } catch {
            logger.error("Failed to remove friend: \(error.localizedDescription)")
        }
    }

    func sendLocationOnBackground() {
        guard isSharingLocation else { return }
        guard let loc = bestAvailableLocation else {
            logger.info("sendLocationOnBackground: skipped — no location available")
            return
        }
        logger.info("sendLocationOnBackground: sending location before app suspends")
        sendLocation(lat: loc.lat, lng: loc.lng)
    }

    func sendLocation(lat: Double, lng: Double, force: Bool = false) {
        let now = Date()
        let timeSinceLast = now.timeIntervalSince(lastSentTime)

        // Throttle: 30s unless forced.
        if !force && timeSinceLast < 30.0 {
            return
        }

        lastSentLocation = (lat: lat, lng: lng)
        lastSentTime = now
        pendingForcedSendAfterPairing = false

        let identifier = self.beginBackgroundTask("SendLocation") {
            // Task expired
        }
        let gen = sendTaskGeneration + 1
        sendTaskGeneration = gen

        currentSendTask?.cancel()
        currentSendTask = Task {
            defer {
                if identifier != .invalid {
                    self.endBackgroundTask(identifier)
                }
            }
            do {
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
                if !Task.isCancelled && gen == self.sendTaskGeneration {
                    logger.info("sendLocation: succeeded (lat=\(lat), lng=\(lng))")
                    updateStatus(nil)
                }
            } catch {
                if !Task.isCancelled && gen == self.sendTaskGeneration {
                    logger.error("Failed to send location: \(error.localizedDescription)")
                    updateStatus(error)
                }
            }
        }
    }

    private func updateStatus(_ error: Error?) {
        if let error = error {
            connectionStatus = Shared.ConnectionStatus.Error(message: RawStringDesc(error.localizedDescription))
        } else {
            connectionStatus = Shared.ConnectionStatus.Ok()
        }
    }

    func updateVisibleUsers() {
        if skipUpdateVisibleUsers { return }

        // Skip updating visible users if not sharing location AND app is in background.
        // This is a power optimization.
        if !isSharingLocation && !isInForeground() {
            visibleUsers = []
            return
        }

        var updates: [Shared.UserLocation] = []
        for (id, loc) in friendLocations {
            if pausedFriendIds.contains(id) { continue }
            updates.append(Shared.UserLocation(userId: id, lat: loc.lat, lng: loc.lng, timestamp: loc.ts))
        }
        visibleUsers = updates
    }
}

private extension UIBackgroundTaskIdentifier {
    func end() {
        if self != .invalid {
            MainActor.assumeIsolated {
                UIApplication.shared.endBackgroundTask(self)
            }
        }
    }
}
