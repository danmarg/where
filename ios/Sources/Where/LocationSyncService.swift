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
    func sendStoppedSharingToFriend(friendId: String) async throws
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

    let repo: FriendSyncRepository
    private var repoSink: AnyCancellable?

    @Published var connectionStatus: Shared.ConnectionStatus = Shared.ConnectionStatus.Ok()
    @Published var isDataLoaded: Bool = false
    /// Mirror of `userStore.isSharingLocation` for SwiftUI binding. Read freely, but to
    /// STOP sharing callers must use `stopSharing()` rather than assignment — that path
    /// also enqueues the StoppedSharing fan-out. didSet handles only state mirroring.
    @Published var isSharingLocation: Bool {
        didSet {
            userStore.setSharing(sharing: isSharingLocation)
            locationProvider.sharingStateChanged()
            if !isSharingLocation {
                forceNextLocationUpdate = false
                locationFixTimeoutTask?.cancel()
                locationFixTimeoutTask = nil
            } else if !oldValue {
                // Master toggle off→on: broadcast our current location immediately to every
                // (non-paused) friend so peers don't wait for the next regular tick. Also
                // request a fresh fix so the next update sends an up-to-date position.
                if let loc = bestAvailableLocation {
                    sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .manual)
                }
                forceNextLocationUpdate = true
                locationProvider.requestImmediateLocation()
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

    @Published var ownHeading: Double? = nil
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
        try await Task.sleep(for: .seconds(seconds))
    }
    // Queries whether the device is stationary. Default queries CoreMotion;
    // tests replace with { true } or { false } for deterministic results.
    // Initialized in init() to capture motionActivityManager/motionQueue without
    // a self reference, avoiding actor-isolation issues in the closure.
    var isStationaryQuery: () async -> Bool = { false }  // overridden in init
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
        let repoValue = FriendSyncRepository(e2eeManager: store, userStore: userStoreValue)
        self.repo = repoValue

        self.isSharingLocation = (userStoreValue.isSharingLocation.value as? Shared.KotlinBoolean)?.boolValue ?? true
        self.displayName = userStoreValue.displayName.value as? String ?? ""

        // Capture manager and queue by value so the closure needs no self reference,
        // keeping it actor-isolation-free and trivially replaceable in tests.
        let motionManager = self.motionActivityManager
        let motionQueue = self.motionQueue
        self.isStationaryQuery = {
            guard CMMotionActivityManager.isActivityAvailable() else { return false }
            switch CMMotionActivityManager.authorizationStatus() {
            case .denied, .restricted:
                logger.warning("Motion activity permission denied/restricted; skipping stationarity check")
                return false
            case .notDetermined:
                return false
            case .authorized:
                break
            @unknown default:
                return false
            }
            return await withCheckedContinuation { continuation in
                let now = Date()
                motionManager.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: motionQueue) { activities, error in
                    if error != nil {
                        continuation.resume(returning: false)
                        return
                    }
                    let stationary = activities?.last(where: { $0.confidence != .low })?.stationary ?? false
                    continuation.resume(returning: stationary)
                }
            }
        }

        // Forward repo's @Published changes to our own observers so the existing
        // `service.foo` forwarders continue to drive SwiftUI updates.
        self.repoSink = repoValue.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }

        Task { @MainActor in
            await repoValue.loadInitialState()
            self.updateVisibleUsers()
            self.isDataLoaded = true
            // Apply any persisted per-friend share timer (or fire if already past).
            self.rescheduleFriendExpiryTask()
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

        // Subscribe to updates on isSharingLocation, and user location

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
            isSharingLocation = true
        }
    }

    /// Canonical stop-sharing path: enqueue StoppedSharing to every active friend, then flip
    /// `isSharingLocation`. Mirrors Android's `LocationViewModel.setSharing(false)`.
    /// Callers MUST use this instead of writing `isSharingLocation = false` directly,
    /// otherwise the StoppedSharing fan-out is silently skipped.
    func stopSharing() {
        let paused = effectivelyPausedIds()
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

    /// Set or clear a per-friend share timer. nil clears (= share indefinitely).
    func setFriendExpiry(friendId: String, epochSeconds: Int64?) {
        userStore.setFriendExpiry(friendId: friendId, epochSeconds: epochSeconds.map { KotlinLong(value: $0) })
        if let v = epochSeconds {
            repo.friendExpiresAt[friendId] = v
        } else {
            repo.friendExpiresAt.removeValue(forKey: friendId)
        }
        rescheduleFriendExpiryTask()
    }

    /// Re-arms the (single) timer for the soonest expiry. When it fires, the affected
    /// friend gets paused locally and a StoppedSharing message is enqueued. This is the
    /// fast path; the hard guarantee is that send-paths gate on effectivelyPausedIds()
    /// which already factors in any elapsed expiry.
    func rescheduleFriendExpiryTask() {
        sharingExpiryTask?.cancel()
        guard let (friendId, expiresAt) = repo.friendExpiresAt.min(by: { $0.value < $1.value }) else { return }
        let nowSec = Int64(Date().timeIntervalSince1970)
        let remaining = max(0, expiresAt - nowSec)
        sharingExpiryTask = Task { @MainActor [weak self] in
            do {
                if remaining > 0 {
                    try await Task.sleep(for: .seconds(remaining))
                }
                guard let self = self, !Task.isCancelled else { return }
                guard self.repo.friendExpiresAt[friendId] == expiresAt else { return }
                self.fireFriendExpiry(friendId: friendId)
            } catch {
                // Cancelled
            }
        }
    }

    private func fireFriendExpiry(friendId: String) {
        userStore.setFriendExpiry(friendId: friendId, epochSeconds: nil)
        repo.friendExpiresAt.removeValue(forKey: friendId)
        // togglePauseFriend handles the StoppedSharing fan-out on a pause-transition.
        // If the friend is already paused, Bob already got the message — no double-send.
        if !repo.pausedFriendIds.contains(friendId) {
            togglePauseFriend(id: friendId)
        }
        rescheduleFriendExpiryTask()
    }

    /// Source-of-truth set of friend ids that must not receive Location messages, combining
    /// the user's explicit pause list with any elapsed per-friend timer.
    func effectivelyPausedIds() -> Set<String> {
        repo.effectivelyPausedIds()
    }

    func togglePauseFriend(id: String) {
        let wasPaused = repo.pausedFriendIds.contains(id)
        if wasPaused {
            repo.pausedFriendIds.remove(id)
        } else {
            repo.pausedFriendIds.insert(id)
        }
        // On transition into paused, give the peer the same positive "stopped" signal
        // that master-off and per-friend-timer-expiry produce. On transition out of
        // paused, immediately broadcast our current location so the peer doesn't have
        // to wait up to a full heartbeat interval for the implicit "I'm back" update.
        if !wasPaused {
            Task {
                do {
                    try await locationClient.sendStoppedSharingToFriend(friendId: id)
                } catch {
                    logger.warning("sendStoppedSharingToFriend(\(id)) failed: \(error.localizedDescription)")
                }
            }
        } else if let last = locationProvider.lastLocation, isSharingLocation {
            Task {
                do {
                    try await locationClient.sendLocationToFriend(friendId: id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: locationProvider.isStationary)
                } catch {
                    logger.warning("sendLocationToFriend(\(id)) on unpause failed: \(error.localizedDescription)")
                }
            }
        }
    }

    func createInvite() async {
        guard repo.pendingInitPayload == nil && repo.pendingQrForNaming == nil else {
            logger.debug("createInvite: pairing already in flight, skipping")
            return
        }
        do {
            let qr = try await e2eeManager.createInvite(suggestedName: displayName)
            repo.inviteState = Shared.InviteState.Pending(qr: qr)
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
        if locationIsStale && locationProvider.isStationary {
            // Don't request a fresh fix when stationary: the position hasn't changed, and
            // requestImmediateLocation() → didUpdateLocations → resumeHighFidelityTracking()
            // would reset isStationary=false and send stationary:false to friends.
            if let loc = bestAvailableLocation {
                sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .network, stationary: true)
            }
        } else if locationIsStale {
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
                    self.sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .network, stationary: self.locationProvider.isStationary)
                }
            }
        } else if let loc = bestAvailableLocation {
            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, source: .network, stationary: locationProvider.isStationary)
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
        if repo.friends.isEmpty && repo.pendingInvites.isEmpty { return Self.maintenancePollInterval }
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
        guard let qr = (repo.inviteState as? Shared.InviteState.Pending)?.qr else { return }
        do {
            try await e2eeManager.markInviteExported(ekPub: qr.ekPub)
            repo.pendingInvites = try await e2eeManager.listPendingInvites()
        } catch {
            logger.error("Failed to mark invite exported: \(error.localizedDescription)")
        }
    }

    func clearInviteIfNotExported() async {
        guard let pending = repo.inviteState as? Shared.InviteState.Pending else { return }
        
        // Refresh repo.pendingInvites to get latest exportedAt state
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
        repo.inviteState = Shared.InviteState.None()
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

    func pollAll(updateUi: Bool = true, source: WakeSource = .timer) async {
        if isPollInFlight { return }
        isPollInFlight = true
        lastPollTime = Date()

        // Watchdog: if the KMP coroutine hangs without returning or throwing (e.g. stalled
        // socket that never reaches a cancellation checkpoint), the defer block never fires and
        // isPollInFlight would stick permanently. Reset it after 90s regardless.
        let watchdog = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(90))
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
            let updates = try await locationClient.poll(isForeground: isInForeground(), pausedFriendIds: effectivelyPausedIds())
            logger.debug("Got \(updates.count) location updates")
            for update in updates {
                do {
                    try await e2eeManager.updateLastLocation(id: update.userId, lat: update.lat, lng: update.lng, ts: update.timestamp)
                } catch {
                    logger.error("Failed to update last location for \(update.userId): \(error.localizedDescription)")
                }
                // Location/stationary/stopped state lives on FriendEntry rows (persisted by
                // E2eeManager.processBatch). The refresh below via listFriends() is the
                // single source of truth — no parallel store to update here.
                onFriendLocationReceived(friendId: update.userId)
            }

            repo.friends = try await e2eeManager.listFriends()
            repo.diagnosticLog = e2eeManager.diagnosticLogSnapshot()
            repo.pendingInvites = try await e2eeManager.listPendingInvites()
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
                    let stationary = await isStationaryQuery()
                    // Converge CoreMotion result into the provider's cached flag so
                    // background-entry and network-restore sends read a consistent value.
                    locationProvider.isStationary = stationary
                    if stationary {
                        logger.info("pollAll: heartbeat due — stationary, re-reporting cached location")
                        if let loc = bestAvailableLocation {
                            sendLocation(lat: loc.lat, lng: loc.lng, heading: loc.heading, force: true, source: .heartbeat, stationary: true)
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

        let pendingInvitesSnapshot = try await e2eeManager.listPendingInvites()
        let filteredResults = results.filter { result in
            pendingInvitesSnapshot.contains { invite in
                toSwiftData(invite.qrPayload.ekPub) == toSwiftData(result.inviteEkPub)
            }
        }

        if filteredResults.isEmpty {
            logger.debug("pollPendingInvites: received \(results.count) results, but none match active pending invites. Ignoring.")
            return []
        }

        // If we already have a naming dialog up, don't overwrite it.
        if repo.pendingInitPayload == nil {
            if let result = filteredResults.first {
                if let error = result.pairingError {
                    updateStatus(NSError(domain: "net.af0.where", code: 1, userInfo: [NSLocalizedDescriptionKey: error]))
                    isInviteSheetShowing = false
                    return filteredResults
                }
                repo.pendingInitPayload = result.payload
                repo.pendingInitAliceEkPub = toSwiftData(result.inviteEkPub) // THE FIX: Use our own EK from the invite
                repo.multipleScansDetected = result.multipleScansDetected
                repo.inviteState = Shared.InviteState.None()
                isInviteSheetShowing = false
                triggerRapidPoll()
            }
        }
        return filteredResults
    }

    func clearInvite(ekPub: Data? = nil) async {
        do {
            let ekPubToClear = ekPub ?? repo.pendingInitAliceEkPub
            if let ekPubToClear = ekPubToClear {
                try await e2eeManager.clearInvite(ekPub: kotlinByteArray(from: ekPubToClear))
            } else if repo.inviteState is Shared.InviteState.Pending {
                // If we are currently showing a QR but no one has scanned it yet,
                // clear that specific one.
                if let qr = (repo.inviteState as? Shared.InviteState.Pending)?.qr {
                    try await e2eeManager.clearInvite(ekPub: qr.ekPub)
                }
            }
            repo.pendingInvites = try await e2eeManager.listPendingInvites()
        } catch {
            logger.error("Failed to clear invite: \(error.localizedDescription)")
        }
        resetRapidPoll()
        repo.inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        repo.pendingInitAliceEkPub = nil
        repo.pendingInitPayload = nil
        locationProvider.sharingStateChanged()
    }

    @discardableResult
    func processQrUrl(_ url: String) -> Bool {
        guard let qr = Shared.QrPayload.Companion.shared.fromUrl(url: url) else {
            updateStatus(NSError(domain: "Where", code: 400, userInfo: [NSLocalizedDescriptionKey: MR.strings().invalid_qr_code.localized()]))
            return false
        }
        repo.inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        repo.pendingQrForNaming = qr
        triggerRapidPoll()
        return true
    }

    func confirmQrScan(qr: Shared.QrPayload, friendName: String) async {
        repo.pendingQrForNaming = nil
        repo.inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false

        let qrWithName = Shared.QrPayload(protocolVersion: Shared.ProtocolConstantsKt.PROTOCOL_VERSION,
            ekPub: qr.ekPub,
            suggestedName: friendName,
            discoverySecret: qr.discoverySecret
        )
        debugLog { "Scanning QR: discovery=\(qrWithName.discoveryToken().toHex()), friendName=\(friendName)" }
        inviteTask?.cancel()
        repo.isExchanging = true
        triggerRapidPoll()

        defer { repo.isExchanging = false }
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
            repo.friends = try await e2eeManager.listFriends()
            updateVisibleUsers()

            do {
                debugLog { "Posting KeyExchangeInit" }
                try await locationClient.postKeyExchangeInit(friendId: bobEntry.id, qr: qrWithName, initPayload: initPayload)
                debugLog { "KeyExchangeInit posted successfully" }

                if let last = locationProvider.lastLocation, isSharingLocation {
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
                repo.friends = try await e2eeManager.listFriends()
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
        guard let aliceEkPub = repo.pendingInitAliceEkPub else { return }
        repo.inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
        repo.isExchanging = true
        repo.pendingInitPayload = nil
        repo.pendingInitAliceEkPub = nil
        repo.multipleScansDetected = false

        defer { repo.isExchanging = false }
        do {
            let result = try await e2eeManager.processKeyExchangeInit(payload: payload, aliceSuggestedName: name, aliceEkPub: kotlinByteArray(from: aliceEkPub))

            if let entry = result ?? nil {
                awaitingFirstUpdateIds.insert(entry.id)
                repo.friends = try await e2eeManager.listFriends()
                updateVisibleUsers()

                if let last = locationProvider.lastLocation, isSharingLocation {
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
            repo.friends = try await e2eeManager.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("confirmPendingInit failed: \(error.localizedDescription)")
            updateStatus(error)
        }
    }

    func cancelPendingInit() async {
        let hasInviteState = !(repo.inviteState is Shared.InviteState.None)
        guard repo.pendingInitPayload != nil || hasInviteState || repo.pendingInitAliceEkPub != nil else { return }
        
        let ekPubToClear = repo.pendingInitAliceEkPub
        repo.pendingInitPayload = nil
        repo.multipleScansDetected = false
        repo.pendingInitAliceEkPub = nil
        
        await clearInvite(ekPub: ekPubToClear)
    }

    func renameFriend(id: String, newName: String) async {
        do {
            try await e2eeManager.renameFriend(id: id, newName: newName)
            repo.friends = try await e2eeManager.listFriends()
            updateVisibleUsers()
        } catch {
            logger.error("Failed to rename friend: \(error.localizedDescription)")
        }
    }

    func removeFriend(id: String) async {
        do {
            try await e2eeManager.deleteFriend(id: id)
            repo.pausedFriendIds.remove(id)
            repo.friends = try await e2eeManager.listFriends()
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
        sendLocation(lat: loc.lat, lng: loc.lng, source: .backgroundEntry, stationary: locationProvider.isStationary)
    }

    func sendLocation(lat: Double, lng: Double, heading: Double? = nil, force: Bool = false, source: WakeSource = .locationUpdate, stationary: Bool = false) {
        guard isSharingLocation else { return }
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
                try await locationClient.sendLocation(lat: lat, lng: lng, pausedFriendIds: effectivelyPausedIds(), stationary: stationary)
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
        for friend in repo.friends {
            if repo.pausedFriendIds.contains(friend.id) { continue }
            guard let lat = friend.lastLat?.doubleValue,
                  let lng = friend.lastLng?.doubleValue,
                  let ts = friend.lastTs?.int64Value else { continue }
            updates.append(Shared.UserLocation(userId: friend.id, lat: lat, lng: lng, timestamp: ts))
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
