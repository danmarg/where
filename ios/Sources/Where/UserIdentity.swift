import Foundation

enum UserIdentity {
    static let userId: String = {
        let key = "where_user_id"
        if let saved = UserDefaults.standard.string(forKey: key) {
            return saved
        }
        let newId = UUID().uuidString
        UserDefaults.standard.set(newId, forKey: key)
        return newId
    }()
}
