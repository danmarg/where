import Shared
import SwiftUI

struct FriendsSheet: View {
    @Binding var displayName: String
    let friends: [Shared.FriendEntry]
    let pendingInvites: [Shared.PendingInviteView]
    let pausedFriendIds: Set<String>
    let lastPingTimes: [String: Date]
    let onTogglePause: (String) -> Void
    let onCancelInvite: (Shared.PendingInviteView) -> Void
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
    @State private var debugExpandedFriendId: String? = nil

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
                        HStack(spacing: 12) {
                            Button {
                                onCreateInvite()
                            } label: {
                                Label(MR.strings().invite.localized(), systemImage: "qrcode")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)

                            Button {
                                onScanQr()
                            } label: {
                                Label(MR.strings().scan.localized(), systemImage: "qrcode.viewfinder")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }


                    }
                }

                if !friends.isEmpty {
                    Section(MR.strings().friends.localized() + " (\(friends.count))") {
                        ForEach(friends, id: \.id) { friend in
                            VStack(alignment: .leading, spacing: 0) {
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        let pendingText = "(" + MR.strings().pending.localized() + ")"
                                        let displayName = friend.isConfirmed ? friend.name : "\(friend.name) \(pendingText)"
                                        Text(displayName)
                                            .font(.body)
                                        Text(friend.safetyNumber)
                                            .font(.caption2)
                                            .fontDesign(.monospaced)
                                            .foregroundStyle(.secondary)
                                        Text(timeAgoString(lastPingTimes[friend.id]))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        if friend.isStale {
                                            Text(MR.strings().friend_inactive_warning.localized())
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
                                if debugExpandedFriendId == friend.id {
                                    FriendDebugView(friend: friend)
                                }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { onZoomTo(friend.id) }
                            .onLongPressGesture {
                                debugExpandedFriendId = debugExpandedFriendId == friend.id ? nil : friend.id
                            }
                            .swipeActions {
                                Button(MR.strings().remove.localized(), role: .destructive) {
                                    friendToRemove = friend
                                }
                            }
                        }
                    }
                } else {
                    Section {
                        Text(MR.strings().no_friends_yet.localized())
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                }
            }
            .navigationTitle(MR.strings().friends.localized())
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog(
                (MR.strings().remove_friend_title.localized()) + " (\(friendToRemove?.name ?? MR.strings().friend_.localized()))?",
                isPresented: Binding(get: { friendToRemove != nil }, set: { if !$0 { friendToRemove = nil } }),
                titleVisibility: .visible
            ) {
                Button(MR.strings().remove.localized(), role: .destructive) {
                    if let friend = friendToRemove {
                        onRemove(friend.id)
                    }
                }
            } message: {
                Text(MR.strings().permanently_delete_key_warning.localized())
            }
            .alert(MR.strings().rename_friend_title.localized(), isPresented: Binding(get: { friendToRename != nil }, set: { if !$0 { friendToRename = nil } })) {
                TextField(MR.strings().friend_name_label.localized(), text: $newFriendName)
                Button(MR.strings().rename.localized()) {
                    if let friend = friendToRename {
                        onRename(friend.id, newFriendName)
                    }
                }
                Button(MR.strings().cancel.localized(), role: .cancel) {
                    friendToRename = nil
                }
            }
        }
    }
}

private struct FriendDebugView: View {
    let friend: Shared.FriendEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            let recvTs = friend.lastRecvTs
            let sentTs = friend.lastSentTs
            let recvToken = toHex(friend.session.recvToken).prefix(8)
            let sendToken = toHex(friend.session.sendToken).prefix(8)
            Text("recv: \(timeAgoStringFromSeconds(recvTs))  sent: \(timeAgoStringFromSeconds(sentTs))")
            Text("recvTok: \(recvToken)  sendTok: \(sendToken)")
            Text("needsRatchet: \(friend.session.needsRatchet ? "YES" : "no")  sendPending: \(friend.session.isSendTokenPending ? "YES" : "no")")
        }
        .font(.caption2)
        .fontDesign(.monospaced)
        .foregroundStyle(.secondary)
        .padding(.vertical, 2)
    }
}
