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
        case .denied, .restricted:
            return MR.strings().location_permission_missing.localized()
        default:
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
                friends: syncService.repo.friends,
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
                bottomBar
                    .sheet(isPresented: Binding(
                        get: { syncService.isInviteSheetShowing },
                        set: { if !$0 {
                            syncService.isInviteSheetShowing = false
                            if syncService.repo.pendingInitPayload == nil {
                                Task { await syncService.clearInviteIfNotExported() }
                            }
                        } else {
                            syncService.isInviteSheetShowing = true
                        } }
                    )) {
                        if let pending = syncService.repo.inviteState as? Shared.InviteState.Pending {
                            InviteSheet(
                                qrPayload: pending.qr,
                                displayName: $syncService.displayName,
                                onDismiss: { Task { await syncService.clearInviteIfNotExported() } },
                                onExported: { Task { await syncService.markCurrentInviteExported() } }
                            )
                        }
                    }
            }
        }
        .sheet(isPresented: $showFriends) {
            FriendsSheet(
                displayName: $syncService.displayName,
                friends: syncService.repo.friends,
                pendingInvites: syncService.repo.pendingInvites,
                pausedFriendIds: syncService.repo.pausedFriendIds,
                friendExpiresAt: syncService.repo.friendExpiresAt,
                onTogglePause: { syncService.togglePauseFriend(id: $0) },
                onCancelInvite: { invite in
                    Task { await syncService.clearInvite(ekPub: toSwiftData(invite.qrPayload.ekPub)) }
                },
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
                onSetFriendExpiry: { id, exp in syncService.setFriendExpiry(friendId: id, epochSeconds: exp) },
                onZoomTo: { friendId in
                    if let friend = syncService.repo.friends.first(where: { $0.id == friendId }),
                       let lat = friend.lastLat?.doubleValue,
                       let lng = friend.lastLng?.doubleValue {
                        zoomTarget = CLLocationCoordinate2D(latitude: lat, longitude: lng)
                    }
                    showFriends = false
                },
                diagnosticLog: syncService.repo.diagnosticLog
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
        .alert(MR.strings().name_this_contact.localized(), isPresented: Binding(
            get: { (syncService.repo.pendingQrForNaming != nil || syncService.repo.pendingInitPayload != nil) && !syncService.isInviteActive },
            set: { if !$0 {
                syncService.repo.pendingQrForNaming = nil
                syncService.repo.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            } }
        )) {
            TextField(MR.strings().friend_name_label.localized(), text: $newFriendName)
            if let qr = syncService.repo.pendingQrForNaming {
                Button(MR.strings().add.localized()) {
                    let name = newFriendName.isEmpty ? MR.strings().friend_.localized() : newFriendName
                    syncService.repo.pendingQrForNaming = nil
                    newFriendName = ""
                    Task { await syncService.confirmQrScan(qr: qr, friendName: name) }
                }
            } else if let payload = syncService.repo.pendingInitPayload {
                Button(MR.strings().save.localized()) {
                    let name = newFriendName.isEmpty ? MR.strings().friend_.localized() : newFriendName
                    Task {
                        await syncService.confirmPendingInit(payload: payload, name: name)
                        newFriendName = ""
                        syncService.repo.pendingInitPayload = nil
                    }
                }
            }
            Button(MR.strings().cancel.localized(), role: .cancel) {
                syncService.repo.pendingQrForNaming = nil
                syncService.repo.pendingInitPayload = nil
                Task { await syncService.cancelPendingInit() }
                newFriendName = ""
            }
        } message: {
            if syncService.repo.pendingQrForNaming != nil {
                Text(MR.strings().enter_name_for_friend.localized())
            } else if syncService.repo.multipleScansDetected {
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
        .onReceive(syncService.repo.$pendingQrForNaming) { qr in
            if let qr = qr { newFriendName = qr.suggestedName } else { newFriendName = "" }
        }
        .onReceive(syncService.repo.$pendingInitPayload) { payload in
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

    @ViewBuilder
    private var bottomBar: some View {
        HStack(spacing: 12) {
            sharingButton
            Spacer()
            connectionStatusView
            Spacer()
            friendsButton
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 32)
    }

    @ViewBuilder
    private var sharingButton: some View {
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
    }

    @ViewBuilder
    private var connectionStatusView: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Circle()
                    .fill(syncService.connectionStatus is Shared.ConnectionStatus.Ok ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(MR.strings().you.localized())
                    .font(.caption)
                    .foregroundStyle(.white)
            }
            if let errorStatus = syncService.connectionStatus as? Shared.ConnectionStatus.Error {
                let errorMessage = errorStatus.message.localized()
                Text(errorMessage)
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
    }

    @ViewBuilder
    private var friendsButton: some View {
        Button {
            showFriends = true
        } label: {
            Label("\(syncService.repo.friends.count)", systemImage: "person.2.fill")
                .font(.caption)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.7))
                .foregroundStyle(.white)
                .clipShape(Capsule())
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
                return
            case .notDetermined:
                locationManager.requestPermissionAndStart()
            default:
                break
            }
            syncService.isSharingLocation = true
            return
        }

        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestPermissionAndStart()
        case .authorizedWhenInUse:
            // If already sharing but only foreground, offer to upgrade to 'Always'.
            showLocationRationale = true
        case .denied, .restricted:
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        case .authorizedAlways:
            syncService.stopSharing()
        @unknown default:
            syncService.stopSharing()
        }
    }
}

extension ConnectionStatus {
    var isOk: Bool {
        return self is Shared.ConnectionStatus.Ok
    }
}
