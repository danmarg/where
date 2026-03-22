import SwiftUI
import Combine

struct ContentView: View {
    @StateObject private var locationManager = LocationManager()
    @StateObject private var syncService = LocationSyncService(userId: UserIdentity.userId)
    @StateObject private var friendsStore = FriendsStore()
    @State private var showFriends = false

    // Only show self + friends on the map
    private var visibleUsers: [UserLocationData] {
        syncService.users.filter { user in
            user.userId == UserIdentity.userId || friendsStore.friendIds.contains(user.userId)
        }
    }

    var body: some View {
        ZStack {
            WhereMapView(users: visibleUsers, ownUserId: UserIdentity.userId)
                .ignoresSafeArea()

            VStack {
                // Top bar: connection status + user count
                HStack(spacing: 8) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 8, height: 8)
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(.white)
                    Spacer()
                    if !friendsStore.friendIds.isEmpty {
                        Text("\(visibleUsers.count) online")
                            .font(.caption)
                            .foregroundStyle(.white)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.black.opacity(0.6))
                .clipShape(Capsule())
                .padding(.top, 12)
                .padding(.horizontal, 16)

                Spacer()

                // Bottom controls
                HStack(spacing: 12) {
                    // Pause / resume sharing
                    Button {
                        friendsStore.isSharingLocation.toggle()
                    } label: {
                        Label(
                            friendsStore.isSharingLocation ? "Sharing" : "Paused",
                            systemImage: friendsStore.isSharingLocation ? "location.fill" : "location.slash.fill"
                        )
                        .font(.caption)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(friendsStore.isSharingLocation ? Color.blue.opacity(0.85) : Color.gray.opacity(0.85))
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                    }

                    Spacer()

                    // Your ID chip
                    Text("You: \(UserIdentity.userId.prefix(8))")
                        .font(.caption)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.black.opacity(0.7))
                        .foregroundStyle(.white)
                        .clipShape(Capsule())

                    Spacer()

                    // Friends button
                    Button {
                        showFriends = true
                    } label: {
                        Label("\(friendsStore.friendIds.count)", systemImage: "person.2.fill")
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
            FriendsSheet(store: friendsStore)
        }
        .onAppear {
            locationManager.requestPermissionAndStart()
            syncService.connect()
        }
        .onDisappear {
            syncService.disconnect()
        }
        .onReceive(locationManager.$location) { newLocation in
            guard let loc = newLocation, friendsStore.isSharingLocation else { return }
            syncService.sendLocation(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        }
    }

    private var statusColor: Color {
        switch syncService.connectionState {
        case .connected: return .green
        case .connecting: return .yellow
        case .disconnected: return .red
        }
    }

    private var statusText: String {
        switch syncService.connectionState {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .disconnected: return "Disconnected"
        }
    }
}
