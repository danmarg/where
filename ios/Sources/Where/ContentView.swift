import CoreLocation
import Shared
import SwiftUI

struct ContentView: View {
    @ObservedObject private var locationManager = LocationManager.shared
    @StateObject private var syncService = LocationSyncService()
    @State private var showFriends = false
    @State private var showScanner = false
    @State private var showUserSettings = false
    @State private var scannedUrl: String? = nil
    @State private var zoomTarget: CLLocationCoordinate2D? = nil
    
    @State private var newFriendName: String = ""

    private var visibleUsers: [Shared.UserLocation] {
        var result: [Shared.UserLocation] = []
        if syncService.isSharingLocation, let loc = locationManager.location {
            result.append(Shared.UserLocation(
                userId: syncService.myId,
                lat: loc.coordinate.latitude,
                lng: loc.coordinate.longitude,
                timestamp: Int64(Date().timeIntervalSince1970)
            ))
        }
        for (friendId, loc) in syncService.friendLocations {
            result.append(Shared.UserLocation(userId: friendId, lat: loc.lat, lng: loc.lng, timestamp: loc.ts))
        }
        return result
    }

    var body: some View {
        ZStack {
            WhereMapView(
                users: visibleUsers,
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
                            Text(syncService.displayName.isEmpty ? "You" : syncService.displayName)
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
                        showUserSettings = true
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
                    syncService.createInvite()
                },
                onScanQr: {
                    showFriends = false
                    showScanner = true
                },
                onRename: { id, name in syncService.renameFriend(id: id, newName: name) },
                onPasteUrl: { url in
                    showFriends = false
                    syncService.processQrUrl(url)
                },
                onRemove: { syncService.removeFriend(id: $0) },
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
            get: { syncService.pendingInviteQr != nil },
            set: { if !$0 { syncService.clearInvite() } }
        )) {
            if let qr = syncService.pendingInviteQr {
                InviteSheet(qrPayload: qr, onDismiss: { syncService.clearInvite() })
            }
        }
        .alert("You", isPresented: $showUserSettings) {
            TextField("Your Name", text: $syncService.displayName)
            Button("Close", role: .cancel) {}
        } message: {
            Text("Set your display name that friends will see.")
        }
        .alert("Name this contact", isPresented: Binding(
            get: { (syncService.pendingQrForNaming != nil || syncService.pendingInitPayload != nil) && !syncService.isInviteActive },
            set: { if !$0 {
                syncService.pendingQrForNaming = nil
                syncService.cancelPendingInit()
                newFriendName = ""
            } }
        )) {
            TextField("Friend's Name", text: $newFriendName)
            if let qr = syncService.pendingQrForNaming {
                Button("Add") {
                    syncService.confirmQrScan(qr: qr, friendName: newFriendName.isEmpty ? "Friend" : newFriendName)
                    newFriendName = ""
                }
            } else if syncService.pendingInitPayload != nil {
                Button("Save") {
                    syncService.confirmPendingInit(name: newFriendName.isEmpty ? "Friend" : newFriendName)
                    newFriendName = ""
                }
            }
            Button("Cancel", role: .cancel) {
                syncService.pendingQrForNaming = nil
                syncService.cancelPendingInit()
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
        .onReceive(locationManager.$location) { loc in
            guard let loc else { return }
            syncService.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
        .onReceive(syncService.$pendingQrForNaming) { qr in
            if let qr = qr { newFriendName = qr.suggestedName } else { newFriendName = "" }
        }
        .onReceive(syncService.$pendingInitPayload) { payload in
            if let payload = payload { newFriendName = payload.suggestedName } else { newFriendName = "" }
        }
    }
}

extension ConnectionStatus {
    var isOk: Bool {
        if case .ok = self { return true }
        return false
    }
}
