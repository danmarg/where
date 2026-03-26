import Foundation
import Shared

enum IdentityKeyStore {
    static let shared: IdentityKeys = {
        let defaults = UserDefaults.standard
        let ikPrivKey = "where_ik_priv"
        let ikPubKey = "where_ik_pub"
        let sigPrivKey = "where_sig_priv"
        let sigPubKey = "where_sig_pub"

        if let ikPrivB64 = defaults.string(forKey: ikPrivKey),
           let ikPubB64 = defaults.string(forKey: ikPubKey),
           let sigPrivB64 = defaults.string(forKey: sigPrivKey),
           let sigPubB64 = defaults.string(forKey: sigPubKey),
           let ikPriv = Data(base64Encoded: ikPrivB64),
           let ikPub = Data(base64Encoded: ikPubB64),
           let sigPriv = Data(base64Encoded: sigPrivB64),
           let sigPub = Data(base64Encoded: sigPubB64)
        {
            return IdentityKeys(
                ik: RawKeyPair(priv: kotlinByteArray(from: ikPriv), pub: kotlinByteArray(from: ikPub)),
                sigIk: RawKeyPair(priv: kotlinByteArray(from: sigPriv), pub: kotlinByteArray(from: sigPub))
            )
        }
        let ik = generateX25519KeyPair()
        let sigIk = generateEd25519KeyPair()
        defaults.set(toSwiftData(ik.priv).base64EncodedString(), forKey: ikPrivKey)
        defaults.set(toSwiftData(ik.pub).base64EncodedString(), forKey: ikPubKey)
        defaults.set(toSwiftData(sigIk.priv).base64EncodedString(), forKey: sigPrivKey)
        defaults.set(toSwiftData(sigIk.pub).base64EncodedString(), forKey: sigPubKey)
        return IdentityKeys(ik: ik, sigIk: sigIk)
    }()
}
