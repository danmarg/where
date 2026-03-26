import CoreLocation
import Shared
import SwiftUI

struct ContentView: View {
    @StateObject private var locationManager = LocationManager()
    @StateObject private var syncService = LocationSyncService()
    @State private var showFriends = false
    @State private var showScanner = false
    @State private var zoomTarget: CLLocationCoordinate2D? = nil

    private var visibleUsers: [UserLocation] {
        var result: [UserLocation] = []
        // Own location when sharing
        if syncService.isSharingLocation, let loc = locationManager.location {
            result.append(UserLocation(
                userId: syncService.myId,
                lat: loc.coordinate.latitude,
                lng: loc.coordinate.longitude,
                timestamp: Int64(Date().timeIntervalSince1970)
            ))
        }
        // Friends' decrypted locations
        for (friendId, loc) in syncService.friendLocations {
            result.append(UserLocation(userId: friendId, lat: loc.lat, lng: loc.lng, timestamp: loc.ts))
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
                friends: syncService.friends,
                onCreateInvite: {
                    showFriends = false
                    syncService.createInvite()
                },
                onScanQr: {
                    showFriends = false
                    showScanner = true
                },
                onRemove: { syncService.e2eeStore.deleteFriend(id: $0) },
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
        .onAppear {
            locationManager.requestPermissionAndStart()
        }
        .onReceive(locationManager.$location) { loc in
            guard let loc else { return }
            syncService.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
    }
}
