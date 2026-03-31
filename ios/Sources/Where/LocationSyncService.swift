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

// MARK: - E2EE payload serialization

private func encryptedLocationBody(epoch: Int32, seq: Int64, ct: Data) -> [String: Any] {
    return [
        "v": 1,
        "type": "EncryptedLocation",
        "epoch": Int(epoch),
        "seq": String(seq),
        "ct": ct.base64EncodedString(),
    ]
}

private func preKeyBundleBody(_ bundle: Shared.PreKeyBundlePayload) -> [String: Any] {
    let keys = bundle.keys.map { opk -> [String: Any] in
        ["id": opk.id, "pub": toSwiftData(opk.pub).base64EncodedString()]
    }
    return [
        "v": 1,
        "type": "PreKeyBundle",
        "keys": keys,
        "mac": toSwiftData(bundle.mac).base64EncodedString(),
    ]
}

private func epochRotationBody(_ payload: Shared.EpochRotationPayload) -> [String: Any] {
    return [
        "v": 1,
        "type": "EpochRotation",
        "epoch": payload.epoch,
        "opk_id": payload.opkId,
        "new_ek_pub": toSwiftData(payload.newEkPub).base64EncodedString(),
        "ts": payload.ts,
        "ct": toSwiftData(payload.ct).base64EncodedString(),
    ]
}

private func ratchetAckBody(_ payload: Shared.RatchetAckPayload) -> [String: Any] {
    return [
        "v": 1,
        "type": "RatchetAck",
        "epoch_seen": payload.epochSeen,
        "ts": payload.ts,
        "ct": toSwiftData(payload.ct).base64EncodedString(),
    ]
}

// JSONSerialization always produces `Int` (64-bit on iOS) for JSON integers,
// never `Int32` or `Int64` directly.  Cast to `Int` first, then narrow.
private func parseEpochRotation(_ msg: [String: Any]) -> Shared.EpochRotationPayload? {
    guard (msg["v"] as? Int) == 1,
          (msg["type"] as? String) == "EpochRotation",
          let epochInt = msg["epoch"] as? Int,
          let opkIdInt = msg["opk_id"] as? Int,
          let newEkPubB64 = msg["new_ek_pub"] as? String, let newEkPubData = Data(base64Encoded: newEkPubB64),
          let tsInt = msg["ts"] as? Int,
          let ctB64 = msg["ct"] as? String, let ctData = Data(base64Encoded: ctB64)
    else { return nil }
    return Shared.EpochRotationPayload(
        v: 1,
        epoch: Int32(epochInt), opkId: Int32(opkIdInt),
        newEkPub: kotlinByteArray(from: newEkPubData),
        ts: Int64(tsInt), ct: kotlinByteArray(from: ctData)
    )
}

private func parsePreKeyBundle(_ msg: [String: Any]) -> Shared.PreKeyBundlePayload? {
    guard (msg["v"] as? Int) == 1,
          (msg["type"] as? String) == "PreKeyBundle",
          let keysArr = msg["keys"] as? [[String: Any]],
          let macB64 = msg["mac"] as? String, let macData = Data(base64Encoded: macB64)
    else { return nil }
    var opkWires: [Shared.OPKWire] = []
    for k in keysArr {
        guard let idInt = k["id"] as? Int,
              let pubB64 = k["pub"] as? String,
              let pubData = Data(base64Encoded: pubB64)
        else { return nil }
        opkWires.append(Shared.OPKWire(id: Int32(idInt), pub: kotlinByteArray(from: pubData)))
    }
    return Shared.PreKeyBundlePayload(v: 1, keys: opkWires, mac: kotlinByteArray(from: macData))
}

private func parseRatchetAck(_ msg: [String: Any]) -> Shared.RatchetAckPayload? {
    guard (msg["v"] as? Int) == 1,
          (msg["type"] as? String) == "RatchetAck",
          let epochSeenInt = msg["epoch_seen"] as? Int,
          let tsInt = msg["ts"] as? Int,
          let ctB64 = msg["ct"] as? String, let ctData = Data(base64Encoded: ctB64)
    else { return nil }
    return Shared.RatchetAckPayload(v: 1, epochSeen: Int32(epochSeenInt), ts: Int64(tsInt), ct: kotlinByteArray(from: ctData))
}

private func parseEncryptedLocation(_ msg: [String: Any]) -> (epoch: Int32, seq: Int64, ct: Data)? {
    guard (msg["v"] as? Int) == 1,
          (msg["type"] as? String) == "EncryptedLocation",
          let epochInt = msg["epoch"] as? Int,
          let seqStr = msg["seq"] as? String,
          let seq = Int64(seqStr),
          let ctB64 = msg["ct"] as? String,
          let ctData = Data(base64Encoded: ctB64)
    else { return nil }
    return (Int32(epochInt), seq, ctData)
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
        let ts = Int64(Date().timeIntervalSince1970)
        let plaintext = Shared.LocationPlaintext(lat: lat, lng: lng, acc: 0.0, ts: ts)
        
        Task {
            let friendList = await e2eeStore.listFriends()
            for friend in friendList {
                if pausedFriendIds.contains(friend.id) { continue }

                // Alice: rotate epoch when due, before encrypting the next message.
                if await e2eeStore.shouldRotateEpoch(friendId: friend.id) {
                    let oldToken = toHex(friend.session.routingToken)
                    if let rotPayload = await e2eeStore.initiateEpochRotation(friendId: friend.id) {
                        let body = epochRotationBody(rotPayload)
                        if let bodyData = try? JSONSerialization.data(withJSONObject: body) {
                            await postToMailbox(token: oldToken, bodyData: bodyData)
                        }
                    }
                }

                // Re-fetch after potential rotation to use the current session/token.
                guard let current = await e2eeStore.getFriend(id: friend.id) else { continue }
                let result = Shared.Session.shared.encryptLocation(
                    state: current.session, location: plaintext,
                    senderFp: current.session.aliceFp, recipientFp: current.session.bobFp
                )
                let newSession = result.first!
                let ct = result.second!
                await e2eeStore.updateSession(id: friend.id, newSession: newSession)
                let hexToken = toHex(current.session.routingToken)
                let payload = encryptedLocationBody(epoch: newSession.epoch, seq: newSession.sendSeq, ct: toSwiftData(ct))
                if let bodyData = try? JSONSerialization.data(withJSONObject: payload) {
                    await postToMailbox(token: hexToken, bodyData: bodyData)
                }
            }
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
                await postOpkBundle(friend: bobEntry)
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

    // MARK: - Private helpers

    private func postOpkBundle(friend: Shared.FriendEntry) async {
        guard let bundle = await e2eeStore.generateOpkBundle(friendId: friend.id, count: 10) else { return }
        let hexToken = toHex(friend.session.routingToken)
        let body = preKeyBundleBody(bundle)
        if let bodyData = try? JSONSerialization.data(withJSONObject: body) {
            await postToMailbox(token: hexToken, bodyData: bodyData)
        }
    }

    // MARK: - Private polling

    private func pollAll() async {
        await pollFriendLocations()
        await pollPendingInvite()
    }

    private func pollFriendLocations() async {
        let friendList = await e2eeStore.listFriends()
        for friend in friendList {
            let hexToken = toHex(friend.session.routingToken)
            let messages = await pollMailbox(token: hexToken)
            let senderFp = friend.session.aliceFp
            let recipientFp = friend.session.bobFp

            // --- Epoch rotation first: changes session/token ---
            for msg in messages {
                guard let rotPayload = parseEpochRotation(msg) else { continue }
                if let ack = try? await e2eeStore.processEpochRotation(friendId: friend.id, payload: rotPayload) {
                    guard let newEntry = await e2eeStore.getFriend(id: friend.id) else { continue }
                    let newToken = toHex(newEntry.session.routingToken)
                    let body = ratchetAckBody(ack)
                    if let bodyData = try? JSONSerialization.data(withJSONObject: body) {
                        await postToMailbox(token: newToken, bodyData: bodyData)
                    }
                    await postOpkBundle(friend: newEntry)
                }
            }

            // --- Cache incoming OPK bundles ---
            for msg in messages {
                guard let bundle = parsePreKeyBundle(msg) else { continue }
                await e2eeStore.storeOpkBundle(friendId: friend.id, bundle: bundle)
            }

            // --- Decrypt location updates ---
            var session = (await e2eeStore.getFriend(id: friend.id) ?? friend).session
            var sessionChanged = false
            for msg in messages {
                guard let loc = parseEncryptedLocation(msg) else { continue }
                if loc.epoch != session.epoch { continue }

                let ct = kotlinByteArray(from: loc.ct)
                if let result = Shared.Session.shared.decryptLocation(
                    state: session, ct: ct, seq: loc.seq, senderFp: senderFp, recipientFp: recipientFp
                ) {
                    session = result.first!
                    let loc = result.second!
                    friendLocations[friend.id] = (lat: loc.lat, lng: loc.lng, ts: loc.ts)
                    sessionChanged = true
                }
            }
            if sessionChanged {
                await e2eeStore.updateSession(id: friend.id, newSession: session)
            }

            // --- Validate RatchetAck ---
            for msg in messages {
                guard let ack = parseRatchetAck(msg) else { continue }
                await e2eeStore.processRatchetAck(friendId: friend.id, payload: ack)
            }

            // --- Bob: proactively replenish OPKs if running low ---
            if await e2eeStore.shouldReplenishOpks(friendId: friend.id),
               let current = await e2eeStore.getFriend(id: friend.id) {
                await postOpkBundle(friend: current)
            }
        }
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
            e2eeStore.clearInvite()
            break
        }
    }
}
