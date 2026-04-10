import CoreLocation
import Shared
import SwiftUI

import os
private let logger = Logger(subsystem: "net.af0.where", category: "LocationSync")

struct ContentView: View {
    @ObservedObject private var locationManager = LocationManager.shared
    @StateObject private var syncService = LocationSyncService.shared
    @Environment(\.scenePhase) private var scenePhase
    @State private var showFriends = false
    @State private var showScanner = false
    @State private var scannedUrl: String? = nil
    @State private var zoomTarget: CLLocationCoordinate2D? = nil

    @State private var newFriendName: String = ""

    var body: some View {
        ZStack {
            WhereMapView(
                users: syncService.visibleUsers,
                friends: syncService.friends,
                ownUserId: syncService.myId,
                zoomTarget: zoomTarget,
                onZoomConsumed: { zoomTarget = nil },
                onSelectFriend: { friendId in
                    showFriends = true
                }
            )
            .ignoresSafeArea()

            VStack {
                Spacer()

                HStack(spacing: 12) {
                    Button {
                        syncService.isSharingLocation.toggle()
                    } label: {
                        Label(
                            syncService.isSharingLocation ? "Sharing" : "Paused",
                            systemImage: syncService.isSharingLocation ? "location.fill" : "location.slash.fill"
                        )
                        .font(.caption)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(syncService.isSharingLocation ? Color.blue.opacity(0.85) : Color.gray.opacity(0.85))
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                    }

                    Spacer()

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(syncService.connectionStatus.isOk ? Color.green : Color.orange)
                                .frame(width: 8, height: 8)
                            Text("You")
                                .font(.caption)
                                .foregroundStyle(.white)
                        }
                        if case .error(let msg) = syncService.connectionStatus {
                            Text(msg)
                                .font(.system(size: 8))
                                .foregroundStyle(.orange)
                                .lineLimit(1)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.7))
                    .clipShape(Capsule())
                    .contentShape(Capsule())
                    .onTapGesture {
                        if let loc = locationManager.location {
                            zoomTarget = CLLocationCoordinate2D(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude)
                        }
                    }

                    Spacer()

                    Button {
                        showFriends = true
                    } label: {
                        Label("\(syncService.friends.count)", systemImage: "person.2.fill")
                            .font(.caption)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.black.opacity(0.7))
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 32)
            }

            if syncService.isExchanging {
                Color.black.opacity(0.3)
                    .ignoresSafeArea()
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(1.5)
            }
        }
        .sheet(isPresented: $showFriends) {
            FriendsSheet(
                myId: syncService.myId,
                displayName: $syncService.displayName,
                friends: syncService.friends,
                pausedFriendIds: syncService.pausedFriendIds,
                lastPingTimes: syncService.friendLastPing,
                onTogglePause: { syncService.togglePauseFriend(id: $0) },
                onCreateInvite: {
                    showFriends = false
                    Task { await syncService.createInvite() }
                },
                onScanQr: {
                    showFriends = false
                    showScanner = true
                },
                onRename: { id, name in Task { await syncService.renameFriend(id: id, newName: name) } },
                onPasteUrl: { url in
                    showFriends = false
                    syncService.processQrUrl(url)
                },
                onRemove: { id in Task { await syncService.removeFriend(id: id) } },
                onZoomTo: { friendId in
                    if friendId == syncService.myId {
                        // Zoom to own location
                        if let loc = locationManager.location {
                            zoomTarget = CLLocationCoordinate2D(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude)
                        }
                    } else if let loc = syncService.friendLocations[friendId] {
                        zoomTarget = CLLocationCoordinate2D(latitude: loc.lat, longitude: loc.lng)
                    }
                    showFriends = false
                }
            )
        }
        .fullScreenCover(isPresented: $showScanner, onDismiss: {
            if let url = scannedUrl {
                _ = syncService.processQrUrl(url)
                scannedUrl = nil
            }
        }) {
            QrScannerView(
                onScan: { url in
                    scannedUrl = url
                    showScanner = false
                },
                onDismiss: { showScanner = false }
            )
            .ignoresSafeArea()
        }
        .sheet(isPresented: Binding(
            get: { if case .pending = syncService.inviteState { return true } else { return false } },
            set: { if !$0 {
                // If we're dismissing because a peer joined (pendingInitPayload is set),
                // do NOT clear the store yet, as we need the ephemeral keys to derive the session.
                if syncService.pendingInitPayload == nil {
                    Task { await syncService.clearInvite() }
                }
            } }
        )) {
            if case .pending(let qr) = syncService.inviteState {
                InviteSheet(
                    qrPayload: qr,
                    displayName: $syncService.displayName,
                    onDismiss: { Task { await syncService.clearInvite() } }
                )
            }
        }
        .alert("Name this contact", isPresented: Binding(
            get: { (syncService.pendingQrForNaming != nil || syncService.pendingInitPayload != nil) && !syncService.isInviteActive },
            set: { if !$0 {
                syncService.pendingQrForNaming = nil
                syncService.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            } }
        )) {
            TextField("Friend's Name", text: $newFriendName)
            if let qr = syncService.pendingQrForNaming {
                Button("Add") {
                    let name = newFriendName.isEmpty ? "Friend" : newFriendName
                    syncService.pendingQrForNaming = nil
                    newFriendName = ""
                    Task { await syncService.confirmQrScan(qr: qr, friendName: name) }
                }
            } else if let payload = syncService.pendingInitPayload {
                Button("Save") {
                    let name = newFriendName.isEmpty ? "Friend" : newFriendName
                    Task {
                        await syncService.confirmPendingInit(payload: payload, name: name)
                        newFriendName = ""
                        syncService.pendingInitPayload = nil
                    }
                }
            }
            Button("Cancel", role: .cancel) {
                syncService.pendingQrForNaming = nil
                syncService.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            }
        } message: {
            if syncService.pendingQrForNaming != nil {
                Text("Enter a name for this friend.")
            } else {
                Text("A new friend has scanned your QR code.")
            }
        }
        .onAppear {
            locationManager.requestPermissionAndStart()
        }
        .onChange(of: scenePhase) { oldPhase, newPhase in
            if newPhase == .background {
                // Timer keeps running to fire background heartbeat sends.
            } else if newPhase == .active {
                syncService.startPolling()
            }
        }
        .onReceive(syncService.$pendingQrForNaming) { qr in
            if let qr = qr { newFriendName = qr.suggestedName } else { newFriendName = "" }
        }
        .onReceive(syncService.$pendingInitPayload) { payload in
            if let payload = payload { newFriendName = payload.suggestedName } else { newFriendName = "" }
        }
        .onOpenURL { url in
            syncService.processQrUrl(url.absoluteString)
        }
    }
}

extension ConnectionStatus {
    var isOk: Bool {
        if case .ok = self { return true }
        return false
    }
}
