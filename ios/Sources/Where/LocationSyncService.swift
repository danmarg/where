import Foundation
import Shared

// MARK: - QR payload URL helpers

private func qrPayloadToUrl(_ qr: QrPayload) -> String {
    let dict: [String: Any] = [
        "ikPub": toSwiftData(qr.ikPub).base64EncodedString(),
        "ekPub": toSwiftData(qr.ekPub).base64EncodedString(),
        "sigPub": toSwiftData(qr.sigPub).base64EncodedString(),
        "suggestedName": qr.suggestedName,
        "fingerprint": qr.fingerprint,
        "sig": toSwiftData(qr.sig).base64EncodedString(),
    ]
    let jsonData = try! JSONSerialization.data(withJSONObject: dict)
    let b64 = jsonData.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
    return "where://invite?q=\(b64)"
}

private func urlToQrPayload(_ url: String) -> QrPayload? {
    guard let q = URLComponents(string: url)?.queryItems?.first(where: { $0.name == "q" })?.value else { return nil }
    var b64 = q.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while b64.count % 4 != 0 { b64 += "=" }
    guard let data = Data(base64Encoded: b64),
          let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let ikPub = (dict["ikPub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          let ekPub = (dict["ekPub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          let sigPub = (dict["sigPub"] as? String).flatMap({ Data(base64Encoded: $0) }),
          let name = dict["suggestedName"] as? String,
          let fp = dict["fingerprint"] as? String,
          let sig = (dict["sig"] as? String).flatMap({ Data(base64Encoded: $0) })
    else { return nil }
    return QrPayload(
        ikPub: kotlinByteArray(from: ikPub),
        ekPub: kotlinByteArray(from: ekPub),
        sigPub: kotlinByteArray(from: sigPub),
        suggestedName: name,
        fingerprint: fp,
        sig: kotlinByteArray(from: sig)
    )
}

// MARK: - HTTP mailbox helpers

private func postToMailbox(token: String, body: [String: Any]) async {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)"),
          let bodyData = try? JSONSerialization.data(withJSONObject: body)
    else { return }
    var req = URLRequest(url: url)
    req.httpMethod = "POST"
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = bodyData
    _ = try? await URLSession.shared.data(for: req)
}

private func pollMailbox(token: String) async -> [[String: Any]] {
    guard let url = URL(string: "\(ServerConfig.httpBaseUrl)/inbox/\(token)") else { return [] }
    guard let (data, _) = try? await URLSession.shared.data(from: url),
          let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
    else { return [] }
    return arr
}

// MARK: - Wire serialization helpers for new payload types

private func preKeyBundleBody(_ bundle: PreKeyBundlePayload) -> [String: Any] {
    let keys = bundle.keys.map { opk -> [String: Any] in
        ["id": opk.id, "pub": toSwiftData(opk.pub).base64EncodedString()]
    }
    return [
        "type": "PreKeyBundle",
        "keys": keys,
        "sig": toSwiftData(bundle.sig).base64EncodedString(),
    ]
}

private func epochRotationBody(_ payload: EpochRotationPayload) -> [String: Any] {
    return [
        "type": "EpochRotation",
        "epoch": payload.epoch,
        "opk_id": payload.opkId,
        "new_ek_pub": toSwiftData(payload.newEkPub).base64EncodedString(),
        "ts": payload.ts,
        "sig": toSwiftData(payload.sig).base64EncodedString(),
    ]
}

private func ratchetAckBody(_ payload: RatchetAckPayload) -> [String: Any] {
    return [
        "type": "RatchetAck",
        "epoch_seen": payload.epochSeen,
        "ts": payload.ts,
        "sig": toSwiftData(payload.sig).base64EncodedString(),
    ]
}

private func parseEpochRotation(_ msg: [String: Any]) -> EpochRotationPayload? {
    guard (msg["type"] as? String) == "EpochRotation",
          let epoch = msg["epoch"] as? Int32,
          let opkId = msg["opk_id"] as? Int32,
          let newEkPubB64 = msg["new_ek_pub"] as? String, let newEkPubData = Data(base64Encoded: newEkPubB64),
          let ts = msg["ts"] as? Int64,
          let sigB64 = msg["sig"] as? String, let sigData = Data(base64Encoded: sigB64)
    else { return nil }
    return EpochRotationPayload(
        epoch: epoch, opkId: opkId,
        newEkPub: kotlinByteArray(from: newEkPubData),
        ts: ts, sig: kotlinByteArray(from: sigData)
    )
}

private func parsePreKeyBundle(_ msg: [String: Any]) -> PreKeyBundlePayload? {
    guard (msg["type"] as? String) == "PreKeyBundle",
          let keysArr = msg["keys"] as? [[String: Any]],
          let sigB64 = msg["sig"] as? String, let sigData = Data(base64Encoded: sigB64)
    else { return nil }
    var opkWires: [OPKWire] = []
    for k in keysArr {
        guard let id = k["id"] as? Int32,
              let pubB64 = k["pub"] as? String,
              let pubData = Data(base64Encoded: pubB64)
        else { return nil }
        opkWires.append(OPKWire(id: id, pub: kotlinByteArray(from: pubData)))
    }
    return PreKeyBundlePayload(keys: opkWires, sig: kotlinByteArray(from: sigData))
}

private func parseRatchetAck(_ msg: [String: Any]) -> RatchetAckPayload? {
    guard (msg["type"] as? String) == "RatchetAck",
          let epochSeen = msg["epoch_seen"] as? Int32,
          let ts = msg["ts"] as? Int64,
          let sigB64 = msg["sig"] as? String, let sigData = Data(base64Encoded: sigB64)
    else { return nil }
    return RatchetAckPayload(epochSeen: epochSeen, ts: ts, sig: kotlinByteArray(from: sigData))
}

// MARK: - LocationSyncService

@MainActor
final class LocationSyncService: ObservableObject {
    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
    @Published var friends: [FriendEntry] = []
    @Published var pendingInviteQr: QrPayload? = nil
    @Published var isSharingLocation: Bool {
        didSet { UserDefaults.standard.set(isSharingLocation, forKey: "where_is_sharing") }
    }

    private let identityKeys: IdentityKeys
    let e2eeStore: E2eeStore
    private var pollTask: Task<Void, Never>?

    var myId: String {
        let fp = identityFingerprint(ikPub: identityKeys.ik.pub, sigIkPub: identityKeys.sigIk.pub)
        return String(toHex(fp).prefix(20))
    }

    init() {
        identityKeys = IdentityKeyStore.shared
        e2eeStore = E2eeStore(storage: UserDefaultsE2eeStorage(), myIdentity: identityKeys)
        let saved = UserDefaults.standard.object(forKey: "where_is_sharing")
        isSharingLocation = saved != nil ? UserDefaults.standard.bool(forKey: "where_is_sharing") : true
        friends = e2eeStore.listFriends() as! [FriendEntry]
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
        let plaintext = LocationPlaintext(lat: lat, lng: lng, acc: 0.0, ts: ts)
        let myFp = identityFingerprint(ikPub: identityKeys.ik.pub, sigIkPub: identityKeys.sigIk.pub)
        let friendList = e2eeStore.listFriends() as! [FriendEntry]
        for friend in friendList {
            let friendFp = identityFingerprint(ikPub: friend.ikPub, sigIkPub: friend.sigIkPub)

            // Alice: rotate epoch when due, before encrypting the next message.
            if e2eeStore.shouldRotateEpoch(friendId: friend.id) {
                let oldToken = toHex(friend.session.routingToken)
                if let rotPayload = e2eeStore.initiateEpochRotation(friendId: friend.id) {
                    Task { await postToMailbox(token: oldToken, body: epochRotationBody(rotPayload)) }
                }
            }

            // Re-fetch after potential rotation to use the current session/token.
            guard let current = e2eeStore.getFriend(id: friend.id) else { continue }
            guard let result = Session.shared.encryptLocation(
                state: current.session, location: plaintext, senderFp: myFp, recipientFp: friendFp
            ) else { continue }
            let newSession = result.first as! SessionState
            let ct = result.second as! KotlinByteArray
            e2eeStore.updateSession(id: friend.id, newSession: newSession)
            let hexToken = toHex(current.session.routingToken)
            let payload: [String: Any] = [
                "type": "EncryptedLocation",
                "epoch": Int(newSession.epoch),
                "seq": String(newSession.sendSeq),
                "ct": toSwiftData(ct).base64EncodedString(),
            ]
            Task { await postToMailbox(token: hexToken, body: payload) }
        }
    }

    func createInvite() {
        let qr = e2eeStore.createInvite(suggestedName: "Me")
        pendingInviteQr = qr
    }

    func clearInvite() {
        e2eeStore.clearInvite()
        pendingInviteQr = nil
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = urlToQrPayload(url) else { return false }
        guard let result = try? e2eeStore.processScannedQr(qr: qr) else { return false }
        let initPayload = result.first as! KeyExchangeInitPayload
        let bobEntry = result.second as! FriendEntry
        friends = e2eeStore.listFriends() as! [FriendEntry]
        // POST KeyExchangeInit to discovery token so Alice can find it.
        let discoveryHex = toHex(qr.discoveryToken())
        let payload: [String: Any] = [
            "type": "KeyExchangeInit",
            "token": initPayload.token,
            "ikPub": toSwiftData(initPayload.ikPub).base64EncodedString(),
            "ekPub": toSwiftData(initPayload.ekPub).base64EncodedString(),
            "sigPub": toSwiftData(initPayload.sigPub).base64EncodedString(),
            "sig": toSwiftData(initPayload.sig).base64EncodedString(),
        ]
        Task {
            await postToMailbox(token: discoveryHex, body: payload)
            // Bob posts initial OPK bundle so Alice can initiate epoch rotation.
            await postOpkBundle(friend: bobEntry)
        }
        return true
    }

    // MARK: - Private helpers

    private func postOpkBundle(friend: FriendEntry) async {
        guard let bundle = e2eeStore.generateOpkBundle(friendId: friend.id, count: Int32(E2eeStore.companion.OPK_BATCH_SIZE)) else { return }
        let hexToken = toHex(friend.session.routingToken)
        await postToMailbox(token: hexToken, body: preKeyBundleBody(bundle))
    }

    // MARK: - Private polling

    private func pollAll() async {
        await pollFriendLocations()
        await pollPendingInvite()
    }

    private func pollFriendLocations() async {
        let myFp = identityFingerprint(ikPub: identityKeys.ik.pub, sigIkPub: identityKeys.sigIk.pub)
        let friendList = e2eeStore.listFriends() as! [FriendEntry]
        for friend in friendList {
            let hexToken = toHex(friend.session.routingToken)
            let messages = await pollMailbox(token: hexToken)
            let friendFp = identityFingerprint(ikPub: friend.ikPub, sigIkPub: friend.sigIkPub)

            // --- Epoch rotation first: changes session/token ---
            for msg in messages {
                guard let rotPayload = parseEpochRotation(msg) else { continue }
                guard let ack = try? e2eeStore.processEpochRotation(friendId: friend.id, payload: rotPayload),
                      let ack = ack else { continue }
                guard let newEntry = e2eeStore.getFriend(id: friend.id) else { continue }
                let newToken = toHex(newEntry.session.routingToken)
                await postToMailbox(token: newToken, body: ratchetAckBody(ack))
                await postOpkBundle(friend: newEntry)
            }

            // --- Cache incoming OPK bundles ---
            for msg in messages {
                guard let bundle = parsePreKeyBundle(msg) else { continue }
                e2eeStore.storeOpkBundle(friendId: friend.id, bundle: bundle)
            }

            // --- Decrypt location updates ---
            var session = (e2eeStore.getFriend(id: friend.id) ?? friend).session
            var sessionChanged = false
            for msg in messages {
                guard (msg["type"] as? String) == "EncryptedLocation",
                      let seqStr = msg["seq"] as? String,
                      let seq = Int64(seqStr),
                      let ctB64 = msg["ct"] as? String,
                      let ctData = Data(base64Encoded: ctB64)
                else { continue }
                let ct = kotlinByteArray(from: ctData)
                guard let result = Session.shared.decryptLocation(
                    state: session, ct: ct, seq: seq, senderFp: friendFp, recipientFp: myFp
                ) else { continue }
                session = result.first as! SessionState
                let loc = result.second as! LocationPlaintext
                friendLocations[friend.id] = (lat: loc.lat, lng: loc.lng, ts: loc.ts)
                sessionChanged = true
            }
            if sessionChanged {
                e2eeStore.updateSession(id: friend.id, newSession: session)
            }

            // --- Validate RatchetAck ---
            for msg in messages {
                guard let ack = parseRatchetAck(msg) else { continue }
                e2eeStore.processRatchetAck(friendId: friend.id, payload: ack)
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
            guard (msg["type"] as? String) == "KeyExchangeInit",
                  let token = msg["token"] as? String,
                  let ikPubB64 = msg["ikPub"] as? String, let ikPub = Data(base64Encoded: ikPubB64),
                  let ekPubB64 = msg["ekPub"] as? String, let ekPub = Data(base64Encoded: ekPubB64),
                  let sigPubB64 = msg["sigPub"] as? String, let sigPub = Data(base64Encoded: sigPubB64),
                  let sigB64 = msg["sig"] as? String, let sig = Data(base64Encoded: sigB64)
            else { continue }
            let initPayload = KeyExchangeInitPayload(
                token: token,
                ikPub: kotlinByteArray(from: ikPub),
                ekPub: kotlinByteArray(from: ekPub),
                sigPub: kotlinByteArray(from: sigPub),
                sig: kotlinByteArray(from: sig)
            )
            // processKeyExchangeInit throws IllegalArgumentException on bad signature.
            guard let result = try? e2eeStore.processKeyExchangeInit(payload: initPayload, bobName: "Friend"),
                  result != nil else { continue }
            pendingInviteQr = nil
            friends = e2eeStore.listFriends() as! [FriendEntry]
            break
        }
    }
}
