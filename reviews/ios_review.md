# iOS Platform Review

**Reviewed:** `ios/Sources/Where/`, `ios/Tests/WhereTests/`

---

## MEDIUM: `updateLastLocation` Stores Reception Time Instead of Sender Timestamp

**File:** `LocationSyncService.swift:282`

```swift
try? await e2eeStore.updateLastLocation(
    id: update.userId,
    lat: update.lat,
    lng: update.lng,
    ts: Int64(Date().timeIntervalSince1970)  // BUG: should be update.timestamp
)
```

`UserLocation.timestamp` carries the sender's GPS fix timestamp from the decrypted payload. The call uses `Date().timeIntervalSince1970` (the receiver's current clock) instead. `FriendEntry.lastTs` therefore stores when the local device received the message, not when the friend's device recorded its position.

This produces misleading "last updated" display: a location update sent 5 minutes ago will appear to be "just now" if received at the current time.

**Fix:** Use `update.timestamp` directly:
```swift
ts: update.timestamp
```

Same bug is present in `LocationService.kt:247` on Android.

---

## MEDIUM: Pending QR Invite Cleared on Every App Launch

**File:** `LocationSyncService.swift:147-151`

```swift
Task {
    // Clear any stale invite from a previous session before first poll.
    if (try? await store.pendingQrPayload()) ?? nil != nil {
        try? await store.clearInvite()
    }
    startPolling()
}
```

Any pending QR invite is discarded every time `LocationSyncService.init` runs. If Alice:
1. Opens "Add Friend" and shows her QR
2. Moves the app to the background
3. The OS terminates and relaunches the app (common under memory pressure)

…her invite is cleared before she notices, and Bob's scan would fail silently or be directed to a now-cleared mailbox.

**Fix:** Only clear invites that are genuinely stale (e.g., older than a configurable timeout, say 5 minutes). Store a creation timestamp with `PendingInvite` and compare on load:
```swift
if let age = pendingInvite?.createdAt, Date().timeIntervalSince(age) > 300 {
    try? await store.clearInvite()
}
```

---

## LOW: Background Task Expiration Handlers Are Empty

**File:** `LocationSyncService.swift:268-270`, `LocationSyncService.swift:469-471`

```swift
let identifier = self.beginBackgroundTask("PollAll") {
    // Task expired
}
```

When iOS terminates a background task due to expiration (~30s limit), the expiration handler is called on the main thread with a short grace period to clean up. Empty handlers mean any in-flight `URLSession` requests (via Ktor's network client) are abandoned without cancellation, potentially leaving the ratchet state partially advanced — specifically, `encryptAndStore` may have run and persisted a new session state + outbox, but the subsequent `post` call may have timed out mid-flight.

The outbox recovery mechanism (`LocationClient.processOutboxes`) handles the recovery correctly on the next poll, so this is not a data-loss issue. However, cancelling the in-flight Task would be cleaner:

```swift
var pollTask: Task<Void, Error>? = nil
let identifier = self.beginBackgroundTask("PollAll") {
    self.pollTask?.cancel()
}
defer { 
    endBackgroundTask(identifier)
}
pollTask = Task { try await locationClient.poll(...) }
```

---

## LOW: GPS Accuracy Not Forwarded to Encrypted Payload

**File:** `LocationSyncService.swift:457` → `LocationClient.kt:148`

```swift
service.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude)
```

`CLLocation.horizontalAccuracy` is not forwarded. `LocationClient.sendLocation` hardcodes `acc = 0.0`. Recipients always see zero accuracy.

**Fix:** Add `acc` parameter to `sendLocation`:
```swift
service.sendLocation(lat: last.coordinate.latitude, lng: last.coordinate.longitude,
                     acc: last.horizontalAccuracy)
```

---

## LOW: Foreground vs Background Poll Intervals — Good

**File:** `LocationSyncService.swift:71-74`

```swift
private static let rapidPollInterval: TimeInterval = 1.0
private static let foregroundPollInterval: TimeInterval = 60.0
private static let normalPollInterval: TimeInterval = 300.0 // 5 min (background sharing)
private static let maintenancePollInterval: TimeInterval = 30 * 60  // ack-only
```

The iOS foreground interval (60s) matches the spec recommendation (§7.4.3). (Note: Android uses 10s — inconsistency flagged in `android_review.md`.)

---

## LOW: `pollAll` Always Updates `lastLocation` from `updateLastLocation` with Current Time

See the timestamp bug above. `friendLastPing` is set correctly from `Date()` (which is the receipt time — appropriate for "last ping" display):

```swift
friendLastPing[update.userId] = Date()
```

But `e2eeStore.updateLastLocation(... ts: Int64(Date().timeIntervalSince1970))` conflates the ping time with the sender timestamp. Fix the `ts:` argument as noted above.

---

## PASS: Keychain Storage Security

**File:** `KeychainE2eeStorage.swift`

Session state is stored using:
```swift
kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
```

This is correct per spec §5.6:
- Excludes the item from iCloud Backup ✓
- Makes the item accessible after first unlock (needed for background operation) ✓
- Tied to `ThisDeviceOnly` — not transferable to other devices ✓

---

## PASS: Rapid Poll Mechanism

The 1-second timer + `isPollInFlight` guard correctly prevents concurrent polls. `triggerRapidPoll()` sets `lastRapidPollTrigger` and fires the timer, allowing rapid polling during key exchange without busy-waiting. The 60-second rapid-poll window is reasonable.

---

## PASS: `@MainActor` Isolation

`LocationSyncService` is correctly annotated `@MainActor`. All `@Published` properties are accessed on the main actor. Injected closures (`beginBackgroundTask`, `endBackgroundTask`) use `MainActor.assumeIsolated` to safely call UIKit APIs. Swift 6 strict concurrency compliance is maintained.

---

## PASS: Name Sanitization

`E2eeStore.sanitizeName` is called by `processScannedQr` (via `bobSuggestedName` parameter) and `processKeyExchangeInit` (via `bobName`). The `normalizeName` (NFKC) call prevents homograph attacks. The 64-character limit and letter/digit/whitespace filter prevent injection of control characters into the UI.

---

## Summary

| Issue | Severity | File |
|-------|----------|------|
| `lastTs` stores reception time, not sender timestamp | Medium | `LocationSyncService.swift:282` |
| Pending QR invite cleared on every app launch | Medium | `LocationSyncService.swift:147-151` |
| Empty background task expiration handlers | Low | `LocationSyncService.swift:268, 469` |
| GPS accuracy not forwarded | Low | `LocationSyncService.swift:457` |
