import Foundation
import Shared

final class UserDefaultsE2eeStorage: E2eeStorage {
    func getString(key: String) -> String? {
        UserDefaults.standard.string(forKey: key)
    }

    func putString(key: String, value: String) {
        UserDefaults.standard.set(value, forKey: key)
    }
}
