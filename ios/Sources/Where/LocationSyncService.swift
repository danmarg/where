import Foundation
@preconcurrency import Shared
import os
import UIKit
import CoreLocation
import Combine
private let logger = Logger(subsystem: "net.af0.where", category: "LocationSync")

@inline(__always)
private func debugLog(_ msg: () -> String) {
    #if DEBUG
    let message = msg()
    logger.debug("\(message)")
    #endif
}

// MARK: - QR payload URL helpers

func qrPayloadToUrl(_ qr: Shared.QrPayload) -> String? {
    var ekPubData = toSwiftData(qr.ekPub)
    defer { ekPubData.zeroize() }
    var secretData = toSwiftData(qr.discoverySecret)
    defer { secretData.zeroize() }

    let dict: [String: Any] = [
        "ek_pub": ekPubData.base64EncodedString(),
        "suggested_name": qr.suggestedName,
        "fingerprint": qr.fingerprint,
        "discovery_secret": secretData.base64EncodedString(),
    ]
    do {
        let jsonData = try JSONSerialization.data(withJSONObject: dict)
        let b64 = jsonData.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "https://where.af0.net/invite#\(b64)"
    } catch {
        logger.error("Failed to serialize QR payload: \(error.localizedDescription)")
        return nil
    }
}

private func urlToQrPayload(_ url: String) -> Shared.QrPayload? {
    guard let fragment = URLComponents(string: url)?.fragment, !fragment.isEmpty else { return nil }
    var b64 = fragment.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64 += "=" }
    guard let data = Data(base64Encoded: b64),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let ekPub = (dict["ek_pub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          ekPub.count == 32,
          let name = dict["suggested_name"] as? String,
          let fp = dict["fingerprint"] as? String,
          fp.count == 40,
          let discoverySecret = (dict["discovery_secret"] as? String).flatMap({ Data(base64Encoded: $0) }),
          discoverySecret.count == 32
    else { return nil }
    return Shared.QrPayload(
        ekPub: kotlinByteArray(from: ekPub),
        suggestedName: name,
        fingerprint: fp,
        discoverySecret: kotlinByteArray(from: discoverySecret)
    )
}

// MARK: - HTTP mailbox helpers

// Ephemeral session: no URLCache, no cookie storage, no disk persistence.
private let noCacheSession: URLSession = {
    URLSession(configuration: .ephemeral)
}()

@MainActor
private func postToMailbox(token: String, bodyData: Data) async throws {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else {
        logger.error("Invalid mailbox URL")
        return
    }
    debugLog { "Posting to mailbox: \(url.absoluteString), bodySize=\(bodyData.count)" }
    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = bodyData
    req.cachePolicy = .reloadIgnoringLocalCacheData
    let (_, response) = try await noCacheSession.data(for: req)
    if let http = response as? HTTPURLResponse, http.statusCode != 200 && http.statusCode != 204 {
        logger.error("Mailbox POST failed: \(http.statusCode)")
        throw NSError(domain: "Where", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Server returned \(http.statusCode)"])
    }
    debugLog { "Mailbox POST succeeded: \((response as? HTTPURLResponse)?.statusCode ?? 0)" }
}

@MainActor
private func pollMailbox(token: String) async throws -> [[String: Any]] {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return [] }
    debugLog { "Polling mailbox: \(url.absoluteString)" }
    var req = URLRequest(url: url)
    req.cachePolicy = .reloadIgnoringLocalCacheData
    let (data, response) = try await noCacheSession.data(for: req)
    if let http = response as? HTTPURLResponse, http.statusCode != 200 {
        logger.error("Mailbox poll failed: \(http.statusCode)")
        throw NSError(domain: "Where", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "Server returned \(http.statusCode)"])
    }
    if let raw = String(data: data, encoding: .utf8) {
        debugLog { "Raw mailbox data: \(raw)" }
    }
    guard let arr = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
    debugLog { "Polled mailbox, got \(arr.count) messages" }
    return arr
}

// MARK: - LocationSyncService

enum ConnectionStatus: Sendable {
    case ok
    case error(message: String)
}

enum InviteState: Sendable {
    case none
    case pending(Shared.QrPayload)
}

@MainActor
final class LocationSyncService: ObservableObject {
    static let shared = LocationSyncService()

    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
    @Published var friendLastPing: [String: Date] = [:]
    @Published var connectionStatus: ConnectionStatus = .ok
    @Published var friends: [Shared.FriendEntry] = []
    @Published var inviteState: InviteState = .none
    @Published var isSharingLocation: Bool {
        didSet {
            let keychain = KeychainE2eeStorage()
            keychain.putString(key: "where_is_sharing", value: isSharingLocation ? "true" : "false")
        }
    }
    @Published var displayName: String {
        didSet {
            let keychain = KeychainE2eeStorage()
            keychain.putString(key: "display_name", value: displayName)
            if isInviteActive {
                Task { await createInvite() }
            }
        }
    }
    @Published var pausedFriendIds: Set<String> {
        didSet {
            let keychain = KeychainE2eeStorage()
            let json = try? JSONSerialization.data(withJSONObject: Array(pausedFriendIds))
            if let json = json, let jsonStr = String(data: json, encoding: .utf8) {
                keychain.putString(key: "paused_friends", value: jsonStr)
            }
        }
    }

    @Published var pendingQrForNaming: Shared.QrPayload? = nil
    @Published var pendingInitPayload: Shared.KeyExchangeInitPayload? = nil
    @Published var isExchanging: Bool = false
    private var inviteTask: Task<Void, Never>? = nil
    @Published var visibleUsers: [Shared.UserLocation] = []
    var isInviteActive: Bool { if case .pending = inviteState { return true } else { return false } }

    var lastRapidPollTrigger: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var pollTimer: Timer?  // internal for testing
    private var isPollInFlight = false
    /// Overridable in tests to simulate foreground/background without UIKit.
    var isInForeground: () -> Bool = { UIApplication.shared.applicationState == .active }
    private static let rapidPollInterval: TimeInterval = 1.0
    private static let normalPollInterval: TimeInterval = 60.0
    private static let maintenancePollInterval: TimeInterval = 30 * 60  // ack-only when not sharing
    private var visibleUsersCancellables = Set<AnyCancellable>()

    private var lastSentLocation: (lat: Double, lng: Double)? = nil
    private var lastSentTime: Date = Date(timeIntervalSince1970: 0)
    var pendingForcedSendAfterPairing: Bool = false
    private var currentSendTask: Task<Void, Never>? = nil
    private var isCurrentSendHeartbeat: Bool = false
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
    let locationClient: Shared.LocationClient

    init(e2eeStore: Shared.E2eeStore? = nil, locationClient: Shared.LocationClient? = nil) {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")

        let store = e2eeStore ?? Shared.E2eeStore(storage: KeychainE2eeStorage())
        self.e2eeStore = store
        self.locationClient = locationClient ?? Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)

        let keychain = KeychainE2eeStorage()

        if let sharingStr = keychain.getString(key: "where_is_sharing") {
            isSharingLocation = sharingStr == "true"
        } else {
            isSharingLocation = true
        }

        displayName = keychain.getString(key: "display_name") ?? ""

        if let pausedJsonStr = keychain.getString(key: "paused_friends"),
           let pausedData = pausedJsonStr.data(using: .utf8),
           let pausedArray = try? JSONSerialization.jsonObject(with: pausedData) as? [String] {
            pausedFriendIds = Set(pausedArray)
        } else {
            pausedFriendIds = []
        }

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
        Publishers.CombineLatest3($friendLocations, $isSharingLocation, LocationManager.shared.$location)
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

    func isRapidPolling() async -> Bool {
        let now = Date()
        // Kotlin suspend functions returning optional types appear as T?? in Swift.
        // We use ?? nil to flatten them to T?.
        let pending = try? await e2eeStore.pendingQrPayload()
        let isPairing = (pending ?? nil) != nil || pendingInitPayload != nil || pendingQrForNaming != nil
        let recentlyTriggered = now.timeIntervalSince(lastRapidPollTrigger) < 5 * 60
        return isPairing || recentlyTriggered
    }

    private func updateVisibleUsers() {
        let confirmedIds = Set(friends.filter { $0.isConfirmed }.map { $0.id })
        visibleUsers = friendLocations
            .filter { confirmedIds.contains($0.key) }
            .map { (friendId, locData) in
                Shared.UserLocation(userId: friendId, lat: locData.lat, lng: locData.lng, timestamp: locData.ts)
            }
    }

    // Poll timer: handles inbound friend-location polling and the outbound heartbeat.
    // Movement-driven sends are handled by LocationManager's CoreLocation delegate.
    // Since UIBackgroundModes contains "location", CoreLocation wakes the app in the
    // background and delivers didUpdateLocations callbacks — no BGAppRefreshTask required.
    // However, when the device is stationary CoreLocation may not fire for extended periods,
    // so the poll timer also triggers a heartbeat send (throttled to 5 minutes by sendLocation).
    func startPolling() {
        logger.debug("startPolling called")
        guard pollTimer?.isValid != true else { return }
        schedulePollTimer(interval: Self.normalPollInterval)
        Task {
            await firePoll()
        }
    }

    func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func schedulePollTimer(interval: TimeInterval) {
        pollTimer?.invalidate()
        let t = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self = self else { return }
                let backgroundTask = self.startBackgroundTask("PollTimer")
                defer { backgroundTask.end() }
                await self.firePoll()
            }
        }
        RunLoop.main.add(t, forMode: .common)
        pollTimer = t
    }

    func firePoll() async {  // internal for testing
        guard !isPollInFlight else { return }
        isPollInFlight = true
        defer { isPollInFlight = false }

        let inForeground = isInForeground()
        let isRapid = await isRapidPolling()

        // Always poll — even when sharing is off we need to process incoming
        // EpochRotations and post Ratchet Acks so Alice's location doesn't get stuck.
        // The timer interval drops to 30 min in that case (maintenance-only).
        // updateUi (which drives pollPendingInvite) is only needed in foreground/rapid.
        await pollAll(updateUi: inForeground || isRapid)
        // Heartbeat always runs: covers the stationary background case where
        // didUpdateLocations never fires (distanceFilter suppresses updates).
        if let loc = LocationManager.shared.lastLocation {
            sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, isHeartbeat: true)
        }

        // Adjust timer interval based on app state.
        let targetInterval: TimeInterval
        if isRapid {
            targetInterval = Self.rapidPollInterval        // 1s: pairing
        } else if inForeground {
            targetInterval = Self.normalPollInterval       // 60s: user watching map
        } else if isSharingLocation {
            targetInterval = 5 * 60                       // 5 min: heartbeat + friend poll
        } else {
            targetInterval = Self.maintenancePollInterval  // 30 min: Ratchet Ack maintenance
        }
        if let t = pollTimer, abs(t.timeInterval - targetInterval) > 0.1 {
            schedulePollTimer(interval: targetInterval)
        }
    }

    @MainActor
    func sendLocation(lat: Double, lng: Double, isHeartbeat: Bool = false, force: Bool = false) {
        MainActor.assertIsolated()

        // pendingForcedSendAfterPairing is read here so the CoreLocation delegate path
        // (which calls sendLocation without force) still picks up post-pairing forced sends.
        let effectiveForce = force || pendingForcedSendAfterPairing
        guard isSharingLocation else { return }

        // If a task is already in-flight, skip this update unless it's forced
        // or we're replacing an in-flight heartbeat with a movement update.
        let replacingHeartbeat = !isHeartbeat && isCurrentSendHeartbeat
        if currentSendTask != nil && !effectiveForce && !replacingHeartbeat { return }

        // Cancel existing task if this is a forced update or if we're replacing a heartbeat.
        // Bump the generation so the cancelled task's deferred cleanup won't clear the new task.
        if (effectiveForce || replacingHeartbeat), let existing = currentSendTask {
            sendTaskGeneration += 1
            existing.cancel()
            currentSendTask = nil
            isCurrentSendHeartbeat = false
        }

        let now = Date()
        let shouldSend = effectiveForce || replacingHeartbeat || lastSentLocation == nil ||
                        (!isHeartbeat && now.timeIntervalSince(lastSentTime) > 15) ||
                        (isHeartbeat && now.timeIntervalSince(lastSentTime) > 5 * 60)

        guard shouldSend else { return }

        // Update throttle markers immediately *before* spawning the task to ensure
        // concurrent triggers are correctly throttled.
        lastSentLocation = (lat: lat, lng: lng)
        lastSentTime = now

        // Capture whether the pairing flag contributed before clearing it.
        // We clear optimistically to prevent concurrent forced sends; on failure we restore it.
        let wasForcedByPairing = pendingForcedSendAfterPairing
        if effectiveForce {
            pendingForcedSendAfterPairing = false
        }

        logger.debug("Sending location: \(lat), \(lng) (heartbeat=\(isHeartbeat), force=\(force), effectiveForce=\(effectiveForce))")

        isCurrentSendHeartbeat = isHeartbeat
        sendTaskGeneration += 1
        let myGeneration = sendTaskGeneration
        let backgroundTask = self.startBackgroundTask("SendLocation")
        currentSendTask = Task {
            // Re-check isSharingLocation inside Task if needed,
            // but for simplicity we rely on the main-actor throttle check above.

            var sendSucceeded = false
            defer {
                backgroundTask.end()
                // Synchronously clear task state on @MainActor — no nested Task needed,
                // avoiding the race where a forced send still sees a non-nil currentSendTask.
                // Only clear state if this task is still the current one; a stale cleanup
                // from a cancelled task must not overwrite state set by a newer task.
                if self.sendTaskGeneration == myGeneration {
                    self.currentSendTask = nil
                    self.isCurrentSendHeartbeat = false
                }
                // Restore the pairing flag if the send did not succeed (error or cancellation)
                // and no newer task has taken over. This covers both thrown errors and silent
                // cancellation paths where the underlying call returns without throwing.
                if wasForcedByPairing && !sendSucceeded && self.sendTaskGeneration == myGeneration {
                    self.pendingForcedSendAfterPairing = true
                }
            }

            do {
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
                sendSucceeded = true
                logger.debug("Location sent successfully")
                updateStatus(nil)
            } catch {
                logger.error("Failed to send location: \(error.localizedDescription)")
                updateStatus(error)
            }
        }
    }

    func createInvite() async {
        inviteTask?.cancel()
        guard pendingInitPayload == nil && pendingQrForNaming == nil else { return }
 
        inviteTask = Task {
            do {
                let qr = try await e2eeStore.createInvite(suggestedName: displayName.isEmpty ? "Me" : displayName)
                try Task.checkCancellation()
                debugLog { "Created invite: discovery=\(toHex(qr.discoveryToken()))" }
                inviteState = .pending(qr)
                triggerRapidPoll()
            } catch is CancellationError {
                // Ignore
            } catch {
                logger.error("Failed to create invite: \(error.localizedDescription)")
                updateStatus(error)
            }
        }
        await inviteTask?.value
    }

    func clearInvite() async {
        do {
            try await e2eeStore.clearInvite()
        } catch {
            logger.error("Failed to clear invite: \(error.localizedDescription)")
        }
        resetRapidPoll()
        inviteState = .none
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = urlToQrPayload(url) else {
            updateStatus(NSError(domain: "Where", code: 400, userInfo: [NSLocalizedDescriptionKey: "Invalid QR code"]))
            return false
        }
        pendingQrForNaming = qr
        triggerRapidPoll()
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) async {
        pendingQrForNaming = nil
        let qrWithName = Shared.QrPayload(
            ekPub: qr.ekPub,
            suggestedName: friendName,
            fingerprint: qr.fingerprint,
            discoverySecret: qr.discoverySecret
        )
        debugLog { "Scanning QR: discovery=\(toHex(qrWithName.discoveryToken())), friendName=\(friendName)" }
        inviteTask?.cancel()
        isExchanging = true
        triggerRapidPoll()

        defer { isExchanging = false }
        do {
            let result = try await e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName)
            guard let initPayload = result.first, let bobEntry = result.second else {
                logger.error("processScannedQr returned nil components")
                updateStatus(NSError(domain: "Where", code: -1, userInfo: [NSLocalizedDescriptionKey: "Pairing failed: invalid response"]))
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

                if let last = LocationManager.shared.lastLocation {
                    self.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude, force: true)
                } else {
                    logger.debug("confirmQrScan: lastLocation is nil, setting pendingForcedSendAfterPairing")
                    self.pendingForcedSendAfterPairing = true
                    LocationManager.shared.requestPermissionAndStart()
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

        defer { isExchanging = false }
        do {
            let result = try await e2eeStore.processKeyExchangeInit(payload: payload, bobName: name)

            if let entry = result ?? nil {
                awaitingFirstUpdateIds.insert(entry.id)
                friends = try await e2eeStore.listFriends()
                updateVisibleUsers()

                if let last = LocationManager.shared.lastLocation {
                    self.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude, force: true)
                } else {
                    self.pendingForcedSendAfterPairing = true
                    LocationManager.shared.requestPermissionAndStart()
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
        let hasInviteState = if case .none = inviteState { false } else { true }
        guard pendingInitPayload != nil || hasInviteState else { return }
        await clearInvite()
        pendingInitPayload = nil
        resetRapidPoll()
        inviteState = .none
    }

    func wakePoll() {
        Task { @MainActor in
            let backgroundTask = self.startBackgroundTask("WakePoll")
            defer { backgroundTask.end() }
            await firePoll()
        }
    }

    private func triggerRapidPoll() {
        lastRapidPollTrigger = Date()
        schedulePollTimer(interval: Self.rapidPollInterval)
        Task { @MainActor in
            let backgroundTask = self.startBackgroundTask("RapidPoll")
            defer { backgroundTask.end() }
            await firePoll()
        }
    }

    func resetRapidPoll() {
        lastRapidPollTrigger = Date(timeIntervalSince1970: 0)
        awaitingFirstUpdateIds.removeAll()
        schedulePollTimer(interval: Self.normalPollInterval)
    }

    func renameFriend(id: String, newName: String) async {
        do {
            try await e2eeStore.renameFriend(id: id, newName: newName)
            friends = try await e2eeStore.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("renameFriend failed: \(error.localizedDescription)")
        }
    }
 
    func setFriendPrecision(id: String, precision: Shared.LocationPrecision) async {
        do {
            try await e2eeStore.updateFriendPrecision(id: id, precision: precision)
            friends = try await e2eeStore.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("setFriendPrecision failed: \(error.localizedDescription)")
        }
    }

    func togglePauseFriend(id: String) {
        if pausedFriendIds.contains(id) {
            pausedFriendIds.remove(id)
        } else {
            pausedFriendIds.insert(id)
        }
    }

    func removeFriend(id: String) async {
        do {
            try await e2eeStore.deleteFriend(id: id)
            friends = try await e2eeStore.listFriends()
            friendLocations.removeValue(forKey: id)
            pausedFriendIds.remove(id)
        } catch {
            logger.error("removeFriend failed: \(error.localizedDescription)")
        }
    }

    // MARK: - Internal polling

    private func onFriendLocationReceived(friendId: String) {
        if awaitingFirstUpdateIds.remove(friendId) != nil {
            if awaitingFirstUpdateIds.isEmpty {
                resetRapidPoll()
            }
        }
    }

    func pollAll(updateUi: Bool = true) async {
        logger.debug("Polling for location updates (updateUi=\(updateUi))")
        let backgroundTask = self.startBackgroundTask("PollAll")

        defer {
            backgroundTask.end()
        }
        do {
            let updates = try await locationClient.poll(isForeground: isInForeground())
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                try? await e2eeStore.updateLastLocation(id: update.userId, lat: update.lat, lng: update.lng, ts: Int64(Date().timeIntervalSince1970))
                friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLastPing[update.userId] = Date()
                onFriendLocationReceived(friendId: update.userId)
            }

            friends = try await e2eeStore.listFriends()
            // Always update visibleUsers to ensure map is fresh when returning to foreground.
            updateVisibleUsers()

            if updateUi {
                try await pollPendingInvite()
            }
            updateStatus(nil)
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    private func pollPendingInvite() async throws {
        if pendingInitPayload != nil { return }
        if let payload = try await locationClient.pollPendingInvite() {
            debugLog { "pollPendingInvite: received KeyExchangeInit" }
            inviteTask?.cancel()
            inviteState = .none   // dismiss the QR sheet so the naming alert can appear
            pendingInitPayload = payload
            triggerRapidPoll()
        }
    }

    @MainActor
    func startBackgroundTask(_ name: String) -> BackgroundTaskBox {
        let endOp = self.endBackgroundTask
        let backgroundTask = BackgroundTaskBox(end: { id in
            endOp(id)
        })
        let id = self.beginBackgroundTask(name) {
            backgroundTask.end()
        }
        backgroundTask.identifier = id
        return backgroundTask
    }

    private func updateStatus(_ error: Error?) {
        if let error = error {
            let msg: String
            if let whereEx = error as? Shared.WhereException {
                if let netEx = whereEx as? Shared.NetworkException {
                    if netEx is Shared.ConnectException {
                        msg = "No connection"
                    } else if netEx is Shared.TimeoutException {
                        msg = "Request timed out"
                    } else if let serverEx = netEx as? Shared.ServerException {
                        msg = "Server error \(serverEx.statusCode)"
                    } else {
                        msg = "Network error"
                    }
                } else if let cryptoEx = whereEx as? Shared.CryptoException {
                    if cryptoEx is Shared.AuthenticationException {
                        msg = "Authentication failed"
                    } else if cryptoEx is Shared.ProtocolException {
                        msg = "Protocol error"
                    } else {
                        msg = "Security error"
                    }
                } else {
                    msg = "Internal error"
                }
            } else {
                let desc = error.localizedDescription.lowercased()
                if desc.contains("timeout") {
                    msg = "Timeout"
                } else if desc.contains("not resolved") || desc.contains("connection") {
                    msg = "No connection"
                } else if let nsError = error as NSError?, nsError.domain == "Where" {
                    msg = "Server error \(nsError.code)"
                } else {
                    msg = String(desc.prefix(32))
                }
            }
            connectionStatus = .error(message: msg)
        } else {
            connectionStatus = .ok
        }
    }
}

// MARK: - Sendable extensions for Kotlin types

extension Shared.E2eeStore: @unchecked @retroactive Sendable {}
extension Shared.QrPayload: @unchecked @retroactive Sendable {}
extension Shared.FriendEntry: @unchecked @retroactive Sendable {}
extension Shared.KeyExchangeInitPayload: @unchecked @retroactive Sendable {}
extension Shared.UserLocation: @unchecked @retroactive Sendable {}
extension Shared.LocationClient: @unchecked @retroactive Sendable {}
extension Shared.KotlinPair: @unchecked @retroactive Sendable {}
extension Shared.LocationPlaintext: @unchecked @retroactive Sendable {}

/// Thread-safe wrapper for UIBackgroundTaskIdentifier to prevent races between
/// the expiry handler and the normal completion path.
final class BackgroundTaskBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _identifier: UIBackgroundTaskIdentifier = .invalid
    private var isEnded = false
    private var isExpired = false
    private let endOp: @Sendable (UIBackgroundTaskIdentifier) -> Void

    var identifier: UIBackgroundTaskIdentifier {
        get {
            lock.lock()
            defer { lock.unlock() }
            return _identifier
        }
        set {
            var idToEnd: UIBackgroundTaskIdentifier = .invalid
            lock.lock()
            _identifier = newValue
            // If the task was already expired by the handler before we even got the ID,
            // end it immediately.
            if isExpired && !isEnded && newValue != .invalid {
                isEnded = true
                idToEnd = newValue
            }
            lock.unlock()
            if idToEnd != .invalid {
                endOp(idToEnd)
            }
        }
    }

    init(end: @Sendable @escaping (UIBackgroundTaskIdentifier) -> Void) {
        self.endOp = end
    }

    func end() {
        var idToEnd: UIBackgroundTaskIdentifier = .invalid
        lock.lock()
        if !isEnded && _identifier != .invalid {
            isEnded = true
            idToEnd = _identifier
        }
        isExpired = true
        lock.unlock()

        if idToEnd != .invalid {
            endOp(idToEnd)
        }
    }
}
