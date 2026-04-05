ndroid — Bug A: ACTION_FORCE_PUBLISH Races With Service Startup
In confirmQrScan and confirmPendingInit, the ViewModel sends the intent
immediately:

kotlin
val intent = Intent(getApplication(), LocationService::class.java).apply {
    action = LocationService.ACTION_FORCE_PUBLISH
    putExtra(LocationService.EXTRA_FRIEND_ID, entry.id)
}
getApplication<Application>().startForegroundService(intent)
But LocationService.onStartCommand also unconditionally calls
fusedClient.requestLocationUpdates(...) on every startForegroundService call,
including the force-publish ones . That's a mild resource leak (re-registering
the callback), but not the main issue.

The main issue: ACTION_FORCE_PUBLISH checks
LocationRepository.lastLocation.value:

kotlin
if (intent?.action == ACTION_FORCE_PUBLISH) {
    LocationRepository.lastLocation.value?.let { (lat, lng) ->
        ...
    }
}
If LocationRepository.lastLocation is null (service just started, no GPS fix yet
— same cold-start scenario as iOS), the force-publish block is silently skipped
by let. The fusedClient.lastLocation fetch in onCreate is asynchronous and may
not have resolved yet when onStartCommand fires. There is no retry or deferred
flush.

Fix: Same solution as iOS — store the pending friend ID and flush it once
onLocationResult or the lastLocation success listener delivers a fix.

Android — Bug B: Location Throttle Incorrectly Blocks Non-Heartbeat Updates
kotlin
val shouldSend = force || lastSentLat == null || 
                (!isHeartbeat && now - lastSentTime > 60_000L) ||
                (isHeartbeat && now - lastSentTime > 300_000L)
For a non-heartbeat (real GPS movement), the condition !isHeartbeat && now -
lastSentTime > 60_000L means the very first movement update after a recent send
(within 60s) is dropped. Consider the sequence: (1) user pairs → forced send
updates lastSentTime, (2) user moves 100m 30 seconds later → !isHeartbeat is
true but now - lastSentTime < 60_000 → update dropped. The OS distance filter of
10m is trusted for significance, but then a 60s cooldown is applied on top — so
a user walking won't get their location updated for a full minute after any
recent send.

Fix: For non-heartbeat movement updates, the cooldown should be much shorter (or
zero, fully trusting the OS 10m/15s filter). The 60s cooldown makes sense for
heartbeats, not for genuine movement.

Android — Bug C: Background Updates Depend on Foreground Service Remaining Alive
The LocationService is a foreground service with START_STICKY, which is good.
However, on Android 12+ with aggressive battery optimization (Doze, app
standby), FusedLocationProviderClient with PRIORITY_BALANCED_POWER_ACCURACY and
a 30s interval will be heavily batched and may not deliver updates in background
. The 5-minute heartbeat loop in serviceScope is the correct backup, but it
depends on the coroutine scope staying alive.

More critically: onStartCommand re-registers locationCallback on every call
(including force-publish intents), and onCreate also registers
fusedClient.lastLocation — but there is no deduplication. Each call to
startForegroundService from the ViewModel triggers a new
fusedClient.requestLocationUpdates(request, locationCallback, mainLooper).
Android's FusedLocationProvider will deliver duplicate callbacks if you register
the same callback object multiple times without calling removeLocationUpdates
first.

Fix: Add a isRegistered flag to guard against double-registrations in
onStartCommand, or move requestLocationUpdates into onCreate only.
