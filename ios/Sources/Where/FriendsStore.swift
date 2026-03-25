import Foundation
import Combine

@MainActor
final class FriendsStore: ObservableObject {
    @Published private(set) var friendIds: Set<String> = []
    @Published var isSharingLocation: Bool {
        didSet { UserDefaults.standard.set(isSharingLocation, forKey: sharingKey) }
    }

    private let friendsKey = "where_friends"
    private let sharingKey = "where_is_sharing"

    init() {
        let saved = UserDefaults.standard.stringArray(forKey: friendsKey) ?? []
        friendIds = Set(saved)
        let savedSharing = UserDefaults.standard.object(forKey: sharingKey)
        isSharingLocation = savedSharing != nil ? UserDefaults.standard.bool(forKey: sharingKey) : true
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
