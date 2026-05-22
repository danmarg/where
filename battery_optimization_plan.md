# Context

Homogenize iOS and Android background/foreground behavior for battery and UX, implementing the optimization plan from the Gemini brain doc. Three categories of change:

1. **Minor standardization**: rapid poll interval (iOS 1s → 2s) and movement distance filter (50m → 100m on both platforms).
2. **No-relationships idle state**: stop GPS and the Android foreground service entirely when there are 0 friends AND 0 pending invites. Friends can only be added through active in-app interaction (QR scan), so the service can safely self-terminate and restart on next foreground open.
3. Leave the `STATIONARY_GEOFENCE_RADIUS` alone — it's already 100m in the code, despite the Gemini plan listing it as a change.

---

## Files to Modify

- `android/src/androidMain/kotlin/net/af0/where/LocationService.kt`
- `android/src/androidMain/kotlin/net/af0/where/LocationViewModel.kt`
- `android/src/androidMain/kotlin/net/af0/where/LocationServiceRestartWorker.kt`
- `ios/Sources/Where/LocationSyncService.swift`
- `ios/Sources/Where/LocationManager.swift`

---

## Android Changes

### LocationService.kt

**1. Distance filter (line ~453):**
```kotlin
setMinUpdateDistanceMeters(50f)  →  setMinUpdateDistanceMeters(100f)
```

**2. `ensureLocationRegistration()` — add no-relationships guard at the top (after the permission/sharing check):**
```kotlin
if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
    // deregister GPS if registered — same cleanup block as the !hasPermission || !isSharing path
    if (isRegistered) { fusedClient.removeLocationUpdates(locationCallback); isRegistered = false }
    if (isPassiveRegistered) { fusedClient.removeLocationUpdates(passiveLocationCallback); isPassiveRegistered = false }
    return
}
```

**3. `pollLoop()` — stop service when no relationships remain (after `doPoll()` refreshes the lists in `locationSource`):**
```kotlin
// After the existing doPoll() call and friends/invites update block:
if (locationSource.friends.value.isEmpty() && locationSource.allPendingInvites.value.isEmpty()) {
    Log.i(TAG, "No friends or pending invites; stopping service.")
    stopSelf()
    return
}
```

**4. `onCreate()` — subscribe to friends/invites flows (mirrors the existing `isSharingLocation.collect` block):**
```kotlin
serviceScope.launch {
    locationSource.friends.collect { ensureLocationRegistration() }
}
serviceScope.launch {
    locationSource.allPendingInvites.collect { ensureLocationRegistration() }
}
```
`locationSource` is `LocationRepository`, which already exposes `friends: StateFlow<List<FriendEntry>>` and `allPendingInvites: StateFlow<List<PendingInviteView>>` — no new infrastructure needed.

### LocationViewModel.kt — `manageForegroundService()` (line ~492)

The existing condition is `if ((sharing && hasLocationPermission) || inForeground)`. Change to:

```kotlin
val hasRelationships = friends.value.isNotEmpty() || locationSource.allPendingInvites.value.isNotEmpty()
if ((sharing && hasLocationPermission && hasRelationships) || inForeground) {
    // existing startForegroundService + WorkManager enqueue
} else if (!hasRelationships) {
    // Cancel the WorkManager keepalive so it doesn't restart the service every 15 min
    try { WorkManager.getInstance(getApplication()).cancelUniqueWork(LocationServiceRestartWorker.WORK_NAME) }
    catch (_: IllegalStateException) {}
}
```

Note: keeping `|| inForeground` — the service still runs in the foreground so invite setup works smoothly before any friend exists.

`friends` is already `val friends: StateFlow<List<FriendEntry>> = locationSource.friends` (line ~89). `locationSource.allPendingInvites` is accessible the same way.

`manageForegroundService()` is already called from all the friend/invite mutation paths (lines 304–305, 361–362, etc.), so no new call sites needed.

### LocationServiceRestartWorker.kt

Add a friends/invites guard before restarting — this is a `CoroutineWorker` so suspend calls are fine:

```kotlin
override suspend fun doWork(): Result {
    val app = applicationContext as? WhereApplication ?: return Result.success()
    val friends = app.e2eeManager.listFriends()
    val invites = app.e2eeManager.listPendingInvites()
    if (friends.isEmpty() && invites.isEmpty()) {
        Log.i(TAG, "No friends or pending invites; skipping service restart")
        return Result.success()
    }
    Log.i(TAG, "WorkManager heartbeat: ensuring LocationService is running")
    applicationContext.startForegroundService(Intent(applicationContext, LocationService::class.java))
    return Result.success()
}
```

`WhereApplication` already exposes `e2eeManager` as a public property (accessed the same way as in `LocationService.onCreate()`).

---

## iOS Changes

### LocationSyncService.swift

**1. Rapid poll interval (line 110):**
```swift
private static let rapidPollInterval: TimeInterval = 1.0  →  2.0
```

**2. Distance filter (line 116):**
```swift
static let minimumReportingDistanceMeters: CLLocationDistance = 50  →  100
```
This changes both the hardware `distanceFilter` set in `LocationManager.init()` and the software distance check in `sendLocation()` — both use this constant.

**3. `targetPollInterval()` — add no-relationships case before the sharing check:**
```swift
func targetPollInterval() -> TimeInterval {
    if isRapidPolling() { return Self.rapidPollInterval }
    if isInForeground() { return Self.foregroundPollInterval }
    if friends.isEmpty && pendingInvites.isEmpty { return Self.maintenancePollInterval }
    return isSharingLocation ? Self.normalPollInterval : Self.maintenancePollInterval
}
```

**4. `removeFriend()` and `clearInvite()` — call `locationProvider.sharingStateChanged()` after state update:**

Both functions already update `friends` and `pendingInvites`. Add at the end of each:
```swift
locationProvider.sharingStateChanged()
```
This triggers `LocationManager.updateRegistration()` to re-evaluate whether GPS should be running.

### LocationManager.swift — `updateRegistration()` (line ~73)

Add a no-relationships guard:
```swift
func updateRegistration() {
    guard let manager = manager else { return }
    let status = manager.authorizationStatus
    let isSharing = LocationSyncService.shared.isSharingLocation
    let hasRelationships = !LocationSyncService.shared.friends.isEmpty || !LocationSyncService.shared.pendingInvites.isEmpty

    if (status == .authorizedWhenInUse || status == .authorizedAlways) && isSharing && hasRelationships {
        startUpdating()
    } else {
        stopUpdating()
    }
}
```

`LocationManager` already accesses `LocationSyncService.shared.isSharingLocation` (existing pattern at line ~78), so accessing `.friends` and `.pendingInvites` follows the same pattern with no new coupling.

---

## What Changes for BootReceiver

No change needed. If the device boots with 0 friends + 0 invites:
- `BootReceiver` starts the service (existing behavior, sharing must be enabled)
- `pollLoop()` runs one iteration, detects 0 relationships, calls `stopSelf()`
- One brief service start on boot is negligible

---

## Verification

1. **Existing tests:** `./gradlew :shared:jvmTest && ./gradlew :android:testDebugUnitTest`
2. **Fresh install, 0 friends:** Open app, go to background. Confirm in Android Studio profiler / iOS Instruments that no GPS is active and no network requests fire.
3. **Pairing flow:** Scan a QR code. Confirm rapid polling at 2s works (service restarts on Android, GPS resumes on iOS the moment invite is created / friend is confirmed).
4. **Friend removal:** Remove the last friend. Confirm GPS stops and service terminates (Android) or GPS stops (iOS) within one poll cycle (~5 min background, instant in foreground).
5. **Background heartbeat with friend present:** Confirm friend location updates still arrive every 5 min in background.
