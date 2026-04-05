Deep Analysis: Location Update & State Machine Bugs
iOS — Bug A: Forced Update After Pairing Is a No-Op If lastLocation Is Nil
In confirmQrScan (Bob-side) and confirmPendingInit (Alice-side), the forced
location send is:

swift
// LocationSyncService.swift confirmQrScan:
if let last = LocationManager.shared.lastLocation {
    self.sendLocation(lat: ..., lng: ..., force: true)
}

// confirmPendingInit:
if let entry, let last = LocationManager.shared.lastLocation {
    self.sendLocation(lat: ..., lng: ..., force: true)
}
The problem: LocationManager.shared.lastLocation is nil until CoreLocation
delivers its first fix. On iOS, startUpdatingLocation() is called from
requestPermissionAndStart(), but lastLocation on a freshly-started
CLLocationManager is nil for a non-trivial window — potentially seconds to
minutes. If pairing completes before the first fix arrives (common on cold-start
or simulator), the if let last = ... guard silently skips the forced send
entirely. There is no fallback. The regular heartbeat won't fire until the poll
loop's next 60s sleep, so the peer sees nothing for up to a minute.

Fix: Store the send intent as a flag (pendingForcedSendAfterPairing = true) and
flush it in locationManager(_:didUpdateLocations:) when the first fix arrives,
or in startPolling's next tick. Alternatively, call sendLocation unconditionally
from the Task if there is a cached location from any prior session (check if
lastSentLocation is non-nil as a proxy, or store the last GPS fix in
UserDefaults across launches).

iOS — Bug B: sendLocation Silently Skips If isSending == true During the Forced
Post
swift
func sendLocation(..., force: Bool = false) {
    guard isSharingLocation, !isSending else { return }
    ...
    isSending = true
    Task {
        ...
        isSending = false
    }
}
isSending is a simple Bool set to true for the duration of the HTTP call. If
startPolling's heartbeat fires at the same time as the forced-send from
confirmQrScan, the forced call sees isSending == true and returns early — even
when force: true. The force flag bypasses the time/distance debounce logic
(shouldSend) but it does not bypass the !isSending guard at the top.

Fix: Either move the !isSending guard inside the non-force branch, or queue the
forced send to retry once the current send completes.

iOS — Bug C: Background Polling Is Structurally Broken
The poll loop in startPolling uses Task.sleep with a 300_000_000_000 ns
(5-minute) interval in background, but the UIApplication.shared.applicationState
== .active check happens at the top of the loop — after the sleep :

swift
while !Task.isCancelled {
    let isForeground = UIApplication.shared.applicationState == .active
    let rapid = await self?.isRapidPolling() == true
    await self?.pollAll(updateUi: isForeground || rapid)
    if let last = LocationManager.shared.lastLocation {
        self?.sendLocation(...)  // ← heartbeat send
    }
    // sleep 300s in background
}
The deeper issue: iOS will suspend the app's Task after ~30 seconds in
background. Task.sleep does not keep the app alive. The poll loop simply
freezes. The beginBackgroundTask wrappers inside pollAll and sendLocation
provide a ~30-second execution window at the point of each call, but the sleep
between calls gets no such window. In practice, after the first background poll
+ send completes and the background time expires, the loop hangs until the user
  brings the app to foreground.

The allowsBackgroundLocationUpdates = true and
pausesLocationUpdatesAutomatically = false in LocationManager ensures GPS
updates keep arriving, but nothing calls sendLocation from the
didUpdateLocations delegate callback — only the poll loop does, and the poll
loop is frozen.

Fix: Call sendLocation directly from locationManager(_:didUpdateLocations:) in
LocationManager, passing the new coordinate. That callback is woken by the OS
alongside background location delivery. The poll loop is still useful for the
pollAll (receiving friend locations) but that part actually needs a proper
background fetch / BGAppRefreshTask or silent push to work reliably.


