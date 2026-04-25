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
    @State private var showErrorAlert = false
    @State private var showLocationRationale = false

    @State private var newFriendName: String = ""

    private var sharingStatusText: String {
        if !syncService.isSharingLocation {
            return MR.strings().paused.localized()
        }
        switch locationManager.authorizationStatus {
        case .notDetermined:
            return MR.strings().sharing.localized()
        case .denied, .restricted:
            return MR.strings().location_permission_missing.localized()
        case .authorizedWhenInUse, .authorizedAlways:
            return MR.strings().sharing.localized()
        @unknown default:
            return MR.strings().sharing.localized()
        }
    }

    private var sharingStatusColor: Color {
        if !syncService.isSharingLocation {
            return Color.gray.opacity(0.85)
        }
        switch locationManager.authorizationStatus {
        case .denied, .restricted:
            return Color.red.opacity(0.85)
        default:
            return Color.blue.opacity(0.85)
        }
    }

    var body: some View {
        ZStack {
            WhereMapView(
                users: syncService.visibleUsers,
                friends: syncService.friends,
                friendLastPing: syncService.friendLastPing,
                ownLocation: locationManager.location.map {
                    CLLocationCoordinate2D(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)
                },
                ownHeading: syncService.ownHeading,
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
                        handleSharingButtonTap()
                    } label: {
                        Label(
                            sharingStatusText,
                            systemImage: syncService.isSharingLocation && locationManager.authorizationStatus != .denied ? "location.fill" : "location.slash.fill"
                        )
                        .font(.caption)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(sharingStatusColor)
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                    }

                    Spacer()

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(syncService.connectionStatus is Shared.ConnectionStatus.Ok ? Color.green : Color.orange)
                                .frame(width: 8, height: 8)
                            Text(MR.strings().you.localized())
                                .font(.caption)
                                .foregroundStyle(.white)
                        }
                        if let error = syncService.connectionStatus as? Shared.ConnectionStatus.Error {
                            Text(error.message.localized())
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
                        if syncService.connectionStatus is Shared.ConnectionStatus.Error {
                            showErrorAlert = true
                        } else if let loc = locationManager.location {
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
                displayName: $syncService.displayName,
                friends: syncService.friends,
                pendingInvites: syncService.pendingInvites,
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
                onRemovePendingInvite: { token in Task { await syncService.removePendingInvite(discoveryTokenHex: token) } },
                onZoomTo: { friendId in
                    if let loc = syncService.friendLocations[friendId] {
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
            get: { syncService.inviteState is Shared.InviteState.Pending },
            set: { if !$0 {
                // If we're dismissing because a peer joined (pendingInitPayload is set),
                // do NOT clear the store yet, as we need the ephemeral keys to derive the session.
                if syncService.pendingInitPayload == nil {
                    Task { await syncService.clearInvite() }
                }
            } }
        )) {
            if let pending = syncService.inviteState as? Shared.InviteState.Pending {
                InviteSheet(
                    qrPayload: pending.qr,
                    displayName: $syncService.displayName,
                    onDismiss: { Task { await syncService.clearInvite() } }
                )
            }
        }
        .alert(MR.strings().name_this_contact.localized(), isPresented: Binding(
            get: { (syncService.pendingQrForNaming != nil || syncService.pendingInitPayload != nil) && !syncService.isInviteActive },
            set: { if !$0 {
                syncService.pendingQrForNaming = nil
                syncService.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            } }
        )) {
            TextField(MR.strings().friend_name_label.localized(), text: $newFriendName)
            if let qr = syncService.pendingQrForNaming {
                Button(MR.strings().add.localized()) {
                    let name = newFriendName.isEmpty ? MR.strings().friend_.localized() : newFriendName
                    syncService.pendingQrForNaming = nil
                    newFriendName = ""
                    Task { await syncService.confirmQrScan(qr: qr, friendName: name) }
                }
            } else if let payload = syncService.pendingInitPayload {
                Button(MR.strings().save.localized()) {
                    let name = newFriendName.isEmpty ? MR.strings().friend_.localized() : newFriendName
                    Task {
                        await syncService.confirmPendingInit(payload: payload, name: name)
                        newFriendName = ""
                        syncService.pendingInitPayload = nil
                    }
                }
            }
            Button(MR.strings().cancel.localized(), role: .cancel) {
                syncService.pendingQrForNaming = nil
                syncService.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            }
        } message: {
            if syncService.pendingQrForNaming != nil {
                Text(MR.strings().enter_name_for_friend.localized())
            } else if syncService.multipleScansDetected {
                Text(MR.strings().multiple_scans_detected_warning.localized())
            } else {
                Text(MR.strings().new_friend_scanned_qr.localized())
            }
        }
        .onAppear {
            locationManager.requestPermissionAndStart()
        }
        .onChange(of: scenePhase) { _, newPhase in
            handlePhaseChange(newPhase)
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
        .alert(MR.strings().background_location_title.localized(), isPresented: $showLocationRationale) {
            Button(MR.strings().allow.localized()) {
                locationManager.requestAlwaysPermission()
            }
            Button(MR.strings().skip.localized(), role: .cancel) { }
        } message: {
            Text(MR.strings().background_location_message.localized())
        }
        .alert(MR.strings().connection_error.localized(), isPresented: $showErrorAlert) {
            Button(MR.strings().ok.localized(), role: .cancel) { }
        } message: {
            if let error = syncService.connectionStatus as? Shared.ConnectionStatus.Error {
                Text(error.message.localized())
            }
        }
    }

    private func handlePhaseChange(_ phase: ScenePhase) {
        if phase == .background {
            // Send location immediately when entering background so Android has fresh
            // data even when BGAppRefreshTask doesn't fire for a long time.
            syncService.sendLocationOnBackground()
        } else if phase == .active {
            syncService.startPolling()
        }
    }

    private func handleSharingButtonTap() {
        if !syncService.isSharingLocation {
            switch locationManager.authorizationStatus {
            case .denied, .restricted:
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
                return // don't set isSharingLocation = true
            default:
                break
            }
            syncService.isSharingLocation = true
            locationManager.requestPermissionAndStart()
            return
        }

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestPermissionAndStart()
        case .authorizedWhenInUse:
            // If already sharing but only foreground, offer to upgrade to 'Always'.
            // Note: We don't toggle isSharingLocation off here; if they 'Skip' the rationale,
            // we continue sharing in foreground mode.
            showLocationRationale = true
        case .denied, .restricted:
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        case .authorizedAlways:
            syncService.isSharingLocation = false
        @unknown default:
            syncService.isSharingLocation = false
        }
    }
}

extension ConnectionStatus {
    var isOk: Bool {
        return self is Shared.ConnectionStatus.Ok
    }
}
