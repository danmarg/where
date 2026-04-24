# Location Sync Desync — Bug Investigation Notes

**Symptom:** iOS shows Android's last location as 6 hours old; Android shows iOS's location as "now".

This means iOS's *send* path is working (Android decrypts iOS messages successfully), but iOS's *receive* path from Android is broken. Either Android stopped sending, or iOS is polling a stale/wrong mailbox token and discarding/failing to decrypt Android's messages.

---

## Theories, Ordered by Likelihood

### 1. Android location service stopped sending (despite being foregrounded)

The most boring, most likely explanation. If Android's `LocationService` was still running but GPS was stalled, paused, or rate-limited, no location would be sent. The 30-second throttle and 5-minute heartbeat both require a GPS fix available via `bestAvailableLocation`. If `locationProvider.lastLocation == null` (GPS lost, denied, or stalled), the heartbeat logs "no location available" and sends nothing.

**Evidence to collect:** Android Logcat for "heartbeat due but no location available" / "skipped — no location available" in the 6-hour window.

---

### 2. iOS process was killed by the OS and never got a background wakeup

If the iOS process was terminated (memory pressure, explicit kill, crash) and no background location event or background-app-refresh woke it up, the poll timer would be dead. iOS would send nothing and receive nothing until the user opened the app. If Android's GPS was also not moving (no significant-location-change wakeup), iOS could stay dark for hours.

**Evidence to collect:** iOS device log for app launch events, crash reports. Check whether iOS's "last heartbeat sent" timestamp corresponds to the 6h gap.

---

### 3. Persistent token desync after a crash or kill mid-ratchet

**Mechanism:** The E2EE session state is saved atomically to Keychain/EncryptedSharedPreferences. If the app is killed between a `processBatch` save (which advances `recvToken`) and the subsequent keepalive POST (which tells the other side to advance its `sendToken`), the two sides will disagree on where to write/read:

- iOS's stored `recvToken` = T_new (iOS followed the ratchet)
- Android's `sendToken` = T_old_or_different (Android never got the keepalive to finalize its transition)

iOS polls T_new, gets nothing. Android posts to T_old. Neither sees the other.

**Why recovery normally works:** `needsRatchet = true` is persisted, and `poll()` sends a keepalive before polling, which re-derives the correct token. BUT: if the `needsRatchet` flag was not set (e.g., the ratchet completed on receive but `needsRatchet` was already cleared before the crash), this recovery path doesn't fire.

**Evidence to collect:** Inspect both sides' stored session state (new debug view will show this). If `recvToken[iOS]` ≠ `sendToken[Android]`, this is confirmed.

---

### 4. `finalizeTokenTransition` keepalive POST failure — silent, unrecovered

**Mechanism:** In `LocationClient.finalizeTokenTransition()`:
```kotlin
if (updatedFriend.session.isSendTokenPending) {
    val finalSession = updatedFriend.session.copy(isSendTokenPending = false)
    store.updateSession(friendId, finalSession)  // Saved with pending = false
    
    if (!finalSession.sendToken.contentEquals(finalSession.prevSendToken)) {
        try {
            sendKeepalive(friendId)  // POST to new token — can throw
        } catch (e: Exception) {
            // SWALLOWED — no outbox, no retry
        }
    }
}
```

After `isSendTokenPending = false` is saved, the keepalive to the new `sendToken` is attempted. If it fails (network timeout, 5xx), the exception is swallowed. There is no outbox retry for this keepalive. The consequence:

- Android's `sendToken` is now T_new, but no message has been posted there yet
- iOS's `recvToken` for Android is also T_new (iOS followed the chain earlier)
- On Android's NEXT location send: `isSendTokenPending = false`, so it posts to T_new directly
- iOS polls T_new, gets the location → **self-heals within one send cycle (30s–5min)**

**Assessment:** This is a real gap but should self-heal quickly. It alone shouldn't cause a 6-hour gap — unless combined with Android also not sending (theory #1).

**Missing test:** There is no integration test for this exact path. The existing `mailbox POST failure during Bob exchange` test only covers key exchange failures, not mid-session transition failures.

---

### 5. `MAX_TOKEN_FOLLOWS_PER_POLL = 2` causing catchup lag

If multiple DH ratchets occurred while iOS was backgrounded (e.g., 5 epochs), iOS can only follow 2 token hops per poll. It would take 3 polls (ceil(5/2)) to catch up. In foreground (10s polls), this resolves in ~30s. In background maintenance mode (30min polls), this resolves in ~1.5h.

**Assessment:** Could explain a delay, not 6 hours. Only relevant if iOS was backgrounded AND there was an unusual burst of ratcheting.

---

### 6. `sharingEnabled = false` race causing keepalive omission when both sides are sharing

In `pollFriend()`, the post-poll keepalive is:
```kotlin
if (!friendAfter.sharingEnabled && !friendAfter.isStale) {
    sendKeepalive(friendId)
}
```

If BOTH sides are sharing location (`sharingEnabled = true`), no keepalive is sent after receiving a new DH key. The intent is that the next `sendLocation` (within 30s) will carry the new `localDhPub` and trigger the other side's ratchet. This creates a ~30s window where iOS is polling the new token but Android doesn't yet know to post to it.

**Assessment:** Transient, self-heals within 30s. Not the cause of a 6h gap but is a correctness concern worth noting.

---

### 7. Android clock skew making iOS see a stale timestamp (not actually a sync failure)

`friendLastPing` on iOS is set from `update.timestamp` — the GPS timestamp embedded in Android's location message by Android's clock. If Android's system clock is behind iOS's clock, the displayed "X ago" could look stale even though the message was received moments ago.

**Assessment:** This could explain a misleading display without an actual sync failure. Easy to verify: check `lastRecvTs` (when iOS actually decrypted the message) against `lastTs` (Android's GPS timestamp).

---

## What the New Diagnostics Will Show

After the planned changes:
- **Structured logs** will show: which token iOS is polling, how many messages it receives per cycle, whether the token changes, seq numbers used on send.
- **Debug long-press UI** will show: `lastRecvTs` ("last heard from"), `lastSentTs`, `recvToken` prefix, `needsRatchet`, `isSendTokenPending`.

The combination of "Android's sendToken[0..7]" and "iOS's recvToken[0..7]" should immediately confirm or deny theory #3 when the issue next occurs.

---

## Next Steps

1. Add structured logging to `LocationClient.kt` and `E2eeStore.kt` (shared)
2. Add debug long-press UI to iOS settings (showing `lastRecvTs`, token prefix, flags)
3. Add integration test for `finalizeTokenTransition` keepalive failure recovery (theory #4)
4. On next occurrence: capture both device logs and compare token prefixes via debug UI
