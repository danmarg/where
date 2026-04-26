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

extension Data {
    func toKotlinByteArray() -> Shared.KotlinByteArray {
        kotlinByteArray(from: self)
    }

    /// Securely zeros out the buffer using memset_s to prevent compiler elision.
    /// This is critical for sensitive data like cryptographic keys.
    mutating func zeroize() {
        withUnsafeMutableBytes { ptr in
            guard let base = ptr.baseAddress else { return }
            memset_s(base, ptr.count, 0, ptr.count)
        }
    }
}

extension Shared.KotlinByteArray {
    func toData() -> Data {
        toSwiftData(self)
    }

    func toHex() -> String {
        Where.toHex(self)
    }
}

func identityFingerprint(ikPub: Shared.KotlinByteArray, sigIkPub: Shared.KotlinByteArray) -> Shared.KotlinByteArray {
    var hasher = SHA256()
    var ikPubData = toSwiftData(ikPub)
    var sigIkPubData = toSwiftData(sigIkPub)
    defer {
        ikPubData.zeroize()
        sigIkPubData.zeroize()
    }
    hasher.update(data: ikPubData)
    hasher.update(data: sigIkPubData)
    return kotlinByteArray(from: Data(hasher.finalize()))
}
