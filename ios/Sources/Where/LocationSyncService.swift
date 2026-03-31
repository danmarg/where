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
private func postToMailbox(token: String, payload: Shared.MailboxPayload) async {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return }
    let jsonString = Shared.LocationMessageCodec.shared.encodeMailboxPayload(payload: payload)
    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = jsonString.data(using: .utf8)
    _ = try? await URLSession.shared.data(for: req)
}

@MainActor
private func pollMailbox(token: String) async -> [Shared.MailboxPayload] {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return [] }
    guard let (data, _) = try? await URLSession.shared.data(from: url),
          let jsonString = String(data: data, encoding: .utf8)
    else { return [] }
    return Shared.LocationMessageCodec.shared.decodeMailboxPayloads(text: jsonString) ?? []
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
    private var pollTask: Task<Void, Never>?

    let myId: String = {
        let key = "where_user_id"
        if let id = UserDefaults.standard.string(forKey: key) { return id }
        let id = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        UserDefaults.standard.set(id, forKey: key)
        return id
    }()

    init() {
        e2eeStore = Shared.E2eeStore(storage: UserDefaultsE2eeStorage())

        let savedSharing = UserDefaults.standard.object(forKey: "where_is_sharing")
        isSharingLocation = savedSharing != nil ? UserDefaults.standard.bool(forKey: "where_is_sharing") : true

        displayName = UserDefaults.standard.string(forKey: "display_name") ?? ""

        let savedPaused = UserDefaults.standard.stringArray(forKey: "paused_friends") ?? []
        pausedFriendIds = Set(savedPaused)

        friends = e2eeStore.listFriends()
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
        let ts = Int64(Date().timeIntervalSince1970)
        let plaintext = Shared.LocationPlaintext(lat: lat, lng: lng, acc: 0.0, ts: ts)
        let friendList = e2eeStore.listFriends()
        
        Task {
            for friend in friendList {
                if pausedFriendIds.contains(friend.id) { continue }

                // Alice: rotate epoch when due, before encrypting the next message.
                if e2eeStore.shouldRotateEpoch(friendId: friend.id) {
                    let oldToken = toHex(friend.session.routingToken)
                    if let rotPayload = e2eeStore.initiateEpochRotation(friendId: friend.id) {
                        await postToMailbox(token: oldToken, payload: rotPayload)
                    }
                }

                // Re-fetch after potential rotation to use the current session/token.
                guard let current = e2eeStore.getFriend(id: friend.id) else { continue }
                let result = Shared.Session.shared.encryptLocation(
                    state: current.session, location: plaintext,
                    senderFp: current.session.aliceFp, recipientFp: current.session.bobFp
                )
                let newSession = result.first!
                let ct = result.second!
                e2eeStore.updateSession(id: friend.id, newSession: newSession)
                let hexToken = toHex(current.session.routingToken)
                let payload = Shared.EncryptedLocationPayload(
                    epoch: newSession.epoch,
                    seq: String(newSession.sendSeq),
                    ct: ct
                )
                await postToMailbox(token: hexToken, payload: payload)
            }
        }
    }

    func createInvite() {
        let qr = e2eeStore.createInvite(suggestedName: displayName.isEmpty ? "Me" : displayName)
        pendingInviteQr = qr
    }

    func clearInvite() {
        e2eeStore.clearInvite()
        pendingInviteQr = nil
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
        guard let result = try? e2eeStore.processScannedQr(qr: qrWithName, bobSuggestedName: displayName) else { return }
        let initPayload = result.first!
        let bobEntry = result.second!
        friends = e2eeStore.listFriends()

        let discoveryHex = toHex(qrWithName.discoveryToken())
        let payload = Shared.KeyExchangeInitPayload(
            token: initPayload.token,
            ekPub: initPayload.ekPub,
            keyConfirmation: initPayload.keyConfirmation,
            suggestedName: initPayload.suggestedName
        )

        Task {
            await postToMailbox(token: discoveryHex, payload: payload)
            await postOpkBundle(friend: bobEntry)
        }
    }

    func confirmPendingInit(name: String) {
        guard let payload = pendingInitPayload else { return }
        pendingInitPayload = nil
        hasPendingInit = false
        _ = try? e2eeStore.processKeyExchangeInit(payload: payload, bobName: name)
        friends = e2eeStore.listFriends()
    }

    func togglePauseFriend(id: String) {
        if pausedFriendIds.contains(id) {
            pausedFriendIds.remove(id)
        } else {
            pausedFriendIds.insert(id)
        }
    }

    func removeFriend(id: String) {
        e2eeStore.deleteFriend(id: id)
        friends = e2eeStore.listFriends()
        friendLocations.removeValue(forKey: id)
        pausedFriendIds.remove(id)
    }

    // MARK: - Private helpers

    private func postOpkBundle(friend: Shared.FriendEntry) async {
        guard let bundle = e2eeStore.generateOpkBundle(friendId: friend.id, count: 10) else { return }
        let hexToken = toHex(friend.session.routingToken)
        await postToMailbox(token: hexToken, payload: bundle)
    }

    // MARK: - Private polling

    private func pollAll() async {
        await pollFriendLocations()
        await pollPendingInvite()
    }

    private func pollFriendLocations() async {
        let friendList = e2eeStore.listFriends()
        for friend in friendList {
            let hexToken = toHex(friend.session.routingToken)
            let messages = await pollMailbox(token: hexToken)
            let senderFp = friend.session.aliceFp
            let recipientFp = friend.session.bobFp

            // --- Epoch rotation first: changes session/token ---
            for msg in messages {
                guard let rotPayload = msg as? Shared.EpochRotationPayload else { continue }
                if let ack = try? e2eeStore.processEpochRotation(friendId: friend.id, payload: rotPayload) {
                    guard let newEntry = e2eeStore.getFriend(id: friend.id) else { continue }
                    let newToken = toHex(newEntry.session.routingToken)
                    await postToMailbox(token: newToken, payload: ack)
                    await postOpkBundle(friend: newEntry)
                }
            }

            // --- Cache incoming OPK bundles ---
            for msg in messages {
                guard let bundle = msg as? Shared.PreKeyBundlePayload else { continue }
                _ = e2eeStore.storeOpkBundle(friendId: friend.id, bundle: bundle)
            }

            // --- Decrypt location updates ---
            var session = (e2eeStore.getFriend(id: friend.id) ?? friend).session
            var sessionChanged = false
            for msg in messages {
                guard let locPayload = msg as? Shared.EncryptedLocationPayload,
                      let seq = Int64(locPayload.seq)
                else { continue }
                if let result = Shared.Session.shared.decryptLocation(
                    state: session, ct: locPayload.ct, seq: seq, senderFp: senderFp, recipientFp: recipientFp
                ) {
                    session = result.first!
                    let loc = result.second!
                    friendLocations[friend.id] = (lat: loc.lat, lng: loc.lng, ts: loc.ts)
                    sessionChanged = true
                }
            }
            if sessionChanged {
                e2eeStore.updateSession(id: friend.id, newSession: session)
            }

            // --- Validate RatchetAck ---
            for msg in messages {
                guard let ack = msg as? Shared.RatchetAckPayload else { continue }
                _ = e2eeStore.processRatchetAck(friendId: friend.id, payload: ack)
            }

            // --- Bob: proactively replenish OPKs if running low ---
            if e2eeStore.shouldReplenishOpks(friendId: friend.id),
               let current = e2eeStore.getFriend(id: friend.id) {
                await postOpkBundle(friend: current)
            }
        }
    }

    private func pollPendingInvite() async {
        guard let qr = e2eeStore.pendingQrPayload else { return }
        let discoveryHex = toHex(qr.discoveryToken())
        let messages = await pollMailbox(token: discoveryHex)
        for msg in messages {
            guard let initPayload = msg as? Shared.KeyExchangeInitPayload else { continue }

            // Found init payload! Show naming dialog instead of processing immediately.
            pendingInitPayload = initPayload
            hasPendingInit = true
            pendingInviteQr = nil
            e2eeStore.clearInvite()
            break
        }
    }
}
