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
    @State private var showBackgroundChoice = false

    @State private var newFriendName: String = ""

    /// What the status pill shows. Reduced (approximate) accuracy only matters when it actually
    /// stops us sending: an approximate fix inside `minBroadcastAccuracyMeters` is still broadcast,
    /// so it's shown as normal sharing rather than as a failure. On When-In-Use, sharing carries on
    /// in the background only until iOS terminates the app (nothing relaunches it), so it's shown
    /// as "while open".
    enum SharingStatus: Equatable {
        case paused, permissionMissing, preciseLocationOff, sharingWhileOpen, sharing

        static func derive(
            isSharing: Bool,
            authorization: CLAuthorizationStatus,
            accuracyAuthorization: CLAccuracyAuthorization,
            lastFixAccuracy: CLLocationAccuracy?
        ) -> SharingStatus {
            if !isSharing { return .paused }
            if authorization == .denied || authorization == .restricted { return .permissionMissing }
            let fixUsable = lastFixAccuracy.map { $0 >= 0 && $0 <= LocationSyncService.minBroadcastAccuracyMeters } ?? false
            if accuracyAuthorization == .reducedAccuracy && !fixUsable { return .preciseLocationOff }
            if authorization == .authorizedWhenInUse { return .sharingWhileOpen }
            return .sharing
        }
    }

    /// What tapping the sharing button does. While sharing it stops - except "while open", where
    /// it offers the Always upgrade alongside stopping. On When-In-Use it used to show only the
    /// background-location rationale, so sharing couldn't be turned off. Not sharing, it starts,
    /// via the permission prompt or Settings when permission is missing.
    enum SharingToggleAction: Equatable {
        case toggle, chooseBackgroundOrStop, requestPermissionAndStart, openSettings

        static func derive(isSharing: Bool, authorization: CLAuthorizationStatus) -> SharingToggleAction {
            if isSharing { return authorization == .authorizedWhenInUse ? .chooseBackgroundOrStop : .toggle }
            switch authorization {
            case .denied, .restricted: return .openSettings
            case .notDetermined: return .requestPermissionAndStart
            default: return .toggle
            }
        }
    }

    /// Set when the app goes inactive, which is what a system permission prompt does.
    @State private var systemPromptAppeared = false

    @AppStorage("always_permission_requested") private var alwaysPermissionRequested = false

    /// iOS shows its When-In-Use → Always prompt at most once, and never under Allow Once; when it
    /// won't, there's nowhere left but Settings. Once we've asked, go straight there. The first
    /// time, ask - and if no prompt shows within a second (it was used up before this flag
    /// existed, or the grant is Allow Once), fall back to Settings.
    private func requestAlwaysOrOpenSettings() {
        if alwaysPermissionRequested {
            openSettings()
            return
        }
        alwaysPermissionRequested = true
        systemPromptAppeared = false
        locationManager.requestAlwaysPermission()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1))
            if !systemPromptAppeared && locationManager.authorizationStatus == .authorizedWhenInUse {
                openSettings()
            }
        }
    }

    private func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private var sharingStatus: SharingStatus {
        SharingStatus.derive(
            isSharing: syncService.isSharingLocation,
            authorization: locationManager.authorizationStatus,
            accuracyAuthorization: locationManager.accuracyAuthorization,
            lastFixAccuracy: locationManager.currentSettingsFixAccuracy
        )
    }

    private var sharingStatusText: String {
        switch sharingStatus {
        case .paused: return MR.strings().paused.localized()
        case .permissionMissing: return MR.strings().location_permission_missing.localized()
        case .preciseLocationOff: return MR.strings().precise_location_off.localized()
        case .sharingWhileOpen: return MR.strings().sharing_while_open.localized()
        case .sharing: return MR.strings().sharing.localized()
        }
    }

    private var sharingStatusColor: Color {
        switch sharingStatus {
        case .paused: return Color.gray.opacity(0.85)
        case .permissionMissing: return Color.red.opacity(0.85)
        case .preciseLocationOff, .sharingWhileOpen: return Color.orange.opacity(0.85)
        case .sharing: return Color.blue.opacity(0.85)
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
                            syncService.dismissInvite()
                        } else {
                            syncService.isInviteSheetShowing = true
                        } }
                    )) {
                        if let pending = syncService.repo.inviteState as? Shared.InviteState.Pending {
                            InviteSheet(
                                qrPayload: pending.qr,
                                displayName: $syncService.displayName,
                                onDismiss: { syncService.dismissInvite() },
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
            .onAppear {
                // diagnosticLog otherwise only refreshes after a poll cycle completes (success
                // or, since the fix above, failure) - opening the sheet shouldn't have to wait
                // for the next one, especially right after a stall when there may not be one
                // for a while.
                Task { try? await syncService.repo.refreshFromStore() }
            }
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
            if newPhase != .active { systemPromptAppeared = true }
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
        .alert(MR.strings().discarded_sessions_title.localized(), isPresented: Binding(
            get: { !syncService.discardedSessionNames.isEmpty },
            set: { if !$0 { syncService.dismissDiscardedSessionsNotice() } }
        )) {
            Button(MR.strings().ok.localized(), role: .cancel) { }
        } message: {
            Text(MR.strings().discarded_sessions_message.localized(
                args: [ListFormatter.localizedString(byJoining: syncService.discardedSessionNames) as NSString]
            ))
        }
        .alert(syncService.inviteError ?? "", isPresented: Binding(
            get: { syncService.inviteError != nil },
            set: { if !$0 { syncService.dismissInviteError() } }
        )) {
            Button(MR.strings().ok.localized(), role: .cancel) { }
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
        // Anchored to the pill so it points there as an iPad popover.
        .confirmationDialog(MR.strings().background_location_title.localized(), isPresented: $showBackgroundChoice, titleVisibility: .visible) {
            Button(MR.strings().allow_background_location.localized()) {
                requestAlwaysOrOpenSettings()
            }
            Button(MR.strings().stop_sharing.localized(), role: .destructive) {
                syncService.stopSharing()
            }
            Button(MR.strings().cancel.localized(), role: .cancel) { }
        } message: {
            Text(MR.strings().background_location_message.localized())
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
        switch SharingToggleAction.derive(isSharing: syncService.isSharingLocation, authorization: locationManager.authorizationStatus) {
        case .toggle:
            syncService.toggleSharing()
        case .chooseBackgroundOrStop:
            showBackgroundChoice = true
        case .requestPermissionAndStart:
            locationManager.requestPermissionAndStart()
            syncService.toggleSharing()
        case .openSettings:
            openSettings()
        }
    }
}

extension ConnectionStatus {
    var isOk: Bool {
        return self is Shared.ConnectionStatus.Ok
    }
}
