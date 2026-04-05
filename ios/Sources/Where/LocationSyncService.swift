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

func qrPayloadToUrl(_ qr: Shared.QrPayload) -> String {
    let dict: [String: Any] = [
        "ek_pub": toSwiftData(qr.ekPub).base64EncodedString(),
        "suggested_name": qr.suggestedName,
        "fingerprint": qr.fingerprint,
    ]
    let jsonData = try! JSONSerialization.data(withJSONObject: dict)
    let b64 = jsonData.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
    return "where://invite?q=\(b64)"
}

private func urlToQrPayload(_ url: String) -> Shared.QrPayload? {
    guard let q = URLComponents(string: url)?.queryItems?.first(where: { $0.name == "q" })?.value else { return nil }
    var b64 = q.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64 += "=" }
    guard let data = Data(base64Encoded: b64),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let ekPub = (dict["ek_pub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          let name = dict["suggested_name"] as? String,
          let fp = dict["fingerprint"] as? String
    else { return nil }
    return Shared.QrPayload(
        ekPub: kotlinByteArray(from: ekPub),
        suggestedName: name,
        fingerprint: fp
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

enum ConnectionStatus {
    case ok
    case error(message: String)
}

enum InviteState {
    case none
    case pending(Shared.QrPayload)
    case consumed(Shared.QrPayload)
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
        didSet { UserDefaults.standard.set(isSharingLocation, forKey: "where_is_sharing") }
    }
    @Published var displayName: String {
        didSet { UserDefaults.standard.set(displayName, forKey: "display_name") }
    }
    @Published var pausedFriendIds: Set<String> {
        didSet { UserDefaults.standard.set(Array(pausedFriendIds), forKey: "paused_friends") }
    }

    @Published var pendingQrForNaming: Shared.QrPayload? = nil
    @Published var pendingInitPayload: Shared.KeyExchangeInitPayload? = nil
    @Published var isExchanging: Bool = false
    @Published var visibleUsers: [Shared.UserLocation] = []
    var isInviteActive: Bool { if case .pending = inviteState { return true } else { return false } }

    private var lastRapidPollTrigger: Date = Date(timeIntervalSince1970: 0)
    private let pollSignals: AsyncStream<Void>
    private let pollSignalContinuation: AsyncStream<Void>.Continuation
    private var visibleUsersCancellables = Set<AnyCancellable>()

    private var lastSentLocation: (lat: Double, lng: Double)? = nil
    private var lastSentTime: Date = Date(timeIntervalSince1970: 0)
    var pendingForcedSendAfterPairing: Bool = false
    private var currentSendTask: Task<Void, Never>? = nil

    // Injected for testing
    var beginBackgroundTask: (String, @escaping () -> Void) -> UIBackgroundTaskIdentifier = { name, handler in
        UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: handler)
    }
    var endBackgroundTask: (UIBackgroundTaskIdentifier) -> Void = { identifier in
        UIApplication.shared.endBackgroundTask(identifier)
    }

    let e2eeStore: Shared.E2eeStore
    let locationClient: Shared.LocationClient
    private var pollTask: Task<Void, Never>?

    let myId: String = {
        let key = "where_user_id"
        if let id = UserDefaults.standard.string(forKey: key) { return id }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        UserDefaults.standard.set(id, forKey: key)
        return id
    }()

    deinit {
        let task = pollTask
        task?.cancel()
    }

    init(e2eeStore: Shared.E2eeStore? = nil, locationClient: Shared.LocationClient? = nil) {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")

        let (stream, continuation) = AsyncStream<Void>.makeStream()
        self.pollSignals = stream
        self.pollSignalContinuation = continuation

        let store = e2eeStore ?? Shared.E2eeStore(storage: KeychainE2eeStorage())
        self.e2eeStore = store
        self.locationClient = locationClient ?? Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)

        let savedSharing = UserDefaults.standard.object(forKey: "where_is_sharing")
        isSharingLocation = savedSharing != nil ? UserDefaults.standard.bool(forKey: "where_is_sharing") : true

        displayName = UserDefaults.standard.string(forKey: "display_name") ?? ""

        let savedPaused = UserDefaults.standard.stringArray(forKey: "paused_friends") ?? []
        pausedFriendIds = Set(savedPaused)

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

        startPolling()
    }

    fileprivate func isRapidPolling() async -> Bool {
        let now = Date()
        let isPairing = (try? await e2eeStore.pendingQrPayload()) != nil || pendingInitPayload != nil || pendingQrForNaming != nil
        let recentlyTriggered = now.timeIntervalSince(lastRapidPollTrigger) < 5 * 60
        return isPairing || recentlyTriggered
    }

    private func updateVisibleUsers() {
        var result: [Shared.UserLocation] = []
        if isSharingLocation, let loc = LocationManager.shared.location {
            result.append(Shared.UserLocation(
                userId: myId,
                lat: loc.coordinate.latitude,
                lng: loc.coordinate.longitude,
                timestamp: Int64(loc.timestamp.timeIntervalSince1970)
            ))
        }
        for (friendId, locData) in friendLocations {
            result.append(Shared.UserLocation(userId: friendId, lat: locData.lat, lng: locData.lng, timestamp: locData.ts))
        }
        visibleUsers = result
    }

    // Poll loop: handles inbound friend-location polling and the outbound heartbeat.
    // Movement-driven sends are handled by LocationManager's CoreLocation delegate.
    // Since UIBackgroundModes contains "location", CoreLocation wakes the app in the
    // background and delivers didUpdateLocations callbacks — no BGAppRefreshTask required.
    // However, when the device is stationary CoreLocation may not fire for extended periods,
    // so the poll loop also triggers a heartbeat send (throttled to 5 minutes by sendLocation).
    func startPolling() {
        // Idempotency guard: do not create a second loop if one is alive.
        if let existing = pollTask, !existing.isCancelled { return }
        pollTask = Task { [weak self] in
            // Use an iterator to catch all signals, including those during pollAll.
            // Since this Task inherits MainActor isolation, we can safely access self weakly.
            guard let signals = self?.pollSignals else { return }
            var signalIterator = signals.makeAsyncIterator()

            while !Task.isCancelled {
                // Re-bind self for each loop iteration to prevent retain cycles while ensuring
                // safety for the duration of the iteration.
                guard let isolatedSelf = self else { return }

                await isolatedSelf.pollAll(updateUi: true)
                // Heartbeat: ensure we send at least once every 5 minutes when stationary.
                if let loc = LocationManager.shared.lastLocation {
                    isolatedSelf.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, isHeartbeat: true)
                }

                let isRapid = await isolatedSelf.isRapidPolling()
                let intervalSeconds = isRapid ? 2.0 : 60.0

                // Use the local self for timeout management.
                try? await isolatedSelf.withTimeout(seconds: intervalSeconds) {
                    _ = await signalIterator.next()
                }
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func triggerRapidPoll() {
        lastRapidPollTrigger = Date()
        pollSignalContinuation.yield()
    }

    @MainActor
    func sendLocation(lat: Double, lng: Double, isHeartbeat: Bool = false, force: Bool = false) {
        MainActor.assertIsolated()

        // pendingForcedSendAfterPairing is read here so the CoreLocation delegate path
        // (which calls sendLocation without force) still picks up post-pairing forced sends.
        let effectiveForce = force || pendingForcedSendAfterPairing
        guard isSharingLocation else { return }

        // If a task is already in-flight, skip this update unless it's forced.
        if currentSendTask != nil && !effectiveForce { return }

        let now = Date()
        let shouldSend = effectiveForce || lastSentLocation == nil ||
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

        currentSendTask = Task {
            // Re-check isSharingLocation inside Task if needed,
            // but for simplicity we rely on the main-actor throttle check above.

            // Structured background task management
            let backgroundTask = BackgroundTaskBox(end: self.endBackgroundTask)
            backgroundTask.identifier = self.beginBackgroundTask("SendLocation") {
                backgroundTask.end()
            }

            guard backgroundTask.identifier != .invalid else {
                logger.error("Failed to begin background task for SendLocation")
                return
            }

            defer {
                backgroundTask.end()
                Task { @MainActor in
                    currentSendTask = nil
                }
            }

            do {
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
                logger.debug("Location sent successfully")
                updateStatus(nil)
            } catch {
                logger.error("Failed to send location: \(error.localizedDescription)")
                // Restore the pairing flag so the next location callback retries the send.
                if wasForcedByPairing {
                    pendingForcedSendAfterPairing = true
                }
                updateStatus(error)
            }
        }
    }

    func createInvite() async {
        do {
            let qr = try await e2eeStore.createInvite(suggestedName: displayName.isEmpty ? "Me" : displayName)
            debugLog { "Created invite: discovery=\(toHex(qr.discoveryToken()))" }
            inviteState = .pending(qr)
            triggerRapidPoll()
        } catch {
            logger.error("Failed to create invite: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func clearInvite() async {
        if case .pending = inviteState {
            do {
                try await e2eeStore.clearInvite()
            } catch {
                logger.error("Failed to clear invite: \(error.localizedDescription)")
            }
        }
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
            fingerprint: qr.fingerprint
        )
        debugLog { "Scanning QR: discovery=\(toHex(qrWithName.discoveryToken())), friendName=\(friendName)" }
        isExchanging = true
        triggerRapidPoll()

        defer { isExchanging = false }
        do {
            let result = try await e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName)
            let initPayload = result.first!
            let bobEntry = result.second!

            friends = try await e2eeStore.listFriends()

            let discoveryHex = toHex(qrWithName.discoveryToken())
            let payload: [String: Any] = [
                "v": 1,
                "type": "KeyExchangeInit",
                "token": initPayload.token,
                "ek_pub": toSwiftData(initPayload.ekPub).base64EncodedString(),
                "key_confirmation": toSwiftData(initPayload.keyConfirmation).base64EncodedString(),
                "suggested_name": initPayload.suggestedName,
            ]

            if let bodyData = try? JSONSerialization.data(withJSONObject: payload) {
                do {
                    debugLog { "Posting KeyExchangeInit to mailbox with token: \(discoveryHex)" }
                    try await postToMailbox(token: discoveryHex, bodyData: bodyData)
                    debugLog { "KeyExchangeInit posted successfully" }

                    try? await Task.sleep(nanoseconds: 500_000_000)

                    try await locationClient.postOpkBundle(friendId: bobEntry.id)

                    if let last = LocationManager.shared.lastLocation {
                        self.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude, force: true)
                    } else {
                        self.pendingForcedSendAfterPairing = true
                        LocationManager.shared.requestPermissionAndStart()
                    }
                    await pollAll(updateUi: true)

                    updateStatus(nil)
                } catch {
                    logger.error("Failed to post init: \(error.localizedDescription)")
                    updateStatus(error)
                }
            }
        } catch {
            logger.error("confirmQrScan failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func confirmPendingInit(name: String) async {
        guard let payload = pendingInitPayload else { return }
        pendingInitPayload = nil
        if case .pending = inviteState {
            await clearInvite()
        }
        inviteState = .none
        isExchanging = true
        triggerRapidPoll()

        defer { isExchanging = false }
        do {
            if let entry = try await e2eeStore.processKeyExchangeInit(payload: payload, bobName: name) {
                friends = try await e2eeStore.listFriends()
                try await locationClient.postOpkBundle(friendId: entry.id)

                if let last = LocationManager.shared.lastLocation {
                    self.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude, force: true)
                } else {
                    self.pendingForcedSendAfterPairing = true
                    LocationManager.shared.requestPermissionAndStart()
                }
            }
            await pollAll(updateUi: true)
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
        inviteState = .none
    }

    func renameFriend(id: String, newName: String) async {
        do {
            try await e2eeStore.renameFriend(id: id, newName: newName)
            friends = try await e2eeStore.listFriends()
        } catch {
            logger.error("renameFriend failed: \(error.localizedDescription)")
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

    // MARK: - Private polling

    private func pollAll(updateUi: Bool = true) async {
        logger.debug("Polling for location updates")
        let backgroundTask = BackgroundTaskBox(end: self.endBackgroundTask)
        backgroundTask.identifier = self.beginBackgroundTask("PollAll") {
            backgroundTask.end()
        }

        defer {
            backgroundTask.end()
        }
        do {
            let updates = try await locationClient.poll()
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                try? await e2eeStore.updateLastLocation(id: update.userId, lat: update.lat, lng: update.lng, ts: Int64(Date().timeIntervalSince1970))
                friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLastPing[update.userId] = Date()
            }
            if updateUi {
                await pollPendingInvite()
            }
            updateStatus(nil)
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    private func pollPendingInvite() async {
        guard let qr = try? await e2eeStore.pendingQrPayload() else { return }
        let discoveryHex = toHex(qr.discoveryToken())
        debugLog { "pollPendingInvite: discoveryHex=\(discoveryHex)" }
        let messages: [[String: Any]]
        do {
            messages = try await pollMailbox(token: discoveryHex)
            if !messages.isEmpty {
                debugLog { "pollPendingInvite: got \(messages.count) messages" }
            }
            updateStatus(nil)
        } catch {
            logger.error("pollPendingInvite error: \(error.localizedDescription)")
            updateStatus(error)
            return
        }
        for msg in messages {
            let version = msg["v"] as? Int ?? 1
            guard version == 1,
                  (msg["type"] as? String) == "KeyExchangeInit",
                  let token = msg["token"] as? String,
                  let ekPubB64 = msg["ek_pub"] as? String, let ekPub = Data(base64Encoded: ekPubB64),
                  let keyConfB64 = msg["key_confirmation"] as? String,
                  let keyConfData = Data(base64Encoded: keyConfB64)
            else { continue }
            let suggestedName = msg["suggested_name"] as? String ?? ""
            let initPayload = Shared.KeyExchangeInitPayload(
                v: Int32(version),
                token: token,
                ekPub: kotlinByteArray(from: ekPub),
                keyConfirmation: kotlinByteArray(from: keyConfData),
                suggestedName: suggestedName
            )

            if case .pending(let currentQr) = inviteState {
                inviteState = .consumed(currentQr)
            }
            pendingInitPayload = initPayload
            triggerRapidPoll()
            break
        }
    }

    private func withTimeout(seconds: Double, body: @escaping () async -> Void) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                await body()
            }
            group.addTask {
                try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                throw CancellationError()
            }
            try await group.next()
            group.cancelAll()
        }
    }

    /// Thread-safe wrapper for UIBackgroundTaskIdentifier to prevent races between
    /// the expiry handler and the normal completion path.
    private final class BackgroundTaskBox: @unchecked Sendable {
        private let lock = NSLock()
        private var _identifier: UIBackgroundTaskIdentifier = .invalid
        private var isEnded = false
        private var isExpired = false
        private let endOp: (UIBackgroundTaskIdentifier) -> Void

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

        init(end: @escaping (UIBackgroundTaskIdentifier) -> Void) {
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

    private func updateStatus(_ error: Error?) {
        if let error = error {
            let msg: String
            let desc = error.localizedDescription.lowercased()
            if desc.contains("timeout") {
                msg = "timeout"
            } else if desc.contains("not resolved") || desc.contains("connection") {
                msg = "no connection"
            } else if let nsError = error as NSError?, nsError.domain == "Where" {
                msg = "server error \(nsError.code)"
            } else {
                msg = String(desc.prefix(32))
            }
            connectionStatus = .error(message: msg)
        } else {
            connectionStatus = .ok
        }
    }
}

// MARK: - Sendable extensions for Kotlin types

extension Shared.E2eeStore: @unchecked Sendable {}
extension Shared.QrPayload: @unchecked Sendable {}
extension Shared.FriendEntry: @unchecked Sendable {}
extension Shared.KeyExchangeInitPayload: @unchecked Sendable {}
extension Shared.UserLocation: @unchecked Sendable {}
extension Shared.LocationClient: @unchecked Sendable {}
extension Shared.KotlinPair: @unchecked Sendable {}
extension Shared.LocationPlaintext: @unchecked Sendable {}
