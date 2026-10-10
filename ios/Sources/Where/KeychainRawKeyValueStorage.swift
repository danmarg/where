import Foundation
import Shared
import Security

final class KeychainRawKeyValueStorage: RawKeyValueStorage {
    private let service = "net.af0.where.e2ee"

    func getString(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)

        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    func putString(key: String, value: String) throws {
        guard let data = value.data(using: .utf8) else {
            throw NSError(domain: "KeychainRawKeyValueStorage", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "Failed to encode value as UTF-8"])
        }

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]

        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)

        if status == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            status = SecItemAdd(addQuery as CFDictionary, nil)
        }

        guard status == errSecSuccess else {
            throw NSError(domain: "KeychainRawKeyValueStorage", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "Keychain write failed: OSStatus \(status)"])
        }
    }
}

/// The device half of the database's device binding (see reconcileDeviceBinding). A
/// ThisDeviceOnly item never leaves the device in a backup or transfer.
final class DeviceMarkerKeychain: DeviceMarkerStore {
    private let service = "net.af0.where.e2ee"
    private let account = "device_binding_marker"

    /// "" only when the item definitely doesn't exist. Any other failure (e.g. the device is
    /// still locked after a reboot) throws, so it is never mistaken for a missing marker.
    func readMarker() throws -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = item as? Data, let marker = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "DeviceMarkerKeychain", code: Int(status),
                          userInfo: [NSLocalizedDescriptionKey: "Keychain read failed: OSStatus \(status)"])
        }
        return marker
    }

    func writeMarker(marker: String) throws {
        try KeychainRawKeyValueStorage().putString(key: account, value: marker)
    }
}
