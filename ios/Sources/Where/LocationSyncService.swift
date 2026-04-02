import Foundation
import Shared

// MARK: - QR payload URL helpers

func qrPayloadToUrl(_ qr: Shared.QrPayload) -> String {
    let dict: [String: Any] = [
        "ekPub": toSwiftData(qr.ekPub).base64EncodedString(),
        "suggestedName": qr.suggestedName,
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
          let ekPub = (dict["ekPub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          let name = dict["suggestedName"] as? String,
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
private func postToMailbox(token: String, bodyData: Data) async {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return }
    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = bodyData
    _ = try? await URLSession.shared.data(for: req)
}

@MainActor
private func pollMailbox(token: String) async -> [[String: Any]] {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return [] }
    guard let (data, _) = try? await URLSession.shared.data(from: url),
          let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
    else { return [] }
    return arr
}

// MARK: - LocationSyncService

@MainActor
final class LocationSyncService: ObservableObject {
    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
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
        let store = Shared.E2eeStore(storage: UserDefaultsE2eeStorage())
        self.e2eeStore = store
        self.locationClient = Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)

        let savedSharing = UserDefaults.standard.object(forKey: "where_is_sharing")
        isSharingLocation = savedSharing != nil ? UserDefaults.standard.bool(forKey: "where_is_sharing") : true

        displayName = UserDefaults.standard.string(forKey: "display_name") ?? ""

        let savedPaused = UserDefaults.standard.stringArray(forKey: "paused_friends") ?? []
        pausedFriendIds = Set(savedPaused)

        Task {
            friends = await e2eeStore.listFriends()
        }
        startPolling()
    }

    func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.pollAll()
                try? await Task.sleep(nanoseconds: 60_000_000_000)
            }
        }
    }

    func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    func sendLocation(lat: Double, lng: Double) {
        guard isSharingLocation else { return }
        Task {
            _ = try? await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds)
        }
    }

    func createInvite() {
        Task {
            let qr = await e2eeStore.createInvite(suggestedName: displayName.isEmpty ? "Me" : displayName)
            pendingInviteQr = qr
        }
    }

    func clearInvite() {
        Task {
            await e2eeStore.clearInvite()
            pendingInviteQr = nil
        }
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = urlToQrPayload(url) else { return false }
        pendingQrForNaming = qr
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) {
        pendingQrForNaming = nil
        let qrWithName = Shared.QrPayload(
            ekPub: qr.ekPub,
            suggestedName: friendName,
            fingerprint: qr.fingerprint
        )
        Task {
            guard let result = try? await e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName) else { return }
            let initPayload = result.first!
            let bobEntry = result.second!
            friends = await e2eeStore.listFriends()

            let discoveryHex = toHex(qrWithName.discoveryToken())
            let payload: [String: Any] = [
                "v": 1,
                "type": "KeyExchangeInit",
                "token": initPayload.token,
                "ekPub": toSwiftData(initPayload.ekPub).base64EncodedString(),
                "key_confirmation": toSwiftData(initPayload.keyConfirmation).base64EncodedString(),
                "suggested_name": initPayload.suggestedName,
            ]

            if let bodyData = try? JSONSerialization.data(withJSONObject: payload) {
                await postToMailbox(token: discoveryHex, bodyData: bodyData)
                _ = try? await locationClient.postOpkBundle(friendId: bobEntry.id)
            }
        }
    }

    func confirmPendingInit(name: String) {
        guard let payload = pendingInitPayload else { return }
        pendingInitPayload = nil
        hasPendingInit = false
        Task {
            _ = try? await e2eeStore.processKeyExchangeInit(payload: payload, bobName: name)
            friends = await e2eeStore.listFriends()
        }
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
            await e2eeStore.deleteFriend(id: id)
            friends = await e2eeStore.listFriends()
            friendLocations.removeValue(forKey: id)
            pausedFriendIds.remove(id)
        }
    }

    // MARK: - Private polling

    private func pollAll() async {
        let updates = (try? await locationClient.poll()) ?? []
        for update in updates {
            friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
        }
        await pollPendingInvite()
    }

    private func pollPendingInvite() async {
        guard let qr = await e2eeStore.pendingQrPayload else { return }
        let discoveryHex = toHex(qr.discoveryToken())
        let messages = await pollMailbox(token: discoveryHex)
        for msg in messages {
            guard (msg["v"] as? Int) == 1,
                  (msg["type"] as? String) == "KeyExchangeInit",
                  let token = msg["token"] as? String,
                  let ekPubB64 = msg["ekPub"] as? String, let ekPub = Data(base64Encoded: ekPubB64),
                  let keyConfB64 = msg["key_confirmation"] as? String, let keyConfData = Data(base64Encoded: keyConfB64)
            else { continue }
            let suggestedName = msg["suggested_name"] as? String ?? ""
            let initPayload = Shared.KeyExchangeInitPayload(
                v: 1,
                token: token,
                ekPub: kotlinByteArray(from: ekPub),
                keyConfirmation: kotlinByteArray(from: keyConfData),
                suggestedName: suggestedName
            )

            // Found init payload! Show naming dialog instead of processing immediately.
            pendingInitPayload = initPayload
            hasPendingInit = true
            pendingInviteQr = nil
            await e2eeStore.clearInvite()
            break
        }
    }
}
