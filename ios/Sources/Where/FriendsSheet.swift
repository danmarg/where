import Shared
import SwiftUI

func timeAgoString(_ date: Date?) -> String {
    guard let date = date else { return "never" }
    let seconds = Date().timeIntervalSince(date)
    if seconds < 60 { return "just now" }
    if seconds < 3600 { return "\(Int(seconds / 60))m ago" }
    if seconds < 86400 { return "\(Int(seconds / 3600))h ago" }
    return "\(Int(seconds / 86400))d ago"
}

struct FriendsSheet: View {
    @Binding var displayName: String
    let friends: [Shared.FriendEntry]
    let pausedFriendIds: Set<String>
    let lastPingTimes: [String: Date]
    let onTogglePause: (String) -> Void
    let onCreateInvite: () -> Void
    let onScanQr: () -> Void
    let onRename: (String, String) -> Void
    let onPasteUrl: (String) -> Void
    let onRemove: (String) -> Void
    let onZoomTo: (String) -> Void

    @State private var friendToRemove: Shared.FriendEntry? = nil
    @State private var friendToRename: Shared.FriendEntry? = nil
    @State private var newFriendName: String = ""
    @State private var showPasteField = false
    @State private var pastedUrl = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
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
                }

                if !friends.isEmpty {
                    Section("Friends (\(friends.count))") {
                        ForEach(friends, id: \.id) { friend in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(friend.isConfirmed ? friend.name : "\(friend.name) (Pending)")
                                        .font(.body)
                                    Text(friend.safetyNumber)
                                        .font(.caption2)
                                        .fontDesign(.monospaced)
                                        .foregroundStyle(.secondary)
                                    Text(timeAgoString(lastPingTimes[friend.id]))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    let ackStale = friend.isInitiator &&
                                        friend.lastAckTs != Int64.max &&
                                        Int64(Date().timeIntervalSince1970) - friend.lastAckTs > 7 * 24 * 3600
                                    if ackStale {
                                        Text("Not receiving acks — location sharing paused")
                                            .font(.caption)
                                            .foregroundStyle(.red)
                                    }
                                }
                                Spacer()
                                
                                Button {
                                    friendToRename = friend
                                    newFriendName = friend.name
                                } label: {
                                    Image(systemName: "pencil")
                                        .font(.title3)
                                        .foregroundStyle(.gray)
                                }
                                .buttonStyle(.plain)

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
            .alert("Rename Friend", isPresented: Binding(get: { friendToRename != nil }, set: { if !$0 { friendToRename = nil } })) {
                TextField("Friend's Name", text: $newFriendName)
                Button("Rename") {
                    if let friend = friendToRename {
                        onRename(friend.id, newFriendName)
                    }
                }
                Button("Cancel", role: .cancel) {
                    friendToRename = nil
                }
            }
        }
    }
}
