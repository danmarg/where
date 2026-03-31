import Shared
import SwiftUI

struct FriendsSheet: View {
    let myId: String
    @Binding var displayName: String
    let friends: [Shared.FriendEntry]
    let pausedFriendIds: Set<String>
    let onTogglePause: (String) -> Void
    let onCreateInvite: () -> Void
    let onScanQr: () -> Void
    let onRemove: (String) -> Void
    let onZoomTo: (String) -> Void

    @State private var friendToRemove: Shared.FriendEntry? = nil

    var body: some View {
        NavigationStack {
            List {
                Section("Your Name") {
                    TextField("Alice", text: $displayName)
                }

                Section("Your ID") {
                    Text(myId)
                        .font(.caption)
                        .fontDesign(.monospaced)
                        .foregroundStyle(.secondary)
                }

                Section {
                    HStack(spacing: 12) {
                        Button {
                            onCreateInvite()
                        } label: {
                            Label("Invite", systemImage: "qrcode")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)

                        Button {
                            onScanQr()
                        } label: {
                            Label("Scan", systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }

                if !friends.isEmpty {
                    Section("Friends (\(friends.count))") {
                        ForEach(friends, id: \.id) { friend in
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(friend.name)
                                        .font(.body)
                                    Text(friend.id.prefix(8))
                                        .font(.caption)
                                        .fontDesign(.monospaced)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                
                                let isPaused = pausedFriendIds.contains(friend.id)
                                Button {
                                    onTogglePause(friend.id)
                                } label: {
                                    Image(systemName: isPaused ? "play.circle" : "pause.circle")
                                        .font(.title3)
                                        .foregroundStyle(isPaused ? .blue : .gray)
                                }
                                .buttonStyle(.plain)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { onZoomTo(friend.id) }
                            .swipeActions {
                                Button("Remove", role: .destructive) { 
                                    friendToRemove = friend
                                }
                            }
                        }
                    }
                } else {
                    Section {
                        Text("No friends yet. Tap Invite to share your QR code.")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                }
            }
            .navigationTitle("Friends")
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog(
                "Remove \(friendToRemove?.name ?? "friend")?",
                isPresented: Binding(get: { friendToRemove != nil }, set: { if !$0 { friendToRemove = nil } }),
                titleVisibility: .visible
            ) {
                Button("Remove", role: .destructive) {
                    if let friend = friendToRemove {
                        onRemove(friend.id)
                    }
                }
            } message: {
                Text("This will permanently delete the key.")
            }
        }
    }
}
