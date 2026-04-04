iOS — Bugs Found
Bug 1: isSending guard races with force — forced send after pairing can silently
drop
In LocationSyncService.swift, the sendLocation function:

swift
func sendLocation(lat: Double, lng: Double, isHeartbeat: Bool = false, force:
Bool = false) {
    let effectiveForce = force || pendingForcedSendAfterPairing
    guard isSharingLocation else { return }
    
    if !effectiveForce && isSending { return }  // ← only guards if
!effectiveForce
    ...
    isSending = true
    if effectiveForce {
        pendingForcedSendAfterPairing = false    // ← clears the flag
    }
The logic looks like it lets forced sends bypass the isSending guard. But if a
regular heartbeat happens to be in-flight (isSending = true) when the forced
send arrives:

effectiveForce = true → the guard is bypassed ✓

isSending is set to true again (it already is), and
pendingForcedSendAfterPairing is cleared ✓

But the heartbeat Task already running will set isSending = false when it
completes, racing with the new forced send's Task

Two concurrent Task {} closures can both call locationClient.sendLocation()
simultaneously, and neither has a proper mutex — this is a potential double-send
or state corruption

The real problem is that isSending is a plain Bool on @MainActor, so it is safe
from data races, but the pattern still allows two overlapping network requests.
More critically: if pendingForcedSendAfterPairing = true (no location yet) and
the next didUpdateLocations call is delayed, the flag is checked each time the
poll loop calls sendLocation(isHeartbeat: true) — but those calls pass
isHeartbeat: true with no force:, so effectiveForce only becomes true when
pendingForcedSendAfterPairing is true. That part works. However:

Bug 2: The poll loop calls sendLocation(isHeartbeat: true) — but the 5-minute
heartbeat throttle means the forced send may be delayed
In startPolling() :

swift
if let last = LocationManager.shared.lastLocation {
    self.sendLocation(lat: last.coordinate.latitude, lng:
last.coordinate.longitude, isHeartbeat: true)
}
This is called every 2 seconds during rapid-poll mode (pairing). But
sendLocation with isHeartbeat: true will only actually send if now -
lastSentTime > 5 * 60. If a heartbeat was just sent, the forced send via
pendingForcedSendAfterPairing will be correctly triggered because effectiveForce
overrides the throttle check — this is actually fine. But there's a subtler
issue: pendingForcedSendAfterPairing is only checked inside sendLocation, and
sendLocation is called from the poll loop with isHeartbeat: true and no force:
argument. effectiveForce = force || pendingForcedSendAfterPairing — so
effectiveForce will be true as long as pendingForcedSendAfterPairing is true.
This does work correctly in theory.

Bug 3: confirmPendingInit (Alice's side) sends location BEFORE
processKeyExchangeInit completes the session
In confirmPendingInit :

swift
func confirmPendingInit(name: String) {
    ...
    let entry = try? e2eeStore.processKeyExchangeInit(payload: payload, bobName:
name)
    if entry != nil {
        friends = e2eeStore.listFriends()
    }
    Task {
        defer { isExchanging = false }
        if entry != nil {
            if let last = LocationManager.shared.lastLocation {
                self.sendLocation(lat: last.coordinate.latitude, lng:
last.coordinate.longitude, force: true)
            } else {
                self.pendingForcedSendAfterPairing = true
            }
        }
        await pollAll(updateUi: true)
    }
}
processKeyExchangeInit is called synchronously before the Task body. The send
then happens inside the Task. The issue: sendLocation on Alice's side calls
locationClient.sendLocation(...), which uses the E2EE session. The E2EE session
key is only established after processKeyExchangeInit succeeds — that's fine. But
there's no postOpkBundle call on Alice's side here. Bob calls postOpkBundle in
confirmQrScan, but Alice does not upload her OPK bundle after completing the
exchange. This may or may not be required by the protocol, but it is asymmetric
compared to Bob's side.

Bug 4: iOS background polling — UIApplication.applicationState is checked on a
Task without @MainActor context
In startPolling() :

swift
pollTask = Task { [weak self] in
    while !Task.isCancelled {
        let isForeground = UIApplication.shared.applicationState == .active
UIApplication.shared.applicationState must be accessed on the main thread. The
Task { } here runs on the cooperative thread pool, not on @MainActor, even
though LocationSyncService is @MainActor and startPolling() is called on the
main actor. The Task { [weak self] in closure captures by weak-self but inherits
the actor context of the calling scope only for the Task creation — the loop
body's UIApplication.shared.applicationState call can run on a non-main thread
in Swift Concurrency. This is a thread-safety violation that could cause a crash
or wrong value.

Bug 5: Background location updates never wake the iOS app for the poll loop
The iOS background strategy is: startUpdatingLocation() with
allowsBackgroundLocationUpdates = true. CLLocationManager does deliver location
updates to the app in background. Each didUpdateLocations call invokes
LocationSyncService.shared.sendLocation(...) — this is the sending path and it
does work for outbound location.

However, the receiving (poll) path is driven by the startPolling() Task loop.
When the app is backgrounded, iOS suspends the app's cooperative thread pool
after some time. The Task.sleep calls in the poll loop simply won't wake the app
in background — iOS does not resume a suspended app for a Task.sleep expiry. The
only thing keeping the app alive in background is the location update delivery
from CLLocationManager. Inbound friend location polls (pollAll) therefore only
happen when location updates are being delivered, not on any fixed schedule. The
5-minute heartbeat path in the poll loop runs pollAll, but only if the loop is
running — and the loop may be suspended by iOS.

The beginBackgroundTask calls on each pollAll and sendLocation give ~30 seconds
of extra background time per invocation, but that's for completing an
in-progress operation, not for periodic wakeup. There is no BGAppRefreshTask or
BGProcessingTask registration, no silent push notification path, and no
setMinimumFetchInterval equivalent — so background polls beyond
location-update-triggered sends are not guaranteed.

Android — Bugs Found
Bug 6: ACTION_FORCE_PUBLISH — race between pendingFriendIds queue and
handleNewLocation
In LocationService.kt , when ACTION_FORCE_PUBLISH is received with a friendId:

kotlin
LocationRepository.lastLocation.value?.let { (lat, lng) ->
    if (friendId != null) {
        serviceScope.launch {
            locationClient.sendLocationToFriend(friendId, lat, lng)
            lastSentLat = lat; lastSentLng = lng; lastSentTime = ...
        }
    }
} ?: run {
    if (friendId != null) { pendingFriendIds.add(friendId) }
}
If there IS a last location, sendLocationToFriend is called directly. If there
ISN'T, the friendId is queued in pendingFriendIds and will be drained in the
next handleNewLocation call. The pendingFriendIds queue is
ConcurrentLinkedQueue, so that's thread-safe. But lastSentLat, lastSentLng,
lastSentTime are plain var fields accessed from serviceScope (Dispatchers.IO) —
these are not @Volatile and not @Synchronized, so concurrent modifications from
multiple coroutines in serviceScope are a data race.

Bug 7: Android poll loop does NOT poll in background — only the heartbeat timer
does, and it reads from LocationRepository which may be stale
The pollLoop() in LocationViewModel :

kotlin
private suspend fun pollLoop() {
    while (isPolling) {
        val rapid = isRapidPolling()
        doPoll()
        val interval = if (rapid) 2_000L else 60_000L
        ...
    }
}
This runs in viewModelScope. When the app is backgrounded, the ViewModel remains
alive as long as it's attached to the Activity — but once the Activity is
destroyed or the process is low-memory killed, this loop stops. The foreground
service LocationService has its own 5-minute heartbeat that calls
locationClient.poll(), but that call's result is not reflected in
LocationViewModel — it is fire-and-forget, and doPoll() in the ViewModel is the
only path that updates friendLocations and triggers UI updates. So background
friend location updates will be received by the service but silently discarded —
the UI will only catch up when the user next foregrounds the app and doPoll()
runs.

Bug 8: confirmQrScan in Android calls startForegroundService for force-publish —
but the service may not be running yet
In LocationViewModel.kt :

kotlin
getApplication<Application>().startForegroundService(intent)
If the user just granted location permission and sharing is true,
manageForegroundService(true) will have started the service. But
startForegroundService here is the second call, with ACTION_FORCE_PUBLISH. In
LocationService.onStartCommand, if isRegistered is already true, the location
callback is not re-registered. This is fine. However: if the service is not yet
running (e.g., pairing completed quickly before the first startForegroundService
from manageForegroundService), the service starts, handles ACTION_FORCE_PUBLISH,
and at that moment LocationRepository.lastLocation.value may still be null (no
location fix yet). In that case the friendId is queued in pendingFriendIds, but
lastSentTime is also 0 — so the next handleNewLocation will drain the queue and
also immediately call locationClient.sendLocation(...) to all friends. This
results in two separate location sends: one via sendLocationToFriend(friendId,
...) and one via sendLocation(lat, lng, pausedIds) within a few milliseconds.
Minor but wasteful.


