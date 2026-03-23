import Foundation
import Combine

@MainActor
final class FriendsStore: ObservableObject {
    @Published private(set) var friendIds: Set<String> = []
    @Published var isSharingLocation: Bool = true

    private let friendsKey = "where_friends"

    init() {
        let saved = UserDefaults.standard.stringArray(forKey: friendsKey) ?? []
        friendIds = Set(saved)
    }

    func add(id: String) {
        let trimmed = id.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != UserIdentity.userId else { return }
        friendIds.insert(trimmed)
        persist()
    }

    func remove(id: String) {
        friendIds.remove(id)
        persist()
    }

    private func persist() {
        UserDefaults.standard.set(Array(friendIds), forKey: friendsKey)
    }
}
