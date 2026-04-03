import Foundation
import Shared
import os

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
    let (_, response) = try await URLSession.shared.data(for: req)
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
    let (data, response) = try await URLSession.shared.data(for: req)
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

@MainActor
final class LocationSyncService: ObservableObject {
    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
    @Published var friendLastPing: [String: Date] = [:]  // Track last location update time
    @Published var connectionStatus: ConnectionStatus = .ok
    @Published var friends: [Shared.FriendEntry] = []
    @Published var pendingInviteQr: Shared.QrPayload? = nil
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
    @Published var hasPendingInit: Bool = false
    var isInviteActive: Bool { pendingInviteQr != nil }

    private var lastRapidPollTrigger: Date = Date(timeIntervalSince1970: 0)
    private var autoClearedInvite: Bool = false

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

    init() {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")
        let store = Shared.E2eeStore(storage: UserDefaultsE2eeStorage())
        self.e2eeStore = store
        self.locationClient = Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)

        let savedSharing = UserDefaults.standard.object(forKey: "where_is_sharing")
        isSharingLocation = savedSharing != nil ? UserDefaults.standard.bool(forKey: "where_is_sharing") : true

        displayName = UserDefaults.standard.string(forKey: "display_name") ?? ""

        let savedPaused = UserDefaults.standard.stringArray(forKey: "paused_friends") ?? []
        pausedFriendIds = Set(savedPaused)

        Task {
            friends = e2eeStore.listFriends()
        }
        startPolling()
    }

    private func triggerRapidPoll() {
        lastRapidPollTrigger = Date()
    }

    private func isRapidPolling() async -> Bool {
        let now = Date()
        let isPairing = e2eeStore.pendingQrPayload != nil || hasPendingInit
        let recentlyTriggered = now.timeIntervalSince(lastRapidPollTrigger) < 5 * 60
        return isPairing || recentlyTriggered
    }

    func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollAll()
                let rapid = await self?.isRapidPolling() == true
                let interval: UInt64 = rapid ? 2_000_000_000 : 60_000_000_000  // 2s while pairing, 60s otherwise
                try? await Task.sleep(nanoseconds: interval)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    func sendLocation(lat: Double, lng: Double) {
        guard isSharingLocation else { return }
        logger.debug("Sending location: \(lat), \(lng)")
        Task {
            do {
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
                logger.debug("Location sent successfully")
                updateStatus(nil)
            } catch {
                logger.error("Failed to send location: \(error.localizedDescription)")
                updateStatus(error)
            }
        }
    }

    func createInvite() {
        Task {
            autoClearedInvite = false
            let qr = e2eeStore.createInvite(suggestedName: displayName.isEmpty ? "Me" : displayName)
            debugLog { "Created invite: discovery=\(toHex(qr.discoveryToken()))" }
            pendingInviteQr = qr
            triggerRapidPoll()
            // Trigger an immediate poll
            await pollPendingInvite()
        }
    }

    func clearInvite() {
        Task {
            if !autoClearedInvite {
                e2eeStore.clearInvite()
            }
            pendingInviteQr = nil
            autoClearedInvite = false
        }
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = urlToQrPayload(url) else {
            updateStatus(NSError(domain: "Where", code: 400, userInfo: [NSLocalizedDescriptionKey: "Invalid QR code"]))
            return false
        }
        pendingQrForNaming = qr
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) {
        pendingQrForNaming = nil
        triggerRapidPoll()
        let qrWithName = Shared.QrPayload(
            ekPub: qr.ekPub,
            suggestedName: friendName,
            fingerprint: qr.fingerprint
        )
        debugLog { "Scanning QR: discovery=\(toHex(qrWithName.discoveryToken())), friendName=\(friendName)" }
        Task {
            let result = e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName)
            let initPayload = result.first!
            let bobEntry = result.second!
            friends = e2eeStore.listFriends()

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
                    
                    // Small delay to ensure Alice's poll sees it
                    try? await Task.sleep(nanoseconds: 500_000_000)
                    
                    try await locationClient.postOpkBundle(friendId: bobEntry.id)

                    // Trigger immediate location sync
                    if let last = LocationManager.shared.lastLocation {
                        try? await locationClient.sendLocationToFriend(friendId: bobEntry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude)
                    }
                    await pollAll()
                    
                    updateStatus(nil)
                } catch {
                    logger.error("Failed to post init: \(error.localizedDescription)")
                    updateStatus(error)
                }
            }
        }
    }

    func confirmPendingInit(name: String) {
        guard let payload = pendingInitPayload else { return }
        pendingInitPayload = nil
        hasPendingInit = false
        pendingInviteQr = nil
        e2eeStore.clearInvite()
        triggerRapidPoll()
        Task {
            // Small delay to ensure Bob has finished posting his initial OPKs/location
            try? await Task.sleep(nanoseconds: 500_000_000)
            
            _ = try? e2eeStore.processKeyExchangeInit(payload: payload, bobName: name)
            friends = e2eeStore.listFriends()
            
            // Trigger immediate location sync to ONLY the new friend
            if let entry = friends.first(where: { $0.name == name }), // Best effort to find the ID
               let last = LocationManager.shared.lastLocation {
                try? await locationClient.sendLocationToFriend(friendId: entry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude)
            }
            await pollAll()
        }
    }

    func cancelPendingInit() {
        pendingInitPayload = nil
        hasPendingInit = false
        pendingInviteQr = nil
        e2eeStore.clearInvite()
    }

    func togglePauseFriend(id: String) {
        if pausedFriendIds.contains(id) {
            pausedFriendIds.remove(id)
        } else {
            pausedFriendIds.insert(id)
        }
    }

    func removeFriend(id: String) {
        Task {
            e2eeStore.deleteFriend(id: id)
            friends = e2eeStore.listFriends()
            friendLocations.removeValue(forKey: id)
            pausedFriendIds.remove(id)
        }
    }

    // MARK: - Private polling

    private func pollAll() async {
        logger.debug("Polling for location updates")
        do {
            let updates = try await locationClient.poll()
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLastPing[update.userId] = Date()
            }
            updateStatus(nil)
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
        }
        await pollPendingInvite()
    }

    private func pollPendingInvite() async {
        guard let qr = e2eeStore.pendingQrPayload else { return }
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

            // Found init payload! Show naming dialog instead of processing immediately.
            autoClearedInvite = true
            pendingInitPayload = initPayload
            hasPendingInit = true
            pendingInviteQr = nil // Clear invite QR so the sheet dismisses
            triggerRapidPoll()
            break
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
