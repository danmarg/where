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
    func sharingStamp() -> Int64
    func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool, stamp: Int64) async throws
    func sendLocationToFriend(friendId: String, lat: Double, lng: Double, stationary: Bool, stamp: Int64) async throws
    func sendStoppedSharing(pausedFriendIds: Set<String>) async throws
    func sendStoppedSharingToFriend(friendId: String) async throws
    func removeFriend(friendId: String, pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws
    func processOrphanedOutboxes() async throws
    func poll(isForeground: Bool, pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws -> [Shared.UserLocation]
    func pollPendingInvites() async throws -> [Shared.PendingInviteResult]
    func postKeyExchangeInit(friendId: String, qr: Shared.QrPayload, initPayload: Shared.KeyExchangeInitPayload) async throws
    func syncNow(pausedFriendIds: Set<String>, sharingEnabled: Bool) async throws
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
    /// Why the database couldn't be opened, if it couldn't. Nothing is loaded, polled or sent then.
    let databaseOpenError: String?
    /// Friends whose sessions were discarded because the database came from another device or
    /// install; shown once so the user knows to re-pair. Persisted until dismissed, since the
    /// discard may happen during a background relaunch.
    @Published private(set) var discardedSessionNames: [String] = []
    static let discardedSessionNamesKey = "discarded_session_names"
    /// Why an invite the user just opened or confirmed was refused. Shown in an alert until
    /// dismissed: the connection status would be cleared by the next successful poll, seconds
    /// later, usually before the user has seen it.
    @Published var inviteError: String? = nil
    /// Mirror of `userStore.isSharingLocation` for SwiftUI binding. Read freely, but to
    /// STOP sharing callers must use `stopSharing()` rather than assignment — that path
    /// also enqueues the StoppedSharing fan-out. didSet handles only state mirroring.
    @Published var isSharingLocation: Bool {
        didSet {
            userStore.setSharing(sharing: isSharingLocation)
            locationProvider.sharingStateChanged()
            if !isSharingLocation {
                sendScheduler.reset()
            } else if !oldValue {
                // Master toggle off→on: friends shouldn't wait for the next regular tick.
                broadcastCurrentLocationNow()
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
                // Serialize per-keystroke renames: each awaits the previous one, so they reach the
                // store and repo.inviteState in order and the last edit wins. (Cancelling the
                // previous task would not stop its in-flight Kotlin call from landing later.)
                let previous = inviteRenameTask
                inviteRenameTask = Task {
                    await previous?.value
                    await createInvite()
                }
            }
        }
    }

    @Published var ownHeading: Double? = nil
    private var inviteRenameTask: Task<Void, Never>? = nil
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
    // Target cadence while sharing and backgrounded. NOTE: this is only actually enforced by
    // a running timer while foregrounded (see startPolling()/tick()) — iOS suspends the run
    // loop once backgrounded, so tick() simply stops firing. In the background, this value is
    // consulted only where it's read directly (the heartbeat check in pollAll(), driven by
    // whatever wakes the process — a BGAppRefreshTask or a CoreLocation callback). Real
    // background cadence is therefore bounded by those wake sources' own scheduling, which
    // the OS can and does throttle well past 5 minutes; this constant is a target, not a
    // guarantee.
    private static let normalPollInterval: TimeInterval = 300.0 // 5 min (background sharing)
    private static let maintenancePollInterval: TimeInterval = 30 * 60  // ack-only when not sharing
    private static let staleLocationThreshold: TimeInterval = 60.0
    /// LocationManager's jitter radius around a stationary geofence center. (How far a fix must
    /// move to be sent is the send scheduler's policy, shared with Android.)
    static let minimumReportingDistanceMeters: CLLocationDistance = 200
    private static let rapidPollDuration: TimeInterval = 60.0
    /// How long a newly-added friend who hasn't sent their first location update keeps the
    /// client in rapid (2s) polling. Bounds the impact of a friend who never shares (see #336) -
    /// long enough for a normal first exchange, short enough not to run rapid mode forever.
    private static let awaitingFirstUpdateTimeout: TimeInterval = 5 * 60
    /// Even while stationary, force a real GPS fix at this interval instead of only re-reporting
    /// the cached location. CoreMotion's stationary detection and the underlying CLLocationUpdate
    /// stream/geofence (LocationManager) can each independently get stuck (mirrors the Android
    /// isStill/Activity-Recognition backstop gap) with no other path back to a fresh fix - this
    /// doesn't depend on either, so a friend's location can't freeze forever. Long enough to
    /// preserve stationary battery savings; short enough that a stuck pipeline surfaces within
    /// hours, not days.
    static let stationaryForceFixInterval: TimeInterval = 2 * 60 * 60
    /// Last time we forced a real GPS fix from the pollAll heartbeat (moving heartbeat or
    /// stationary backstop). Starts at service init rather than the epoch so a fresh instance
    /// gets one normal stationary cadence before the backstop can engage. internal for testing.
    var lastForcedFixTime: Date = Date()
    /// How long the heartbeat waits for a fresh fix before sending the cached one.
    var locationFixTimeout: TimeInterval = 10.0  // internal for testing
    /// locationFixTimeout for services created from now on (tests shorten it: mocks never deliver
    /// a requested fix).
    static var defaultLocationFixTimeout: TimeInterval = 10.0
    /// Fixes with horizontalAccuracy above this threshold are cell/WiFi network fixes too noisy
    /// to broadcast: the send scheduler's accuracy cap, shared with Android.
    nonisolated static let minBroadcastAccuracyMeters: CLLocationAccuracy = LocationSendSchedulerKt.defaultSendPolicy.maxAccuracyMeters
    private var visibleUsersCancellables = Set<AnyCancellable>()
    let pathMonitor = NWPathMonitor()  // internal for testing
    private let monitorQueue = DispatchQueue(label: "NWPathMonitorQueue")

    /// The last accurate fix reported to the send scheduler (readable for testing).
    private(set) var lastReportedLocation: (lat: Double, lng: Double)? = nil

    /// Best available location for heartbeat sends: accurate GPS fix first, then last reported.
    /// Low-accuracy network fixes (e.g. from stationary cell-tower positioning) are skipped
    /// so a 3km drift doesn't overwrite a precise known location in friends' maps.
    private var bestAvailableLocation: (lat: Double, lng: Double, heading: Double?)? {
        if let loc = locationProvider.lastLocation, loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= Self.minBroadcastAccuracyMeters {
            return (lat: loc.coordinate.latitude, lng: loc.coordinate.longitude, heading: (locationProvider as? LocationManager)?.heading)
        }
        if let last = lastReportedLocation {
            return (lat: last.lat, lng: last.lng, heading: ownHeading)
        }
        return nil
    }
    var pendingForcedSendFriendId: String? = nil
    var skipNetworkRestore: Bool = false  // internal for testing

    /// The only sender of broadcast Locations (shared with Android): this service reports fixes
    /// and wakes to it. Set at the end of init; see SendSchedulerBridge.swift.
    private(set) var sendScheduler: LocationSendScheduler!
    /// The drain holding a background task while the scheduler sends (at most one at a time).
    private(set) var drainTask: Task<Void, Never>? = nil  // readable for testing
    /// How long one wake's sending may hold background time: under the ~30 s an app gets.
    static let drainMaxWaitMs: Int64 = 25_000
    /// With no usable fix at all, the heartbeat asks for one at most this often.
    private static let fixRequestWithoutFallbackInterval: TimeInterval = 60
    private var lastFixRequestWithoutFallback: Date = .distantPast
    /// What reported the fix a non-heartbeat send carries, for the diagnostic log.
    private var lastReportSource: WakeSource = .locationUpdate
    // Queries whether the device is stationary. Default queries CoreMotion;
    // tests replace with { true } or { false } for deterministic results.
    // Initialized in init() to capture motionActivityManager/motionQueue without
    // a self reference. Note the closure is still main-actor-isolated (it's formed in the
    // @MainActor init), so the CoreMotion callback inside it is marked @Sendable: CoreMotion
    // calls it on motionQueue, and an inherited-isolation check there would trap.
    var isStationaryQuery: () async -> Bool = { false }  // overridden in init
    /// Friend IDs awaiting their first location update, keyed to when they were added so
    /// isRapidPolling() can stop treating a stale entry as a reason to keep rapid mode on.
    /// internal (not private) for testing, mirroring lastRapidPollTrigger below.
    var awaitingFirstUpdateIds: [String: Date] = [:]
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

    /// Begins a background task whose expiration handler actually ends it (an empty handler
    /// gets the app killed once the budget runs out, e.g. a long multi-friend poll on a BGTask
    /// wake). `onExpire` decides what expiry means for the protected work; see
    /// BackgroundTaskToken. Call `end()` on the normal completion path (idempotent).
    private func beginExpiringBackgroundTask(_ name: String, onExpire: (@MainActor () -> Void)? = nil) -> BackgroundTaskToken {
        BackgroundTaskToken(name: name, begin: beginBackgroundTask, end: endBackgroundTask, onExpire: onExpire)
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

    /// Opens the on-disk database, discarding its sessions first if it was restored or
    /// transferred from elsewhere (spec §5.5), and checks it is excluded from backup. Throws if
    /// the database can't be opened, e.g. a migration fails or it was written by a newer build.
    static func openDatabase(name: String = "where.db", deviceStore: Shared.DeviceMarkerStore = DeviceMarkerKeychain()) throws -> Shared.E2eeManager {
        let driver = IosSqlDriverKt.createIosSqlDriver(name: name)
        let binding = try DeviceBindingKt.reconcileDeviceBinding(sqlDriver: driver, deviceStore: deviceStore)
        let store = try Shared.E2eeManager(sqlDriver: driver)

        if !binding.checked {
            logger.warning("Device binding check skipped: device marker unavailable")
            store.addDiagnosticEvent(message: "Device binding check skipped: keychain unavailable", coalesceKey: "Device binding check skipped")
        }
        let discarded = binding.discardedFriendNames
        if !discarded.isEmpty {
            logger.error("Discarded \(discarded.count) session(s) from a database restored or transferred from another device")
            store.addDiagnosticEvent(message: "Discarded \(discarded.count) session(s): database came from another device", coalesceKey: nil)
            let defaults = UserDefaults.standard
            let pending = defaults.stringArray(forKey: discardedSessionNamesKey) ?? []
            defaults.set(pending + discarded, forKey: discardedSessionNamesKey)
        }

        let dir = IosSqlDriverKt.iosDatabaseDirectory(name: name)
        if !isExcludedFromBackup(path: dir) {
            logger.error("Database directory is not excluded from backup: \(dir, privacy: .public)")
            store.addDiagnosticEvent(message: "Database directory is not excluded from backup", coalesceKey: "Database directory is not excluded")
        }
        return store
    }

    /// Reads the attribute through a fresh URL: a URL that set a resource value caches it.
    nonisolated static func isExcludedFromBackup(path: String) -> Bool {
        let values = try? URL(fileURLWithPath: path).resourceValues(forKeys: [.isExcludedFromBackupKey])
        return values?.isExcludedFromBackup == true
    }

    func dismissInviteError() {
        inviteError = nil
    }

    func dismissDiscardedSessionsNotice() {
        UserDefaults.standard.removeObject(forKey: Self.discardedSessionNamesKey)
        discardedSessionNames = []
    }

    init(e2eeManager: Shared.E2eeManager? = nil, userStore: Shared.UserStore? = nil, locationClient: LocationClientProtocol? = nil, locationProvider: LocationProviding? = nil, sendClock: MonotonicClock? = nil) {
        logger.debug("LocationSyncService init: serverUrl=\(ServerConfig.httpBaseUrl)")

        let keychain = KeychainRawKeyValueStorage()
        let store: Shared.E2eeManager
        var openError: String? = nil
        if let e2eeManager {
            store = e2eeManager
        } else {
            do {
                store = try Self.openDatabase()
            } catch {
                logger.fault("Could not open the database: \(String(describing: error), privacy: .public)")
                // Before first unlock (a background relaunch after reboot) the files are still
                // encrypted. That passes, but this process would keep the failure, so end it and
                // let the system relaunch the app later.
                if !UIApplication.shared.isProtectedDataAvailable { exit(0) }
                // Otherwise run on an empty placeholder, never loaded or polled, behind a screen
                // that says what went wrong; the database on disk is left as it is.
                openError = error.localizedDescription
                store = try! Shared.E2eeManager(sqlDriver: IosSqlDriverKt.createIosInMemorySqlDriver())
            }
        }
        self.databaseOpenError = openError
        self.e2eeManager = store
        let userStoreValue = userStore ?? Shared.UserStore(storage: keychain)
        self.userStore = userStoreValue
        self.locationClient = locationClient ?? Shared.LocationClient(baseUrl: ServerConfig.httpBaseUrl, store: store)
        self.locationProvider = locationProvider ?? LocationManager.shared
        self.locationFixTimeout = Self.defaultLocationFixTimeout
        let repoValue = FriendSyncRepository(e2eeManager: store, userStore: userStoreValue)
        self.repo = repoValue

        self.discardedSessionNames = UserDefaults.standard.stringArray(forKey: Self.discardedSessionNamesKey) ?? []
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
                motionManager.queryActivityStarting(from: now.addingTimeInterval(-60), to: now, to: motionQueue) { @Sendable activities, error in
                    if error != nil {
                        continuation.resume(returning: false)
                        return
                    }
                    let stationary = activities?.last(where: { $0.confidence != .low })?.stationary ?? false
                    continuation.resume(returning: stationary)
                }
            }
        }

        // The real Kotlin client sends directly; test mocks go through the Swift protocol.
        let sender: BroadcastSender =
            (self.locationClient as? Shared.LocationClient)?.asBroadcastSender()
            ?? ProtocolBroadcastSender(client: self.locationClient)
        self.sendScheduler = LocationSendSchedulerKt.createMainSendScheduler(
            sender: sender,
            gate: SchedulerGate(service: self),
            clock: sendClock ?? SystemMonotonicClock(),
            listener: SchedulerListener(service: self),
        )

        // Forward repo's @Published changes to our own observers so the existing
        // `service.foo` forwarders continue to drive SwiftUI updates.
        self.repoSink = repoValue.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }

        Task { @MainActor in
            guard self.databaseOpenError == nil else { return }
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

    /// Broadcasts our current location to every (non-paused) friend now (forced, so without a fix
    /// yet it goes out with the first one), and requests a fresh fix, sent like any other.
    private func broadcastCurrentLocationNow() {
        if let loc = bestAvailableLocation {
            reportLocation(lat: loc.lat, lng: loc.lng, accuracy: nil, heading: loc.heading, force: true, source: .manual)
        } else {
            sendScheduler.requestForced()
        }
        locationProvider.requestImmediateLocation()
    }

    /// Sharing is on and we have location permission: a Location may be sent. Mirrors Android's
    /// `LocationService.canSendLocations`. Take a send's stamp before reading this.
    func canSendLocations() -> Bool {
        isSharingLocation && locationProvider.hasLocationPermission
    }

    // In UserDefaults, not the keychain-backed UserStore: a keychain write can fail, and one from
    // Kotlin's UserStore setters isn't @Throws, so it would terminate the app.
    static let stoppedForMissingPermissionKey = "stopped_sharing_for_missing_permission"
    private var permissionStopInFlight = false

    /// Keeps friends' view right across permission changes; mirrors Android's
    /// `LocationViewModel.reconcileSharingWithPermission`. With sharing on and permission lost
    /// (Settings, Location Services off), no Location can go out, so friends are told once that
    /// we stopped rather than keep our last pin. Sharing itself stays on, as the user's choice:
    /// when permission is back, our location goes out again. The "told them" flag is persisted,
    /// so it fires once per loss; a stop that fails to be queued is retried by LocationClient.
    /// Returns whether it broadcast our location (permission is back).
    @discardableResult
    func locationPermissionChanged() -> Bool {
        guard databaseOpenError == nil else { return false }
        let defaults = UserDefaults.standard
        let notified = defaults.bool(forKey: Self.stoppedForMissingPermissionKey)
        let hasPermission = locationProvider.hasLocationPermission
        if isSharingLocation && !hasPermission && !notified {
            guard !permissionStopInFlight else { return false }
            permissionStopInFlight = true
            let paused = effectivelyPausedIds()
            Task {
                do {
                    try await locationClient.sendStoppedSharing(pausedFriendIds: paused)
                    UserDefaults.standard.set(true, forKey: Self.stoppedForMissingPermissionKey)
                } catch {
                    logger.warning("sendStoppedSharing (permission lost) failed, will retry: \(error.localizedDescription)")
                }
                permissionStopInFlight = false
                // Permission may have come back while the stop went out: resume now, not at the
                // next foreground entry.
                if locationProvider.hasLocationPermission { locationPermissionChanged() }
            }
        } else if hasPermission && notified {
            defaults.set(false, forKey: Self.stoppedForMissingPermissionKey)
            guard isSharingLocation else { return false }
            broadcastCurrentLocationNow()
            return true
        }
        return false
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
        // Flip the state before the stop goes out: a Location send that read it earlier took its
        // stamp earlier, so LocationClient drops it rather than sending it after the stop.
        if isSharingLocation {
            isSharingLocation = false
        }
        Task {
            do {
                try await locationClient.sendStoppedSharing(pausedFriendIds: paused)
            } catch {
                logger.warning("sendStoppedSharing failed: \(error.localizedDescription)")
            }
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
        // Before reading the sharing state below: a stop issued after it drops the resume send.
        let stamp = locationClient.sharingStamp()
        if !wasPaused {
            Task {
                do {
                    try await locationClient.sendStoppedSharingToFriend(friendId: id)
                } catch {
                    logger.warning("sendStoppedSharingToFriend(\(id)) failed: \(error.localizedDescription)")
                }
            }
        } else if let last = locationProvider.lastLocation, canSendLocations() {
            Task {
                do {
                    try await locationClient.sendLocationToFriend(friendId: id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: locationProvider.isStationary, stamp: stamp)
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
            // A name edit while an invite is showing renames it in place (same keys), so a scan of
            // the QR as shown a moment ago still pairs and edits don't mint invites.
            let qr: Shared.QrPayload
            if let shown = (repo.inviteState as? Shared.InviteState.Pending)?.qr,
               let renamed = try await e2eeManager.updateInviteName(ekPub: shown.ekPub, suggestedName: displayName) {
                qr = renamed
            } else {
                qr = try await e2eeManager.createInvite(suggestedName: displayName)
            }
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
            try await locationClient.syncNow(pausedFriendIds: effectivelyPausedIds(), sharingEnabled: canSendLocations())
        } catch {
            logger.error("syncNow failed on path update: \(error.localizedDescription)")
        }

        // A send backing off after failures may retry now.
        sendScheduler.onNetworkRestored()
        guard canSendLocations() else { return }

        let locationIsStale = (locationProvider.lastLocation?.timestamp.timeIntervalSinceNow ?? -.infinity) < -Self.staleLocationThreshold
        if locationIsStale && !locationProvider.isStationary {
            // Moving with a stale fix: get a fresh one; it's sent like any other once it arrives.
            // (Stationary, the position hasn't changed: nothing new to send.)
            locationProvider.requestImmediateLocation()
        }
        drainInBackground()
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
        guard databaseOpenError == nil else { return }
        // Reset lastPollTime to .distantPast so tick() bypasses its interval guard and fires
        // a poll immediately. A stuck isPollInFlight is handled by pollAll()'s watchdog task.
        lastPollTime = .distantPast
        // Proactively report our location so friends see us soon (the scheduler sends it if it
        // moved or the heartbeat is due), and request a fresh high-accuracy fix, which arrives
        // via didUpdateLocations and is reported the same way. Permission just back already did
        // both, forced.
        if !locationPermissionChanged() {
            if canSendLocations(), let loc = bestAvailableLocation {
                reportLocation(lat: loc.lat, lng: loc.lng, accuracy: nil, heading: loc.heading, source: .manual)
            }
            locationProvider.requestImmediateLocation()
        }
        // Fire a poll directly rather than through the timer to minimize foreground latency.
        // Tracked so tests can await the poll completion without sleeping.
        foregroundPollTask = Task { @MainActor in
            await pollAll(updateUi: true, source: .manual)
        }
    }

    var foregroundPollTask: Task<Void, Never>? = nil  // internal for testing

    /// Drives poll cadence only while the run loop is active — i.e. only while foregrounded.
    /// iOS suspends this timer on backgrounding rather than merely slowing it, so tick() simply
    /// stops firing; it resumes on the next onForegroundEntry() → startPolling() call. Background
    /// cadence instead depends entirely on BGAppRefreshTask and CoreLocation wake sources (see
    /// normalPollInterval's comment) — this timer contributes nothing there.
    func startPolling() {
        pollTimer?.invalidate()
        // 1Hz, matching rapidPollInterval's timescale but deliberately not equal to it: ticking
        // at exactly the same interval as the shortest target interval this drives against
        // aliases against tick()'s elapsed-time check (the two timestamps are offset by an
        // async hop's worth of delay), causing the check to miss roughly half the time and
        // degrading rapid-poll (QR pairing) cadence from ~2s to ~3-4s in practice. A 1Hz tick
        // avoids that by staying well under every target interval.
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
        if awaitingFirstUpdateIds.values.contains(where: { Date().timeIntervalSince($0) < Self.awaitingFirstUpdateTimeout }) {
            return true
        }
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

    /// Closes the invite sheet without deleting the invite.
    ///
    /// Showing a QR in person never marks it exported, and the scan can arrive after the sheet is
    /// gone (swiped away, or closed to scan the other person's QR). Deleting the invite here would
    /// discard its private key and silently drop that scan, leaving the scanner "Pending" forever.
    /// Unused invites are cleaned up by `cleanupExpiredInvites`.
    func dismissInvite() {
        resetRapidPoll()
        repo.inviteState = Shared.InviteState.None()
        isInviteSheetShowing = false
    }

    private func onFriendLocationReceived(friendId: String) {
        if awaitingFirstUpdateIds.removeValue(forKey: friendId) != nil {
            if awaitingFirstUpdateIds.isEmpty {
                resetRapidPoll()
            }
        }
    }

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
        if isPollInFlight || databaseOpenError != nil { return }
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
                try await e2eeManager.cleanupExpiredInvites()
                lastCleanupTime = Date()
            } catch {
                logger.error("Failed to cleanup expired invites: \(error.localizedDescription)")
            }
        }

        // On expiry the remaining network steps (invite polling, heartbeat send) are skipped
        // rather than started without budget. Already-received updates are persisted by
        // poll() itself, so stopping between steps loses nothing; the next poll resumes.
        let backgroundTask = self.beginExpiringBackgroundTask("PollAll")
        let wakeStart = Date()

        defer {
            watchdog.cancel()
            isPollInFlight = false
            backgroundTask.end()
        }
        do {
            let updates = try await locationClient.poll(
                isForeground: isInForeground(),
                pausedFriendIds: effectivelyPausedIds(),
                sharingEnabled: canSendLocations(),
            )
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

            if backgroundTask.didExpire || Task.isCancelled {
                logger.warning("pollAll: background time expired; skipping invite poll and heartbeat")
                updateStatus(nil)  // the poll itself succeeded
                return
            }
            if updateUi || isInviteSheetShowing {
                _ = try await pollPendingInvites()
            }
            if backgroundTask.didExpire || Task.isCancelled {
                logger.warning("pollAll: background time expired; skipping heartbeat")
                updateStatus(nil)  // the poll itself succeeded
                return
            }

            // Heartbeat: if we're awake enough to poll, also send location if one is due.
            // This covers wakeups that don't go through tick() (e.g. didUpdateLocations,
            // background-app-refresh, or any direct pollAll() call).
            let heartbeatDue = sendScheduler.isHeartbeatDue()
            logger.info("pollAll: sharing=\(self.isSharingLocation) heartbeatDue=\(heartbeatDue)")
            if canSendLocations() && heartbeatDue {
                let stationary = await isStationaryQuery()
                // Converge CoreMotion result into the provider's cached flag so
                // background-entry and network-restore sends read a consistent value.
                locationProvider.isStationary = stationary
                let now = Date()
                // Backstop: if we haven't forced a real GPS fix in a long while - whether
                // because we've genuinely been stationary the whole time, or because
                // CoreMotion's stationary detection or the underlying CLLocationUpdate
                // stream/geofence got silently stuck - force one anyway. See
                // stationaryForceFixInterval.
                let backstopDue = now.timeIntervalSince(lastForcedFixTime) > Self.stationaryForceFixInterval
                var fix: (lat: Double, lng: Double)? = bestAvailableLocation.map { (lat: $0.lat, lng: $0.lng) }
                if stationary && !backstopDue {
                    logger.info("pollAll: heartbeat due — stationary, re-reporting cached location")
                } else {
                    if backstopDue {
                        logger.info("pollAll: heartbeat due — stationary backstop: forcing fresh GPS fix after \(Int(Self.stationaryForceFixInterval / 3600))h")
                    } else {
                        logger.info("pollAll: heartbeat due — moving, requesting fresh fix")
                    }
                    if fix != nil {
                        lastForcedFixTime = now
                        // Wait for the fresh fix rather than sending the cached one: a fresh fix
                        // landing just after would be held back by the send interval, and on a
                        // background wake we'd be suspended before it went out.
                        if let fresh = try await awaitFreshFix(since: now) { fix = fresh }
                    } else if now.timeIntervalSince(lastFixRequestWithoutFallback) >= Self.fixRequestWithoutFallbackInterval {
                        // No usable fix at all (e.g. approximate location): ask for one, but don't
                        // hold up this poll waiting - the heartbeat stays due until one arrives.
                        lastFixRequestWithoutFallback = now
                        locationProvider.requestImmediateLocation()
                    }
                }
                if backgroundTask.didExpire || Task.isCancelled {
                    logger.warning("pollAll: background time expired; skipping heartbeat send")
                } else if let fix {
                    sendScheduler.onFix(lat: fix.lat, lng: fix.lng, accuracyMeters: nil)
                    // Within what's left of this wake's budget, not a fresh one.
                    let remainingMs = Self.drainMaxWaitMs - Int64(Date().timeIntervalSince(wakeStart) * 1000)
                    _ = try? await sendScheduler.wakeAndDrain(maxWaitMs: KotlinLong(value: max(remainingMs, 1_000)))
                }
            }

            updateStatus(nil)
        } catch is CancellationError {
            // The wake's task was cancelled (BGTask expiry): stop here. Not a network failure,
            // so the status is left alone; the next poll resumes.
            logger.warning("pollAll: cancelled")
        } catch {
            logger.error("Poll failed: \(error.localizedDescription)")
            updateStatus(error)
            // The success path above is the only other place this refreshes - without this,
            // a poll failure (the case you most want visibility into) leaves the Events log
            // frozen on whatever it last showed, even though the underlying store just
            // recorded a new diagnostic event for this exact failure.
            repo.diagnosticLog = e2eeManager.diagnosticLogSnapshot()
        }
    }

    @discardableResult
    private func pollPendingInvites() async throws -> [Shared.PendingInviteResult] {
        let results = try await locationClient.pollPendingInvites()
        if results.isEmpty { return [] }

        let pendingInvitesSnapshot = try await e2eeManager.listPendingInvites()
        let filteredResults = results.filter { result in
            pendingInvitesSnapshot.contains { invite in
                invite.qrPayload.ekPub == result.inviteEkPub
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
                repo.pendingInitAliceEkPub = result.inviteEkPub // THE FIX: Use our own EK from the invite
                repo.multipleScansDetected = result.multipleScansDetected
                repo.inviteState = Shared.InviteState.None()
                isInviteSheetShowing = false
                triggerRapidPoll()
            }
        }
        return filteredResults
    }

    func clearInvite(ekPub: Shared.InvitePublicKey? = nil) async {
        do {
            let ekPubToClear = ekPub ?? repo.pendingInitAliceEkPub
            if let ekPubToClear = ekPubToClear {
                try await e2eeManager.clearInvite(ekPub: ekPubToClear)
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
            inviteError = MR.strings().invalid_qr_code.localized()
            return false
        }
        if qr.isExpired() {
            inviteError = MR.strings().invite_expired.localized()
            return false
        }
        // Accepting an invite pairs with fresh keys, so a re-opened or redelivered link for an
        // invite we already accepted must not pair a second time. processScannedQr enforces
        // this again under the store lock (e.g. if the friend list hadn't loaded yet).
        if repo.friends.contains(where: { $0.isFromInvite(qr: qr) }) {
            logger.info("processQrUrl: invite already accepted")
            inviteError = MR.strings().invite_already_used.localized()
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

        // The naming dialog may have stayed open past the invite's expiry.
        if qr.isExpired() {
            inviteError = MR.strings().invite_expired.localized()
            return
        }

        // Keep the scanned protocol_version so bobProcessQr can reject versions we don't support.
        let qrWithName = Shared.QrPayload(protocolVersion: qr.protocolVersion,
            ekPub: qr.ekPub,
            suggestedName: friendName,
            discoverySecret: qr.discoverySecret,
            expiresAt: qr.expiresAt
        )
        debugLog { "Scanning QR: discovery=\(qrWithName.discoveryToken().toHex()), friendName=\(friendName)" }
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
            awaitingFirstUpdateIds[bobEntry.id] = Date()
            repo.friends = try await e2eeManager.listFriends()
            updateVisibleUsers()

            do {
                debugLog { "Posting KeyExchangeInit" }
                try await locationClient.postKeyExchangeInit(friendId: bobEntry.id, qr: qrWithName, initPayload: initPayload)
                debugLog { "KeyExchangeInit posted successfully" }

                // Before reading the sharing state: a stop issued after it drops the send.
                let stamp = locationClient.sharingStamp()
                if let last = locationProvider.lastLocation, canSendLocations() {
                    // Proactively send our first location update to Alice to trigger confirmation on her side.
                    // We use sendLocationToFriend here to bypass the !isConfirmed check in sendLocation().
                    do {
                        try await locationClient.sendLocationToFriend(friendId: bobEntry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: false, stamp: stamp)
                    } catch {
                        logger.error("Failed to send proactive location update to \(bobEntry.id): \(error.localizedDescription)")
                    }
                } else {
                    logger.debug("confirmQrScan: lastLocation is nil, deferring the pairing send to the first fix")
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
            switch (error as NSError).userInfo["KotlinException"] {
            case is Shared.InviteAlreadyAcceptedException:
                inviteError = MR.strings().invite_already_used.localized()
            case is Shared.InviteExpiredException:
                inviteError = MR.strings().invite_expired.localized()
            default:
                updateStatus(error)
            }
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
            let result = try await e2eeManager.processKeyExchangeInit(payload: payload, aliceSuggestedName: name, aliceEkPub: aliceEkPub)

            if let entry = result ?? nil {
                awaitingFirstUpdateIds[entry.id] = Date()
                repo.friends = try await e2eeManager.listFriends()
                updateVisibleUsers()

                // Before reading the sharing state: a stop issued after it drops the send.
                let stamp = locationClient.sharingStamp()
                if let last = locationProvider.lastLocation, canSendLocations() {
                    // Proactively send our first location update to Bob to trigger confirmation on his side.
                    do {
                        try await locationClient.sendLocationToFriend(friendId: entry.id, lat: last.coordinate.latitude, lng: last.coordinate.longitude, stationary: false, stamp: stamp)
                    } catch {
                        logger.error("Failed to send reactive location update to \(entry.id): \(error.localizedDescription)")
                    }
                } else {
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
            // Queues a StoppedSharing (if we were sharing with them) and deletes them locally;
            // it's delivered below, after the UI has updated, or by a later poll.
            try await locationClient.removeFriend(friendId: id, pausedFriendIds: effectivelyPausedIds(), sharingEnabled: canSendLocations())
            repo.pausedFriendIds.remove(id)
            repo.friends = try await e2eeManager.listFriends()
            updateVisibleUsers()
            locationProvider.sharingStateChanged()
        } catch {
            logger.error("Failed to remove friend: \(error.localizedDescription)")
            return
        }
        do {
            try await locationClient.processOrphanedOutboxes()
        } catch {
            logger.warning("processOrphanedOutboxes after removing a friend failed: \(error.localizedDescription)")
        }
    }

    func sendLocationOnBackground() {
        guard canSendLocations() else { return }
        guard let loc = bestAvailableLocation else {
            logger.info("sendLocationOnBackground: skipped — no location available")
            return
        }
        logger.info("sendLocationOnBackground: sending location before app suspends")
        reportLocation(lat: loc.lat, lng: loc.lng, accuracy: nil, heading: loc.heading, source: .backgroundEntry)
    }

    /// Reports a fix to the send scheduler, the only sender of broadcast Locations: it decides
    /// when to send (the interval, distance and heartbeat policy shared with Android) and reads
    /// "here since" stationarity at send time, so callers don't pass it. `force` skips the send
    /// interval (entering stationary, sharing turned on), not the queue behind a send in flight.
    /// `accuracy` nil means the fix was already vetted (bestAvailableLocation).
    func reportLocation(lat: Double, lng: Double, accuracy: Double?, heading: Double? = nil, force: Bool = false, source: WakeSource = .locationUpdate) {
        guard canSendLocations() else { return }
        ownHeading = heading
        lastReportSource = source
        if accuracy.map({ $0 >= 0 && $0 <= Self.minBroadcastAccuracyMeters }) ?? true {
            lastReportedLocation = (lat: lat, lng: lng)
        }

        // If a friend-specific send is pending (set when location was unavailable at pairing
        // time), send it now: per-friend sends aren't broadcasts and don't go through the
        // scheduler, so the newly-paired peer gets our location whatever its interval says.
        if let friendId = pendingForcedSendFriendId {
            pendingForcedSendFriendId = nil
            // The friend may have been paused while this send waited for a fix.
            if effectivelyPausedIds().contains(friendId) {
                logger.info("Skipping forced pairing send: \(friendId) is paused")
            } else {
                // Before reading the sharing state: a stop issued after it drops the send.
                let stamp = locationClient.sharingStamp()
                Task {
                    do {
                        try await locationClient.sendLocationToFriend(friendId: friendId, lat: lat, lng: lng, stationary: false, stamp: stamp)
                    } catch {
                        logger.error("Failed forced pairing send to \(friendId): \(error.localizedDescription)")
                    }
                }
            }
        }

        sendScheduler.onFix(lat: lat, lng: lng, accuracyMeters: accuracy.map { KotlinDouble(value: $0) })
        if force { sendScheduler.requestForced() }
        drainInBackground()
    }

    /// Holds a background task while the scheduler sends whatever this event made due, so a
    /// background wake isn't suspended mid-send. One drain at a time: it covers events that
    /// arrive while it waits.
    private func drainInBackground() {
        guard drainTask == nil else { return }
        let backgroundTask = beginExpiringBackgroundTask("SendLocation")
        drainTask = Task { @MainActor [weak self] in
            defer {
                backgroundTask.end()
                self?.drainTask = nil
            }
            guard let self else { return }
            _ = try? await self.sendScheduler.wakeAndDrain(maxWaitMs: KotlinLong(value: Self.drainMaxWaitMs))
        }
    }

    /// Requests a fresh fix and waits up to locationFixTimeout for an accurate one newer than
    /// `start`; nil if none arrives in time. Throws CancellationError if the task is cancelled
    /// (a BGTask expiring): swallowing it would spin this loop on the main actor until the
    /// deadline, since a cancelled Task.sleep returns at once.
    private func awaitFreshFix(since start: Date) async throws -> (lat: Double, lng: Double)? {
        locationProvider.requestImmediateLocation()
        let deadline = Date().addingTimeInterval(locationFixTimeout)
        while true {
            if let loc = locationProvider.lastLocation, loc.timestamp > start,
               loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= Self.minBroadcastAccuracyMeters {
                return (lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
            }
            if Date() >= deadline { return nil }
            try await Task.sleep(for: .milliseconds(250))
        }
    }

    /// The scheduler's report on a settled send (see SchedulerListener).
    func sendSettled(heartbeat: Bool, success: Bool, sinceLastSuccess: TimeInterval?, errorMessage: String?) {
        logReliability(source: heartbeat ? .heartbeat : lastReportSource, success: success, interval: sinceLastSuccess)
        if let errorMessage {
            updateStatus(NSError(domain: "net.af0.where.send", code: 1, userInfo: [NSLocalizedDescriptionKey: errorMessage]))
        } else {
            updateStatus(nil)
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
