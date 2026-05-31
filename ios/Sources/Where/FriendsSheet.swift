import Shared
import SwiftUI

struct FriendsSheet: View {
    @Binding var displayName: String
    let friends: [Shared.FriendEntry]
    let pendingInvites: [Shared.PendingInviteView]
    let pausedFriendIds: Set<String>
    let lastPingTimes: [String: Date]
    var friendExpiresAt: [String: Int64] = [:]
    let onTogglePause: (String) -> Void
    let onCancelInvite: (Shared.PendingInviteView) -> Void
    let onCreateInvite: () -> Void
    let onScanQr: () -> Void
    let onRename: (String, String) -> Void
    let onPasteUrl: (String) -> Void
    let onRemove: (String) -> Void
    var onSetFriendExpiry: (String, Int64?) -> Void = { _, _ in }
    let onZoomTo: (String) -> Void
    var diagnosticLog: [String] = []

    @State private var friendToRemove: Shared.FriendEntry? = nil
    @State private var friendToRename: Shared.FriendEntry? = nil
    @State private var newFriendName: String = ""
    @State private var showPasteField = false
    @State private var pastedUrl = ""
    @State private var debugExpandedFriendId: String? = nil
    @State private var showDiagnosticLog = false

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
                                        let display = friend.displayState(
                                            nowSeconds: Int64(Date().timeIntervalSince1970),
                                            lastPingSeconds: lastPingTimes[friend.id].map { KotlinLong(value: Int64($0.timeIntervalSince1970)) },
                                            dimWindowSeconds: PeerDisplayKt.STOPPED_PIN_DIM_WINDOW_SECONDS,
                                        )
                                        Text(peerSubtitleText(display))
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                        if friend.isStale {
                                            Text(MR.strings().friend_inactive_warning.localized())
                                                .font(.caption)
                                                .foregroundStyle(.red)
                                        }
                                        if friend.lastDecryptFailed {
                                            Text(MR.strings().decryption_error_warning.localized())
                                                .font(.caption)
                                                .foregroundStyle(.red)
                                        }
                                        if let exp = friendExpiresAt[friend.id] {
                                            let nowSec = Int64(Date().timeIntervalSince1970)
                                            let rem = max(0, exp - nowSec)
                                            let h = rem / 3600
                                            let m = (rem % 3600) / 60
                                            let left = h > 0 ? "\(h)h \(m)m" : "\(m)m"
                                            Text(String(format: MR.strings().sharing_for_remaining.localized(), left))
                                                .font(.caption)
                                                .foregroundStyle(.blue)
                                        }
                                    }
                                    Spacer()

                                    FriendOverflowMenu(
                                        friend: friend,
                                        isPaused: pausedFriendIds.contains(friend.id),
                                        hasTimer: friendExpiresAt[friend.id] != nil,
                                        onRename: {
                                            friendToRename = friend
                                            newFriendName = friend.name
                                        },
                                        onTogglePause: { onTogglePause(friend.id) },
                                        onRemove: { friendToRemove = friend },
                                        onShareFor: { durationSec in
                                            if let d = durationSec {
                                                onSetFriendExpiry(friend.id, Int64(Date().timeIntervalSince1970) + d)
                                            } else {
                                                onSetFriendExpiry(friend.id, nil)
                                            }
                                        },
                                    )
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
                if !diagnosticLog.isEmpty && debugExpandedFriendId != nil {
                    let hasError = diagnosticLog.contains { $0.contains("FAIL") || $0.contains("DESYNC") || $0.contains("STORAGE") }
                    Section {
                        Button {
                            showDiagnosticLog.toggle()
                        } label: {
                            Label(
                                showDiagnosticLog ? "Hide Events" : "Events (\(diagnosticLog.count))",
                                systemImage: showDiagnosticLog ? "chevron.down" : "chevron.right"
                            )
                            .font(.caption2)
                            .fontDesign(.monospaced)
                            .foregroundStyle(hasError ? .red : .secondary)
                        }
                        .buttonStyle(.plain)
                        if showDiagnosticLog {
                            ForEach(diagnosticLog, id: \.self) { event in
                                let isError = event.contains("FAIL") || event.contains("DESYNC") || event.contains("STORAGE")
                                Text(event)
                                    .font(.caption2)
                                    .fontDesign(.monospaced)
                                    .foregroundStyle(isError ? .red : .secondary)
                            }
                        }
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

private struct FriendOverflowMenu: View {
    let friend: Shared.FriendEntry
    let isPaused: Bool
    let hasTimer: Bool
    let onRename: () -> Void
    let onTogglePause: () -> Void
    let onRemove: () -> Void
    let onShareFor: (Int64?) -> Void

    var body: some View {
        Menu {
            if !isPaused {
                Button(MR.strings().share_for_30m.localized()) { onShareFor(30 * 60) }
                Button(MR.strings().share_for_1h.localized())  { onShareFor(60 * 60) }
                Button(MR.strings().share_for_4h.localized())  { onShareFor(4 * 60 * 60) }
                Button(MR.strings().share_for_8h.localized())  { onShareFor(8 * 60 * 60) }
                if hasTimer {
                    Button(MR.strings().share_indefinitely.localized()) { onShareFor(nil) }
                }
                Divider()
            }
            Button(isPaused ? MR.strings().resume.localized() : MR.strings().pause.localized(),
                   systemImage: isPaused ? "play.circle" : "pause.circle") {
                onTogglePause()
            }
            Button(MR.strings().rename.localized(), systemImage: "pencil") { onRename() }
            Button(MR.strings().remove.localized(), systemImage: "trash", role: .destructive) { onRemove() }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.title3)
                .foregroundStyle(.gray)
        }
        .buttonStyle(.plain)
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
            Text("recv: \(timeAgoStringFromSeconds(recvTs))  sent: \(timeAgoStringFromSeconds(sentTs))  poll: \(timeAgoStringFromSeconds(friend.lastPollTs))")
            Text("recvTok: \(recvToken)  sendTok: \(sendToken)")
            Text("needsRatchet: \(friend.session.needsRatchet ? "YES" : "no")  caughtUp: \(friend.isCaughtUp ? "YES" : "no")")
        }
        .font(.caption2)
        .fontDesign(.monospaced)
        .foregroundStyle(.secondary)
        .padding(.vertical, 2)
    }
}
