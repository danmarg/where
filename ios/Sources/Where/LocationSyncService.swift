import Foundation
@preconcurrency import Shared
import os
import UIKit
import CoreLocation
import CoreMotion
import Combine
import Network
private let logger = Logger(subsystem: "net.af0.where", category: "LocationSync")


private class RawStringDesc: NSObject, Shared.ResourcesStringDesc {
    private let value: String
    init(_ value: String) { self.value = value }
    func localized() -> String { value }
}

@MainActor
protocol LocationClientProtocol: AnyObject, Sendable {
    func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool) async throws
    func sendLocationToFriend(friendId: String, lat: Double, lng: Double, stationary: Bool) async throws
    func sendStoppedSharing(pausedFriendIds: Set<String>) async throws
    func poll(isForeground: Bool, pausedFriendIds: Set<String>) async throws -> [Shared.UserLocation]
    func pollPendingInvites() async throws -> [Shared.PendingInviteResult]
    func postKeyExchangeInit(friendId: String, qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws
    func syncNow() async throws
}

extension Shared.LocationClient: @unchecked @retroactive Sendable, LocationClientProtocol {}

@inline(__always)
private func debugLog(_ msg: () -> String) {
    #if DEBUG
    let message = msg()
    logger.debug("\(message)")
    #endif
}

enum WakeSource: String {
    case timer = "Timer"
    case backgroundTask = "BGTask"
    case locationUpdate = "GPS"
    case visit = "Visit"
    case manual = "Manual"
    case backgroundEntry = "BGEntry"
    case heartbeat = "Heartbeat"
    case network = "Network"
}

@MainActor
final class LocationSyncService: ObservableObject {
    static let shared = LocationSyncService()

    @Published var friendLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
    @Published var friendLastPing: [String: Date] = [:]
    /// friendId → epoch-seconds the peer first reported stationary in the current run.
    /// Derived from `self.friends`; never written directly. Refreshed by `refreshSharingStateMaps()`.
    @Published private(set) var friendStationarySince: [String: Int64] = [:]
    /// friendId → epoch-seconds the peer sent StoppedSharing.
    /// Derived from `self.friends`; never written directly. Refreshed by `refreshSharingStateMaps()`.
    @Published private(set) var friendStoppedAt: [String: Int64] = [:]
    /// Epoch-seconds at which sharing should automatically stop, or nil for no expiry.
    @Published var sharingExpiresAt: Int64? = nil
    @Published var connectionStatus: Shared.ConnectionStatus = Shared.ConnectionStatus.Ok()
    @Published var friends: [Shared.FriendEntry] = []
    @Published var diagnosticLog: [String] = []
    @Published var pendingInvites: [Shared.PendingInviteView] = []
    @Published var isDataLoaded: Bool = false
    @Published var inviteState: Shared.InviteState = Shared.InviteState.None()
    /// Mirror of `userStore.isSharingLocation` for SwiftUI binding. Treat this as a
    /// read-from-anywhere / write-via-method property: callers that want to STOP sharing
    /// must call `stopSharing()` (not `isSharingLocation = false`) so the StoppedSharing
    /// message is enqueued and the time-limited expiry is cleared. didSet handles only
    /// state mirroring concerns that apply to every transition.
    @Published var isSharingLocation: Bool {
        didSet {
            userStore.setSharing(sharing: isSharingLocation)
            locationProvider.sharingStateChanged()
            if !isSharingLocation {
                forceNextLocationUpdate = false
                locationFixTimeoutTask?.cancel()
                locationFixTimeoutTask = nil
            }
        }
    }
    @Published var isInviteSheetShowing: Bool = false {
        didSet {
            if isInviteSheetShowing {
                triggerRapidPoll()
            }
        }
    }
    @Published var displayName: String {
        didSet {
            userStore.setDisplayName(name: displayName)
            if isInviteActive {
                Task { await createInvite() }
            }
        }
    }
    @Published var pausedFriendIds: Set<String> {
        didSet {
            userStore.setPausedFriends(ids: pausedFriendIds)
        }
    }

    @Published var ownHeading: Double? = nil
    @Published var pendingQrForNaming: Shared.QrPayload? = nil
    @Published var pendingInitPayload: Shared.KeyExchangeInitPayload? = nil
    @Published var pendingInitAliceEkPub: Data? = nil
    @Published var multipleScansDetected: Bool = false
    @Published var isExchanging: Bool = false
    private var inviteTask: Task<Void, Never>? = nil
    private var sharingExpiryTask: Task<Void, Never>? = nil
    @Published var visibleUsers: [Shared.UserLocation] = []
    var isInviteActive: Bool { isInviteSheetShowing }
    var skipUpdateVisibleUsers: Bool = false

    var lastRapidPollTrigger: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var lastPollTime: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    private var lastCleanupTime: Date = Date(timeIntervalSince1970: 0)
    var pollTimer: Timer?  // internal for testing
    private var isPollInFlight = false
    /// Overridable in tests to simulate foreground/background without UIKit.
    var isInForeground: () -> Bool = { UIApplication.shared.applicationState == .active }
    private static let rapidPollInterval: TimeInterval = 2.0
    private static let foregroundPollInterval: TimeInterval = 10.0
    private static let normalPollInterval: TimeInterval = 300.0 // 5 min (background sharing)
    private static let maintenancePollInterval: TimeInterval = 30 * 60  // ack-only when not sharing
    private static let staleLocationThreshold: TimeInterval = 60.0
    private static let locationSendThrottle: TimeInterval = 30.0
    static let minimumReportingDistanceMeters: CLLocationDistance = 200
    private static let rapidPollDuration: TimeInterval = 60.0
    var locationFixTimeout: TimeInterval = 10.0  // internal for testing
    /// Fixes with horizontalAccuracy above this threshold are cell/WiFi network fixes too noisy
    /// to broadcast; only sub-200m GPS fixes are sent to friends or used for heartbeats.
    static let minBroadcastAccuracyMeters: CLLocationAccuracy = 200
    private var visibleUsersCancellables = Set<AnyCancellable>()
    let pathMonitor = NWPathMonitor()  // internal for testing
    private let monitorQueue = DispatchQueue(label: "NWPathMonitorQueue")

    private var lastSentLocation: (lat: Double, lng: Double)? = nil

    /// Best available location for heartbeat sends: accurate GPS fix first, then last sent.
    /// Low-accuracy network fixes (e.g. from stationary cell-tower positioning) are skipped
    /// so a 3km drift doesn't overwrite a precise known location in friends' maps.
    private var bestAvailableLocation: (lat: Double, lng: Double, heading: Double?)? {
        if let loc = locationProvider.lastLocation, loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= Self.minBroadcastAccuracyMeters {
            return (lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, heading: (locationProvider as? LocationManager)?.heading)
        }
        if let last = lastSentLocation {
            return (lat: last.lat, lng: last.lng, heading: ownHeading)
        }
        return nil
    }
    var lastSentTime: Date = Date(timeIntervalSince1970: 0)  // internal for testing
    var pendingForcedSendAfterPairing: Bool = false
    var pendingForcedSendFriendId: String? = nil
    var forceNextLocationUpdate: Bool = false
    var skipNetworkRestore: Bool = false  // internal for testing
    var locationFixTimeoutTask: Task<Void, Never>? = nil  // internal for testing
    var currentSendTask: Task<Void, Never>? = nil  // internal for testing
    // Injectable sleep used for the GPS-fix fallback timeout. Tests replace this
    // with an instant or controllable variant so they don't wait real walltime.
    var sleepForFixTimeout: @Sendable (TimeInterval) async throws -> Void = { seconds in
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
    private var awaitingFirstUpdateIds: Set<String> = []
    // Monotonically increasing counter used to prevent stale task cleanup from
    // clearing a newer task's state. Incremented each time a new send task is created.
    private var sendTaskGeneration: Int = 0

    // Injected for testing. Closures are @Sendable and use MainActor.assumeIsolated internally
    // to interact with UIApplication.shared, which is required for background tasks in Swift 6.
    var beginBackgroundTask: @Sendable (String, @Sendable @escaping () -> Void) -> UIBackgroundTaskIdentifier = { name, handler in
        MainActor.assumeIsolated {
            UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: handler)
        }
    }
    var endBackgroundTask: @Sendable (UIBackgroundTaskIdentifier) -> Void = { identifier in
        MainActor.assumeIsolated {
            UIApplication.shared.endBackgroundTask(identifier)
        }
    }

    let e2eeManager: Shared.E2eeManager
    let userStore: Shared.UserStore
    let locationClient: LocationClientProtocol
    let locationProvider: LocationProviding
    private let motionActivityManager = CMMotionActivityManager()
    private let motionQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "net.af0.where.motion"
        queue.maxConcurrentOperationCount = 1
        return queue
    }()

    init(e2eeManager: Shared.E2eeManager? = nil, userStore: Shared.UserStore? = nil, locationClient: LocationClientProtocol? = nil, locationProvider: LocationProviding? = nil) {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")

        let keychain = KeychainRawKeyValueStorage()
        let driver = IosSqlDriverKt.createIosSqlDriver()
        let store = e2eeManager ?? Shared.E2eeManager(sqlDriver: driver)
        self.e2eeManager = store
        let userStoreValue = userStore ?? Shared.UserStore(storage: keychain)
        self.userStore = userStoreValue
        self.locationClient = locationClient ?? Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)
        self.locationProvider = locationProvider ?? LocationManager.shared

        self.isSharingLocation = (userStoreValue.isSharingLocation.value as? Shared.KotlinBoolean)?.boolValue ?? true
        self.displayName = userStoreValue.displayName.value as? String ?? ""
        self.pausedFriendIds = Set(userStoreValue.pausedFriendIds.value as? Set<String> ?? [])
        self.sharingExpiresAt = (userStoreValue.sharingExpiresAt.value as? Shared.KotlinLong)?.int64Value

        Task { @MainActor in
            do {
                try await store.cleanupExpiredInvites(expirySeconds: 48 * 3600)
                self.friends = try await store.listFriends()
                self.pendingInvites = try await store.listPendingInvites()
                var initialLocations: [String: (lat: Double, lng: Double, ts: Int64)] = [:]
                var initialLastPing: [String: Date] = [:]
                for friend in self.friends {
                    if let lat = friend.lastLat?.doubleValue, let lng = friend.lastLng?.doubleValue, let ts = friend.lastTs?.int64Value {
                        initialLocations[friend.id] = (lat: lat, lng: lng, ts: ts)
                        initialLastPing[friend.id] = Date(timeIntervalSince1970: TimeInterval(ts))
                    }
                }
                self.friendLocations = initialLocations
                self.friendLastPing = initialLastPing
                self.updateVisibleUsers()
            } catch {
                logger.error("Failed to load initial friends: \(error.localizedDescription)")
            }
            self.isDataLoaded = true
            // Apply any persisted time-limited share expiry (or stop now if past).
            self.scheduleSharingExpiryTask()
            // Start polling AFTER friends/invites are loaded to ensure targetPollInterval is correct.
            self.startPolling()
        }

        // Fire sharingStateChanged() once when the initial DB load completes, so a background
        // wake-up that arrived before hydration finished can start GPS updates.
        $isDataLoaded
            .filter { $0 }
            .first()
            .sink { [weak self] _ in
                self?.locationProvider.sharingStateChanged()
            }
            .store(in: &visibleUsersCancellables)

        // Re-derive friendStoppedAt / friendStationarySince from the source of truth
        // (FriendEntry rows) whenever `friends` changes. E2eeManager.processBatch
        // persists both fields atomically with lastLat/lastLng/etc., so a refresh of
        // `friends = try await e2eeManager.listFriends()` picks up every transition.
        $friends
            .sink { [weak self] list in
                guard let self = self else { return }
                var stationary: [String: Int64] = [:]
                var stopped: [String: Int64] = [:]
                for friend in list {
                    if let ts = friend.stationarySinceTs?.int64Value {
                        stationary[friend.id] = ts
                    }
                    if let ts = friend.stoppedAtTs?.int64Value {
                        stopped[friend.id] = ts
                    }
                }
                self.friendStationarySince = stationary
                self.friendStoppedAt = stopped
            }
            .store(in: &visibleUsersCancellables)

        // Subscribe to updates on friendLocations, isSharingLocation, and user location

        pathMonitor.pathUpdateHandler = { [weak self] path in
            if path.status == .satisfied {
                logger.debug("Network path satisfied, triggering syncNow() and location send")
                Task { @MainActor [weak self] in
                    guard let self = self, !self.skipNetworkRestore else { return }
                    await self.handleNetworkRestored()
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }

    func setDisplayName(name: String) {
        displayName = name
    }

    func toggleSharing() {
        if isSharingLocation {
            stopSharing()
        } else {
            startSharing(expiresAt: nil)
        }
    }

    /// Turn sharing on with an optional automatic expiry (epoch-seconds, nil = no expiry).
    func startSharing(expiresAt: Int64?) {
        sharingExpiresAt = expiresAt
        userStore.setSharingExpiresAt(epochSeconds: expiresAt.map { KotlinLong(value: $0) })
        if !isSharingLocation {
            isSharingLocation = true
        }
        scheduleSharingExpiryTask()
    }

    /// Canonical stop-sharing path: enqueue StoppedSharing to every active friend, clear
    /// any pending time-limited expiry, then flip `isSharingLocation` (which fires didSet
    /// for state mirroring). Mirrors Android's `LocationViewModel.setSharing(false, null)`.
    /// Callers MUST use this instead of writing `isSharingLocation = false` directly;
    /// otherwise the StoppedSharing message and expiry cleanup are silently skipped.
    func stopSharing() {
        sharingExpiresAt = nil
        sharingExpiryTask?.cancel()
        sharingExpiryTask = nil
        userStore.setSharingExpiresAt(epochSeconds: nil)
        // Enqueue StoppedSharing to every active friend so their UI gets a positive
        // "stopped" signal. Keepalives continue afterwards so the session does not go stale.
        let paused = pausedFriendIds
        Task {
            do {
                try await locationClient.sendStoppedSharing(pausedFriendIds: paused)
            } catch {
                logger.warning("sendStoppedSharing failed: \(error.localizedDescription)")
            }
        }
        if isSharingLocation {
            isSharingLocation = false
        }
    }

    /// Apply the persisted sharing expiry on launch: if it's already past, stop immediately;
    /// otherwise schedule an automatic stop at expiry. Idempotent.
    func scheduleSharingExpiryTask() {
        sharingExpiryTask?.cancel()
        guard let expiresAt = sharingExpiresAt, isSharingLocation else { return }
        let nowSec = Int64(Date().timeIntervalSince1970)
        let remaining = expiresAt - nowSec
        if remaining <= 0 {
            logger.info("scheduleSharingExpiryTask: already expired, stopping immediately")
            stopSharing()
            return
        }
        sharingExpiryTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: UInt64(remaining) * 1_000_000_000)
                guard let self = self else { return }
                if !Task.isCancelled && self.sharingExpiresAt == expiresAt && self.isSharingLocation {
                    logger.info("scheduleSharingExpiryTask: expiry reached, stopping sharing")
                    self.stopSharing()
                }
            } catch {
                // Cancelled
            }
        }
    }

    func togglePauseFriend(id: String) {
        if pausedFriendIds.contains(id) {
            pausedFriendIds.remove(id)
        } else {
            pausedFriendIds.insert(id)
        }
    }

    func createInvite() async {
        guard pendingInitPayload == nil && pendingQrForNaming == nil else {
            logger.debug("createInvite: pairing already in flight, skipping")
            return
        }
        do {
            let qr = try await e2eeManager.createInvite(suggestedName: displayName)
            inviteState = Shared.InviteState.Pending(qr: qr)
            isInviteSheetShowing = true
            triggerRapidPoll()
        } catch {
            logger.error("Failed to create invite: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    private func resetRapidPoll() {
        lastRapidPollTrigger = Date(timeIntervalSince1970: 0)
        awaitingFirstUpdateIds.removeAll()
    }

    // Extracted for testability. Called from pathMonitor handler and directly in tests.
    func handleNetworkRestored() async {
        do {
            try await locationClient.syncNow()
        } catch {
            logger.error("syncNow failed on path update: \(error.localizedDescription)")
        }

        guard isSharingLocation else { return }

        let locationIsStale = (locationProvider.lastLocation?.timestamp.timeIntervalSinceNow ?? -.infinity) < -Self.staleLocationThreshold
        if locationIsStale {
            forceNextLocationUpdate = true
            locationProvider.requestImmediateLocation()
            // Cancel any previous fallback timeout before starting a new one.
            locationFixTimeoutTask?.cancel()
            let sleep = sleepForFixTimeout
            locationFixTimeoutTask = Task { [weak self] in
                do {
                    guard let timeout = self?.locationFixTimeout else { return }
                    try await sleep(timeout)
                } catch {
                    logger.debug("locationFixTimeout task cancelled")
                    return
                }
                guard let self = self, self.forceNextLocationUpdate else { return }
                logger.info("requestImmediateLocation timeout: sending stale fix as fallback")
                self.forceNextLocationUpdate = false
                if let loc = self.bestAvailableLocation {
                    self.sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .network)
                }
            }
        } else if let loc = bestAvailableLocation {
            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, source: .network)
        }
    }

    func triggerRapidPoll() {
        lastRapidPollTrigger = Date()
        wakePoll()
    }

    func wakePoll() {
        if isPollInFlight { return }
        pollTimer?.fire()
    }

    func onForegroundEntry() {
        // Reset lastPollTime to .distantPast so tick() bypasses its interval guard and fires
        // a poll immediately. A stuck isPollInFlight is handled by pollAll()'s watchdog task.
        lastPollTime = .distantPast
        // Proactively send own location so friends see us immediately (subject to 30s throttle).
        // sendLocation() updates lastSentTime synchronously, so pollAll()'s heartbeat guard
        // (elapsed >= 300s) will not fire a second send even if a heartbeat was overdue.
        if isSharingLocation, let loc = bestAvailableLocation {
            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, source: .manual)
        }
        // Request a fresh high-accuracy fix; result arrives via didUpdateLocations.
        forceNextLocationUpdate = true
        locationProvider.requestImmediateLocation()
        // Fire a poll directly rather than through the timer to minimize foreground latency.
        // Tracked so tests can await the poll completion without sleeping.
        foregroundPollTask = Task { @MainActor in
            await pollAll(updateUi: true, source: .manual)
        }
    }

    var foregroundPollTask: Task<Void, Never>? = nil  // internal for testing

    func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task { @MainActor in
                self.tick()
            }
        }
    }

    private func tick() {
        if isPollInFlight { return }

        let now = Date()
        let interval = targetPollInterval()

        if now.timeIntervalSince(lastPollTime) >= interval {
            Task {
                await pollAll()
            }
        }
        // Heartbeat sends are handled inside pollAll() so they fire on every wakeup
        // path (CLLocation callbacks, visit monitoring, background-app-refresh), not
        // just on this 1-second timer tick.
    }

    @MainActor
    func targetPollInterval() -> TimeInterval {
        if isRapidPolling() { return Self.rapidPollInterval }
        if isInForeground() { return Self.foregroundPollInterval }
        if friends.isEmpty && pendingInvites.isEmpty { return Self.maintenancePollInterval }
        return isSharingLocation ? Self.normalPollInterval : Self.maintenancePollInterval
    }

    @MainActor
    func isRapidPolling() -> Bool {
        if !awaitingFirstUpdateIds.isEmpty { return true }
        if isInviteSheetShowing { return true }
        return Date().timeIntervalSince(lastRapidPollTrigger) < Self.rapidPollDuration
    }

    func firePoll() async {
        await pollAll()
    }

    func markCurrentInviteExported() async {
        guard let qr = (inviteState as? Shared.InviteState.Pending)?.qr else { return }
        do {
            try await e2eeManager.markInviteExported(ekPub: qr.ekPub)
            pendingInvites = try await e2eeManager.listPendingInvites()
        } catch {
            logger.error("Failed to mark invite exported: \(error.localizedDescription)")
        }
    }

    func clearInviteIfNotExported() async {
        guard let pending = inviteState as? Shared.InviteState.Pending else { return }
        
        // Refresh pendingInvites to get latest exportedAt state
        do {
            let list = try await e2eeManager.listPendingInvites()
            if let current = list.first(where: { toSwiftData($0.qrPayload.ekPub) == toSwiftData(pending.qr.ekPub) }) {
                if current.exportedAt == nil {
                    await clearInvite(ekPub: toSwiftData(pending.qr.ekPub))
                }
            }
        } catch {
            logger.error("Failed to check if invite was exported: \(error.localizedDescription)")
        }
        inviteState = Shared.InviteState.None()
    }

    private func onFriendLocationReceived(friendId: String) {
        if awaitingFirstUpdateIds.remove(friendId) != nil {
            if awaitingFirstUpdateIds.isEmpty {
                resetRapidPoll()
            }
        }
    }

    private var lastSuccessfulSendTime: Date? = nil

    private func logReliability(source: WakeSource, success: Bool, interval: TimeInterval? = nil) {
        let status = success ? "OK" : "ERR"
        var message = "Wake: \(source.rawValue) -> \(status)"
        if let interval = interval {
            let mins = Int(interval) / 60
            let secs = Int(interval) % 60
            if mins > 0 {
                message += " (Interval: \(mins)m \(secs)s)"
            } else {
                message += " (Interval: \(secs)s)"
            }
        }
        e2eeManager.addDiagnosticEvent(message: message, coalesceKey: nil)
    }

    private func isStationary() async -> Bool {
        guard CMMotionActivityManager.isActivityAvailable() else {
            return false
        }

        switch CMMotionActivityManager.authorizationStatus() {
        case .denied, .restricted:
            logger.warning("Motion activity permission denied/restricted; skipping stationarity check")
            return false
        case .notDetermined:
            // Permission prompt has not been shown yet. Skip the query so we don't
            // inadvertently trigger the system permission dialog during a background wake.
            return false
        case .authorized:
            break
        @unknown default:
            return false
        }

        return await withCheckedContinuation { [weak self] continuation in
            guard let self = self else {
                continuation.resume(returning: false)
                return
            }

            let now = Date()
            let sixtySecondsAgo = now.addingTimeInterval(-60)

            self.motionActivityManager.queryActivityStarting(from: sixtySecondsAgo, to: now, to: self.motionQueue) { activities, error in
                if let _ = error {
                    continuation.resume(returning: false)
                    return
                }

                let stationary = activities?.last(where: { $0.confidence != .low })?.stationary ?? false
                continuation.resume(returning: stationary)
            }
        }
    }

    func pollAll(updateUi: Bool = true, source: WakeSource = .timer) async {
        if isPollInFlight { return }
        isPollInFlight = true
        lastPollTime = Date()

        // Watchdog: if the KMP coroutine hangs without returning or throwing (e.g. stalled
        // socket that never reaches a cancellation checkpoint), the defer block never fires and
        // isPollInFlight would stick permanently. Reset it after 90s regardless.
        let watchdog = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 90_000_000_000)
            guard let self, self.isPollInFlight else { return }
            logger.warning("pollAll: watchdog resetting stuck isPollInFlight")
            self.isPollInFlight = false
        }
        logger.debug("Polling for location updates (updateUi=\(updateUi), source=\(source.rawValue))")

        if Date().timeIntervalSince(lastCleanupTime) > 3600 {
            do {
                try await e2eeManager.cleanupExpiredInvites(expirySeconds: 48 * 3600)
                lastCleanupTime = Date()
            } catch {
                logger.error("Failed to cleanup expired invites: \(error.localizedDescription)")
            }
        }

        let identifier = self.beginBackgroundTask("PollAll") {
            // Task expired
        }

        defer {
            watchdog.cancel()
            isPollInFlight = false
            if identifier != .invalid {
                self.endBackgroundTask(identifier)
            }
        }
        do {
            let updates = try await locationClient.poll(isForeground: isInForeground(), pausedFriendIds: pausedFriendIds)
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                do {
                    try await e2eeManager.updateLastLocation(id: update.userId, lat: update.lat, lng: update.lng, ts: update.timestamp)
                } catch {
                    logger.error("Failed to update last location for \(update.userId): \(error.localizedDescription)")
                }
                friendLocations[update.userId] = (lat: update.lat, lng: update.lng, ts: update.timestamp)
                friendLastPing[update.userId] = Date(timeIntervalSince1970: TimeInterval(update.timestamp))
                // friendStationarySince / friendStoppedAt are derived from FriendEntry rows
                // (persisted by E2eeManager.processBatch in the same transaction as lastLat/etc.).
                // The refresh happens via the $friends sink in init.
                onFriendLocationReceived(friendId: update.userId)
            }

            friends = try await e2eeManager.listFriends()
            diagnosticLog = e2eeManager.diagnosticLogSnapshot()
            pendingInvites = try await e2eeManager.listPendingInvites()
            // Always update visibleUsers to ensure map is fresh when returning to foreground.
            updateVisibleUsers()

            if updateUi || isInviteSheetShowing {
                _ = try await pollPendingInvites()
            }

            // Heartbeat: if we're awake enough to poll, also send location if one is due.
            // This covers wakeups that don't go through tick() (e.g. didUpdateLocations,
            // background-app-refresh, or any direct pollAll() call).
            let elapsed = Date().timeIntervalSince(lastSentTime)
            let sharing = isSharingLocation
            logger.info("pollAll: sharing=\(sharing) elapsed=\(Int(elapsed))s")
            if isSharingLocation {
                if elapsed >= Self.normalPollInterval {
                    let stationary = await isStationary()
                    if stationary {
                        logger.info("pollAll: heartbeat due — stationary, re-reporting cached location")
                        if let loc = bestAvailableLocation {
                            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .heartbeat)
                        }
                    } else {
                        logger.info("pollAll: heartbeat due — moving, requesting fresh fix")
                        forceNextLocationUpdate = true
                        if let loc = bestAvailableLocation {
                            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .heartbeat)
                        }
                        locationProvider.requestImmediateLocation()
                    }
                }
            }

            updateStatus(nil)
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    @discardableResult
    private func pollPendingInvites() async throws -> [Shared.PendingInviteResult] {
        let results = try await locationClient.pollPendingInvites()
        if results.isEmpty { return [] }

        let pendingInvites = try await e2eeManager.listPendingInvites()
        let filteredResults = results.filter { result in
            pendingInvites.contains { invite in
                toSwiftData(invite.qrPayload.ekPub) == toSwiftData(result.inviteEkPub)
            }
        }

        if filteredResults.isEmpty {
            logger.debug("pollPendingInvites: received \(results.count) results, but none match active pending invites. Ignoring.")
            return []
        }

        // If we already have a naming dialog up, don't overwrite it.
        if pendingInitPayload == nil {
            if let result = filteredResults.first {
                if let error = result.pairingError {
                    updateStatus(NSError(domain: "net.af0.where", code: 1, userInfo: [NSLocalizedDescriptionKey: error]))
                    isInviteSheetShowing = false
                    return filteredResults
                }
                pendingInitPayload = result.payload
                pendingInitAliceEkPub = toSwiftData(result.inviteEkPub) // THE FIX: Use our own EK from the invite
                multipleScansDetected = result.multipleScansDetected
                inviteState = Shared.InviteState.None()
                isInviteSheetShowing = false
                triggerRapidPoll()
            }
        }
        return filteredResults
    }

    func clearInvite(ekPub: Data? = nil) async {
        do {
            let ekPubToClear = ekPub ?? pendingInitAliceEkPub
            if let ekPubToClear = ekPubToClear {
                try await e2eeManager.clearInvite(ekPub: kotlinByteArray(from: ekPubToClear))
            } else if inviteState is Shared.InviteState.Pending {
                // If we are currently showing a QR but no one has scanned it yet,
                // clear that specific one.
                if let qr = (inviteState as? Shared.InviteState.Pending)?.qr {
                    try await e2eeManager.clearInvite(ekPub: qr.ekPub)
                }
            }
            pendingInvites = try await e2eeManager.listPendingInvites()
        } catch {
            logger.error("Failed to clear invite: \(error.localizedDescription)")
        }
        resetRapidPoll()
        inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        pendingInitAliceEkPub = nil
        pendingInitPayload = nil
        locationProvider.sharingStateChanged()
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = Shared.QrPayload.Companion.shared.fromUrl(url: url) else {
            updateStatus(NSError(domain: "Where", code: 400, userInfo: [NSLocalizedDescriptionKey: MR.strings().invalid_qr_code.localized()]))
            return false
        }
        inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        pendingQrForNaming = qr
        triggerRapidPoll()
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) async {
        pendingQrForNaming = nil
        inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false

        let qrWithName = Shared.QrPayload(protocolVersion: Shared.ProtocolConstantsKt.PROTOCOL_VERSION, 
            ekPub: qr.ekPub,
            suggestedName: friendName,
            fingerprint: qr.fingerprint,
            discoverySecret: qr.discoverySecret
        )
        debugLog { "Scanning QR: discovery=\(qrWithName.discoveryToken().toHex()), friendName=\(friendName)" }
        inviteTask?.cancel()
        isExchanging = true
        triggerRapidPoll()

        defer { isExchanging = false }
        do {
            let result = try await e2eeManager.processScannedQr(qr: qrWithName, bobSuggestedName: displayName)
            guard let initPayload = result.first, let bobEntry = result.second else {
                logger.error("processScannedQr returned nil components")
                updateStatus(NSError(domain: "Where", code: -1, userInfo: [NSLocalizedDescriptionKey: MR.strings().pairing_failed.localized()]))
                return
            }

            await clearInvite()
            // Insert AFTER clearInvite: clearInvite calls resetRapidPoll which clears
            // awaitingFirstUpdateIds, so inserting before it is a no-op.
            awaitingFirstUpdateIds.insert(bobEntry.id)
            friends = try await e2eeManager.listFriends()
            updateVisibleUsers()

            do {
                debugLog { "Posting KeyExchangeInit" }
                try await locationClient.postKeyExchangeInit(friendId: bobEntry.id, qr: qrWithName, initPayload: initPayload)
                debugLog { "KeyExchangeInit posted successfully" }

                if let last = locationProvider.lastLocation {
                    // Proactively send our first location update to Alice to trigger confirmation on her side.
                    // We use sendLocationToFriend here to bypass the !isConfirmed check in sendLocation().
                    do {
                        try await locationClient.sendLocationToFriend(friendId: bobEntry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: false)
                    } catch {
                        logger.error("Failed to send proactive location update to \(bobEntry.id): \(error.localizedDescription)")
                    }
                    self.lastSentTime = Date()
                } else {
                    logger.debug("confirmQrScan: lastLocation is nil, setting pendingForcedSendAfterPairing")
                    self.pendingForcedSendAfterPairing = true
                    self.pendingForcedSendFriendId = bobEntry.id
                    locationProvider.requestPermissionAndStart()
                }
                await pollAll(updateUi: true)
                friends = try await e2eeManager.listFriends()
                updateVisibleUsers()
                triggerRapidPoll()

                updateStatus(nil)
            } catch {
                logger.error("Failed to post init: \(error.localizedDescription)")
                updateStatus(error)
            }
        } catch {
            logger.error("confirmQrScan failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func confirmPendingInit(payload: Shared.KeyExchangeInitPayload, name: String) async {
        guard let aliceEkPub = pendingInitAliceEkPub else { return }
        inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        isExchanging = true
        pendingInitPayload = nil
        pendingInitAliceEkPub = nil
        multipleScansDetected = false

        defer { isExchanging = false }
        do {
            let result = try await e2eeManager.processKeyExchangeInit(payload: payload, aliceSuggestedName: name, aliceEkPub: kotlinByteArray(from: aliceEkPub))

            if let entry = result ?? nil {
                awaitingFirstUpdateIds.insert(entry.id)
                friends = try await e2eeManager.listFriends()
                updateVisibleUsers()

                if let last = locationProvider.lastLocation {
                    // Proactively send our first location update to Bob to trigger confirmation on his side.
                    do {
                        try await locationClient.sendLocationToFriend(friendId: entry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: false)
                    } catch {
                        logger.error("Failed to send reactive location update to \(entry.id): \(error.localizedDescription)")
                    }
                    self.lastSentTime = Date()
                } else {
                    self.pendingForcedSendAfterPairing = true
                    self.pendingForcedSendFriendId = entry.id
                    locationProvider.requestPermissionAndStart()
                }
            }
            triggerRapidPoll()
            locationProvider.sharingStateChanged()
            friends = try await e2eeManager.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("confirmPendingInit failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func cancelPendingInit() async {
        let hasInviteState = !(inviteState is Shared.InviteState.None)
        guard pendingInitPayload != nil || hasInviteState || pendingInitAliceEkPub != nil else { return }
        
        let ekPubToClear = pendingInitAliceEkPub
        pendingInitPayload = nil
        multipleScansDetected = false
        pendingInitAliceEkPub = nil
        
        await clearInvite(ekPub: ekPubToClear)
    }

    func renameFriend(id: String, newName: String) async {
        do {
            try await e2eeManager.renameFriend(id: id, newName: newName)
            friends = try await e2eeManager.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("Failed to rename friend: \(error.localizedDescription)")
        }
    }

    func removeFriend(id: String) async {
        do {
            try await e2eeManager.deleteFriend(id: id)
            pausedFriendIds.remove(id)
            friends = try await e2eeManager.listFriends()
            friendLocations.removeValue(forKey: id)
            friendLastPing.removeValue(forKey: id)
            updateVisibleUsers()
            locationProvider.sharingStateChanged()
        } catch {
            logger.error("Failed to remove friend: \(error.localizedDescription)")
        }
    }

    func sendLocationOnBackground() {
        guard isSharingLocation else { return }
        guard let loc = bestAvailableLocation else {
            logger.info("sendLocationOnBackground: skipped — no location available")
            return
        }
        logger.info("sendLocationOnBackground: sending location before app suspends")
        sendLocation(lat: loc.lat, lng: loc.lng, source: .backgroundEntry)
    }

    func sendLocation(lat: Double, lng: Double, heading: Double? = nil, force: Bool = false, source: WakeSource = .locationUpdate, stationary: Bool = false) {
        let now = Date()

        // Software Distance Filter: avoid excessive updates if we haven't moved much,
        // unless this is a forced update (heartbeat, manual, etc).
        //
        // Note: while LocationManager also sets a hardware distanceFilter=50, that only
        // affects didUpdateLocations callbacks. This software check also covers other
        // wake sources (network restore, visits, etc) and ensures we follow the
        // "50m or 5-minute" reporting contract robustly.
        if !force, let last = lastSentLocation {
            let lastLoc = CLLocation(latitude: last.lat, longitude: last.lng)
            let newLoc = CLLocation(latitude: lat, longitude: lng)
            if newLoc.distance(from: lastLoc) < Self.minimumReportingDistanceMeters {
                // Still update the local heading even if we don't broadcast.
                // Note: heading-only updates are suppressed when stationary to save radio.
                ownHeading = heading
                return
            }
        }

        // If a friend-specific forced send is pending (set when location was unavailable at
        // pairing time), fire it now before the throttle check so the newly-paired peer
        // receives our location even if the throttle would otherwise suppress the broadcast.
        if let friendId = pendingForcedSendFriendId {
            pendingForcedSendFriendId = nil
            Task {
                do {
                    try await locationClient.sendLocationToFriend(friendId: friendId, lat: lat, lng: lng, stationary: false)
                } catch {
                    logger.error("Failed forced pairing send to \(friendId): \(error.localizedDescription)")
                }
            }
        }

        let timeSinceLast = now.timeIntervalSince(lastSentTime)

        var forceUpdate = force
        if source == .locationUpdate && forceNextLocationUpdate {
            forceUpdate = true
            forceNextLocationUpdate = false
        }

        // Throttle: avoid excessive updates unless forced.
        if !forceUpdate && timeSinceLast < Self.locationSendThrottle {
            // Still update the local heading even if we don't send to the server.
            ownHeading = heading
            return
        }

        let interval = lastSuccessfulSendTime.map { now.timeIntervalSince($0) }
        ownHeading = heading
        lastSentTime = now
        pendingForcedSendAfterPairing = false

        let identifier = self.beginBackgroundTask("SendLocation") {
            // Task expired
        }
        let gen = sendTaskGeneration + 1
        sendTaskGeneration = gen

        currentSendTask?.cancel()
        currentSendTask = Task {
            defer {
                if identifier != .invalid {
                    self.endBackgroundTask(identifier)
                }
            }
            do {
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds, stationary: stationary)
                if !Task.isCancelled && gen == self.sendTaskGeneration {
                    logger.info("sendLocation: succeeded (lat=\(lat), lng=\(lng), source=\(source.rawValue))")
                    logReliability(source: source, success: true, interval: interval)
                    lastSuccessfulSendTime = now
                    // Software Distance Filter Baseline: only update the baseline when a send
                    // successfully completes. This ensures that if a transmission fails, the
                    // next update (even if it's <50m from this failed one) will still be
                    // eligible for broadcast.
                    self.lastSentLocation = (lat: lat, lng: lng)
                    updateStatus(nil)
                }
            } catch {
                if !Task.isCancelled && gen == self.sendTaskGeneration {
                    logger.error("Failed to send location: \(error.localizedDescription)")
                    logReliability(source: source, success: false, interval: interval)
                    updateStatus(error)
                }
            }
        }
    }

    private func updateStatus(_ error: Error?) {
        if let error = error {
            connectionStatus = Shared.ConnectionStatus.Error(message: RawStringDesc(error.localizedDescription))
        } else {
            connectionStatus = Shared.ConnectionStatus.Ok()
        }
    }

    func updateVisibleUsers() {
        if skipUpdateVisibleUsers { return }

        // Skip updating visible users if not sharing location AND app is in background.
        // This is a power optimization.
        if !isSharingLocation && !isInForeground() {
            visibleUsers = []
            return
        }

        var updates: [Shared.UserLocation] = []
        for (id, loc) in friendLocations {
            if pausedFriendIds.contains(id) { continue }
            let stationary = friendStationarySince[id] != nil
            updates.append(Shared.UserLocation(userId: id, lat: loc.lat, lng: loc.lng, timestamp: loc.ts, stationary: stationary))
        }
        visibleUsers = updates
    }
}

private extension UIBackgroundTaskIdentifier {
    func end() {
        if self != .invalid {
            MainActor.assumeIsolated {
                UIApplication.shared.endBackgroundTask(self)
            }
        }
    }
}
