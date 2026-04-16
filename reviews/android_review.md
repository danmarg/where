# Android Platform Review

**Reviewed:** `android/src/androidMain/kotlin/net/af0/where/`

---

## HIGH: BootReceiver Fires Before CE Storage Is Unlocked

**File:** `BootReceiver.kt:16-28`, `WhereApplication.kt:10-16`

`BootReceiver` listens for `ACTION_BOOT_COMPLETED` and calls `UserPrefs.isSharing(context)`, which chains through `WhereApplication.userStore` → `SharedPrefsE2eeStorage` → `EncryptedSharedPreferences`.

On API 24+ devices using File-Based Encryption (FBE), the credential-encrypted (CE) storage directory — where `EncryptedSharedPreferences` persists its file — is **not accessible until the user completes their first post-boot unlock**. `ACTION_BOOT_COMPLETED` fires before that unlock. Accessing CE storage at this point will throw an exception and the service will not start.

The workaround is to additionally (or exclusively) listen for `Intent.ACTION_USER_UNLOCKED`:

```kotlin
// In AndroidManifest.xml:
<action android:name="android.intent.action.USER_UNLOCKED" />

// In BootReceiver.onReceive:
if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_USER_UNLOCKED || ...) {
```

Alternatively, store the `isSharing` boolean in DE (device-encrypted) storage (`Context.createDeviceProtectedStorageContext()`) so it is readable at boot, while keeping session state in CE storage.

**Impact:** Location sharing silently does not restart after a device reboot when FBE is active (all modern Android devices). Users must manually open the app to resume.

---

## MEDIUM: Foreground Poll Interval Is 10s (Spec Recommends 60s)

**File:** `LocationService.kt:219`

```kotlin
inForeground -> 10_000L
```

iOS uses 60s for foreground polling (`LocationSyncService.swift:72`). The spec (§7.4.3) recommends **60 seconds** as the default. The 10s interval is 6× more aggressive, creating both battery drain and excessive server load.

**Fix:** Change to `60_000L` to match iOS and the spec recommendation.

---

## MEDIUM: GPS Accuracy Not Forwarded to Encrypted Payload

**Files:** `LocationService.kt:76-77`, `LocationClient.kt:148`

When `FusedLocationProviderClient` delivers a location fix, only `latitude` and `longitude` are extracted:

```kotlin
override fun onLocationResult(result: LocationResult) {
    val loc = result.lastLocation ?: return
    locationSource.onLocation(loc.latitude, loc.longitude)  // accuracy dropped
}
```

`LocationClient.sendLocation` then hardcodes `acc = 0.0`:

```kotlin
val payload = MessagePlaintext.Location(lat = lat, lng = lng, acc = 0.0, ts = ts)
```

The `acc` field flows through the encryption path and is presented to the recipient, who sees accuracy = 0.0 regardless of the actual GPS fix quality. This affects the UI's ability to show meaningful uncertainty circles on the map.

**Fix:** Thread `accuracy` through `LocationRepository.onLocation(lat, lng, accuracy)`, `sendLocationIfNeeded`, and `LocationClient.sendLocation(lat, lng, acc)`.

---

## MEDIUM: `processBatch` Silently Drops Decryption Failures

**File:** `E2eeStore.kt:546-549`

```kotlin
} catch (e: Exception) {
    // Skip individually bad messages to prevent head-of-line blocking
}
```

Decryption failures (authentication failures, replay attempts, protocol errors) are swallowed without logging. This makes it impossible to distinguish between:
- A corrupted server message (infrastructure issue)
- A replay attack (security event)
- A protocol version mismatch (engineering issue)
- A session desync that requires re-pairing

**Fix:** At minimum, `Log.w(TAG, "decryptMessage failed for $friendId: ${e.message}")`. Replay/authentication failures could also be surfaced to the connection status.

---

## LOW: `lastTs` Stores Reception Time, Not Sender Timestamp

**File:** `LocationService.kt:247`

```kotlin
e2eeStore.updateLastLocation(update.userId, update.lat, update.lng, now / 1000L)
```

The `UserLocation.timestamp` field contains the sender's timestamp from the encrypted payload. `updateLastLocation` is called with `now / 1000L` (the receiver's current time), overwriting `FriendEntry.lastTs` with the reception time instead.

This means the "last seen at" time shown in the UI reflects when the local device received the message, not when the friend's GPS fix was taken — potentially showing "just now" for a location that was sent 10 minutes ago.

**Fix:** Use `update.timestamp` instead of `now / 1000L`.

---

## LOW: `outgoing` List in `processBatch` Is Always Empty (Dead Code)

**File:** `E2eeStore.kt:488, 568`

```kotlin
val outgoing = mutableListOf<OutgoingMessage>()
// ... outgoing is never appended to ...
PollBatchResult(decryptedLocations, outgoing)
```

`LocationClient.pollFriend` iterates `result.outgoing` (`LocationClient.kt:104-106`) but this loop is always a no-op. The `outgoing` mechanism was designed for `processBatch` to auto-generate keepalives, but that responsibility moved to `LocationClient.pollFriend` directly.

**Fix:** Remove `OutgoingMessage`, `PollBatchResult.outgoing`, and the dead iteration in `pollFriend`. Simplify `processBatch` to return only `List<LocationPlaintext>`.

---

## LOW: `FriendEntry.lastSentTs` Updated for Keepalives via `encryptAndStore`

**File:** `E2eeStore.kt:365`

```kotlin
friends[friendId] = entry.copy(
    session = newSession,
    outbox = EncryptedOutboxMessage(...),
    lastSentTs = currentTimeSeconds(),  // set for ALL message types
)
```

`encryptAndStore` sets `lastSentTs` for both Location and Keepalive messages. `LocationClient.sendMessageToFriendInternal` then calls `store.updateLastSentTs(friendId, ...)` only for Location messages, but by this point `lastSentTs` is already set (incorrectly) from the keepalive. The UI would show a keepalive as the last "send" time.

**Fix:** Don't set `lastSentTs` in `encryptAndStore`; keep it only in the `updateLastSentTs` call for Location messages.

---

## LOW: `LocationPlaintext` Is Redundant with `MessagePlaintext.Location`

**Files:** `Types.kt:179-198`, `E2eeStore.kt:537-545`

`LocationPlaintext` and `MessagePlaintext.Location` represent the same data. `processBatch` converts from the latter to the former, silently dropping `precision` and `pn` fields. The `precision` field is part of the wire format and could be relevant to the UI (COARSE vs FINE precision mode). Having two types for the same concept creates maintenance burden.

**Fix:** Remove `LocationPlaintext`; use `MessagePlaintext.Location` throughout the pipeline. Platform code can access `.lat`, `.lng`, `.acc`, `.ts`, and `.precision` directly.

---

## PASS: Backup Security

- `android:allowBackup="false"` is set in `AndroidManifest.xml:18`. ✓
- `EncryptedSharedPreferences` with StrongBox-backed master key (API 28+) is implemented in `SharedPrefsE2eeStorage.kt`. ✓
- Keystore key is created with `setIsStrongBoxBacked(true)` with fallback to standard TEE-backed Keystore. ✓

---

## PASS: Service Lifecycle

The foreground `LocationService` uses `SupervisorJob()` for its coroutine scope, preventing one child failure from cancelling polling or sending. The `pendingFriendSends` channel correctly defers friend-specific location sends until a GPS fix is available.
