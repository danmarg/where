import Foundation
@preconcurrency import Shared

// Swift sides of the shared LocationSendScheduler's interfaces. The scheduler runs on the main
// thread (createMainSendScheduler uses MainScope), so it only calls these there; each holds the
// service weakly, since the scheduler holds them for the service's lifetime.

/// What the scheduler reads at send time: may it send, to whom, and whether we're stationary.
@MainActor
final class SchedulerGate: NSObject, @preconcurrency SendGate {
    weak var service: LocationSyncService?

    init(service: LocationSyncService) {
        self.service = service
    }

    func canSend() -> Bool { service?.canSendLocations() ?? false }

    func pausedFriendIds() -> Set<String> { service?.effectivelyPausedIds() ?? [] }

    // Read when each send goes out, so "here since" is never stale.
    func stationary() -> Bool { service?.locationProvider.isStationary ?? false }
}

/// Reports each settled send to the diagnostic log and connection status.
@MainActor
final class SchedulerListener: NSObject, @preconcurrency SendListener {
    weak var service: LocationSyncService?

    init(service: LocationSyncService) {
        self.service = service
    }

    func onSendSettled(reason: SendReason, success: Bool, sinceLastSuccessMs: KotlinLong?, error: KotlinThrowable?) {
        service?.sendSettled(
            heartbeat: reason == SendReason.heartbeat,
            success: success,
            sinceLastSuccess: sinceLastSuccessMs.map { TimeInterval($0.int64Value) / 1000 },
            errorMessage: success ? nil : (error?.message ?? "send failed"),
        )
    }
}

/// Elapsed time that keeps counting while the device sleeps, so a heartbeat comes due after a
/// long sleep. CLOCK_MONOTONIC_RAW is mach_continuous_time (sleep-inclusive, never adjusted);
/// CLOCK_UPTIME_RAW / mach_absolute_time would stop while asleep.
final class SystemMonotonicClock: NSObject, MonotonicClock {
    func nowMs() -> Int64 { Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / 1_000_000) }
}

/// Sends through the service's LocationClientProtocol: used when that isn't the real Kotlin
/// LocationClient (tests' mocks). Production passes the client's own asBroadcastSender().
@MainActor
final class ProtocolBroadcastSender: NSObject, @preconcurrency BroadcastSender {
    private let client: LocationClientProtocol

    init(client: LocationClientProtocol) {
        self.client = client
    }

    func sharingStamp() -> Int64 { client.sharingStamp() }

    func sendLocation(lat: Double, lng: Double, pausedFriendIds: Set<String>, stationary: Bool, stamp: Int64) async throws {
        try await client.sendLocation(lat: lat, lng: lng, pausedFriendIds: pausedFriendIds, stationary: stationary, stamp: stamp)
    }
}
