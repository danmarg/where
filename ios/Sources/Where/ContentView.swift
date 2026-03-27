import CoreLocation
import Shared
import SwiftUI

struct ContentView: View {
    @StateObject private var locationManager = LocationManager()
    @StateObject private var syncService = LocationSyncService()
    @State private var showFriends = false
    @State private var showScanner = false
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
                ownUserId: syncService.myId,
                zoomTarget: zoomTarget,
                onZoomConsumed: { zoomTarget = nil }
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

                    Text("You: \(syncService.myId.prefix(8))")
                        .font(.caption)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.black.opacity(0.7))
                        .foregroundStyle(.white)
                        .clipShape(Capsule())

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
        }
        .sheet(isPresented: $showFriends) {
            FriendsSheet(
                myId: syncService.myId,
                displayName: $syncService.displayName,
                friends: syncService.friends,
                pausedFriendIds: syncService.pausedFriendIds,
                onTogglePause: { syncService.togglePauseFriend(id: $0) },
                onCreateInvite: {
                    showFriends = false
                    syncService.createInvite()
                },
                onScanQr: {
                    showFriends = false
                    showScanner = true
                },
                onRemove: { syncService.removeFriend(id: $0) },
                onZoomTo: { friendId in
                    if let loc = syncService.friendLocations[friendId] {
                        zoomTarget = CLLocationCoordinate2D(latitude: loc.lat, longitude: loc.lng)
                    }
                }
            )
        }
        .fullScreenCover(isPresented: $showScanner) {
            QrScannerView(
                onScan: { url in syncService.processQrUrl(url) },
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
        .alert("Name this contact", isPresented: Binding(
            get: { syncService.pendingQrForNaming != nil },
            set: { if !$0 { syncService.pendingQrForNaming = nil } }
        )) {
            TextField("Friend's Name", text: $newFriendName)
            Button("Add") {
                if let qr = syncService.pendingQrForNaming {
                    syncService.confirmQrScan(qr: qr, friendName: newFriendName.isEmpty ? "Friend" : newFriendName)
                    newFriendName = ""
                }
            }
            Button("Cancel", role: .cancel) {
                syncService.pendingQrForNaming = nil
                newFriendName = ""
            }
        } message: {
            Text("Enter a name for this friend.")
        }
        .alert("Name this contact", isPresented: $syncService.hasPendingInit) {
            TextField("Friend's Name", text: $newFriendName)
            Button("Save") {
                syncService.confirmPendingInit(name: newFriendName.isEmpty ? "Friend" : newFriendName)
                newFriendName = ""
            }
            Button("Skip", role: .cancel) {
                syncService.confirmPendingInit(name: "Friend")
                newFriendName = ""
            }
        } message: {
            Text("A new friend has scanned your QR code.")
        }
        .onAppear {
            locationManager.requestPermissionAndStart()
        }
        .onReceive(locationManager.$location) { loc in
            guard let loc else { return }
            syncService.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
        .onReceive(syncService.$pendingQrForNaming) { qr in
            if let qr = qr {
                newFriendName = qr.suggestedName
            }
        }
    }
}
