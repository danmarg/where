import CryptoKit
import Foundation
import Shared

func kotlinByteArray(from data: Data) -> Shared.KotlinByteArray {
    let ba = Shared.KotlinByteArray(size: Int32(data.count))
    for (i, byte) in data.enumerated() {
        ba.set(index: Int32(i), value: Int8(bitPattern: byte))
    }
    return ba
}

func toSwiftData(_ kba: Shared.KotlinByteArray) -> Data {
    Data((0..<Int(kba.size)).map { UInt8(bitPattern: kba.get(index: Int32($0))) })
}

func toHex(_ kba: Shared.KotlinByteArray) -> String {
    (0..<Int(kba.size)).map { String(format: "%02x", UInt8(bitPattern: kba.get(index: Int32($0)))) }.joined()
}

func identityFingerprint(ikPub: Shared.KotlinByteArray, sigIkPub: Shared.KotlinByteArray) -> Shared.KotlinByteArray {
    var hasher = SHA256()
    hasher.update(data: toSwiftData(ikPub))
    hasher.update(data: toSwiftData(sigIkPub))
    return kotlinByteArray(from: Data(hasher.finalize()))
}
