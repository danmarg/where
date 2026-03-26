import Shared
import SwiftUI

struct FriendsSheet: View {
    let myId: String
    let friends: [FriendEntry]
    let onCreateInvite: () -> Void
    let onScanQr: () -> Void
    let onRemove: (String) -> Void
    let onZoomTo: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
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
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { onZoomTo(friend.id) }
                            .swipeActions {
                                Button("Remove", role: .destructive) { onRemove(friend.id) }
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
        }
    }
}
