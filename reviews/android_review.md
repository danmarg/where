<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are a senior Android engineer reviewing a Kotlin Multiplatform location-sharing app. Fetch and read the full contents of the android/ directory at:

[https://github.com/danmarg/where/tree/main/android](https://github.com/danmarg/where/tree/main/android)

Also read:

[https://github.com/danmarg/where/blob/main/android_bugs.md](https://github.com/danmarg/where/blob/main/android_bugs.md)

[https://github.com/danmarg/where/blob/main/CLAUDE.md](https://github.com/danmarg/where/blob/main/CLAUDE.md) (for build/architecture context)
Review the following areas, citing specific files and line numbers:

Keystore integration. The protocol spec (§5.5) requires root keys to be stored in Android Keystore with setIsStrongBoxBacked(true) and allowBackup=false. Verify whether the Android implementation uses EncryptedSharedPreferences backed by a Keystore key, whether allowBackup=false is set in the manifest, and whether any session state is stored outside the Keystore.

Background location. Evaluate how background location updates are implemented (foreground service, WorkManager, JobScheduler). Is the polling loop reliable when the app is backgrounded? Are there battery vs. freshness tradeoffs made explicitly?

Session lifecycle. How is the E2EE session state (root key, chain key, epoch) persisted across app restarts? Is session state loaded atomically on startup? Is there a race condition between the UI and background polling coroutines accessing session state?

Concurrency correctness. Identify any shared mutable state accessed from multiple coroutines without synchronization. Are Kotlin coroutine cancellation and structured concurrency used correctly?

Error handling and UX. How are cryptographic failures (MAC verification failure, unknown epoch, OPK depletion) surfaced to the user? Are errors logged in a way that could leak sensitive information?

Known bugs. Read android_bugs.md and assess which bugs are security-relevant vs. purely functional. For each listed bug, assess its current status and severity.

Build configuration. Check build.gradle.kts and the manifest for: minSdk, targetSdk, ProGuard/R8 rules (are crypto classes excluded from obfuscation?), and whether debug builds differ from release in security-relevant ways.
Produce a prioritized finding list with severity labels.

Now I have enough information for a comprehensive review. Let me compile the findings.

***

# Android Security \& Architecture Review — `danmarg/where`

This review covers seven areas across the Android Kotlin Multiplatform codebase. Findings are ordered by severity.

***

## Prioritized Finding List

### 🔴 CRITICAL — C1: User ID Stored in Plaintext SharedPreferences

**File:** [`UserPrefs.kt`, lines 7–21](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/UserPrefs.kt)

`UserPrefs` calls `context.getSharedPreferences("where_prefs", Context.MODE_PRIVATE)` — this is **plain, unencrypted** SharedPreferences. While the `user_id` is a UUID and not itself cryptographic key material, it is the stable identity used to address mailbox tokens and correlate location events across sessions. Since the `userId` is used as the `FriendEntry.id` fingerprint, compromise of this file lets an attacker link all encrypted location messages to a specific device. More critically, `display_name`, `is_sharing`, and `paused_friends` are also stored in plaintext — paused friend IDs are a partial graph of who the user is tracking. On a rooted device or via adb backup (even with `allowBackup=false`), this file is trivially readable. All `UserPrefs` values should move into `EncryptedSharedPreferences` backed by the same MasterKey already built in `SharedPrefsE2eeStorage`.

***

### 🔴 CRITICAL — C2: No ProGuard/R8 Rules; Crypto Classes Exposed in Release

**File:** [`android/build.gradle.kts`](https://github.com/danmarg/where/blob/main/android/build.gradle.kts)

The `buildTypes` block contains only:

```kotlin
release {
    signingConfig = signingConfigs.getByName("release")
}
```

There is **no `isMinifyEnabled = true`, no `proguardFiles` directive, and no R8 rule file** anywhere in the Android module . This means:

- The release APK ships with full unobfuscated class names, making it trivial to reverse-engineer E2EE protocol code (Double Ratchet, key exchange flows).
- Crypto classes in the `e2ee` package (from the shared KMP module) get no rename protection.
- No dead-code stripping: the attack surface is maximized.

At minimum, `isMinifyEnabled = true` + `isShrinkResources = true` should be enabled for release, with keep rules for `kotlinx.serialization`, the `e2ee.*` sealed classes, and libsodium JNI bindings.

***

### 🔴 CRITICAL — C3: Default Server URL is HTTP in Production Build Config

**File:** [`android/build.gradle.kts`, line 41](https://github.com/danmarg/where/blob/main/android/build.gradle.kts)

```kotlin
buildConfigField("String", "SERVER_HTTP_URL",
    "\"${localProperties.getProperty("SERVER_HTTP_URL") ?: "http://10.0.2.2:8080"}\""
)
```

The fallback is `http://10.0.2.2:8080` — a cleartext HTTP URL. If a developer forgets to set `SERVER_HTTP_URL` in `local.properties` and builds release, **all location messages, key-exchange payloads, and OPK bundles travel over cleartext HTTP**. This completely voids the E2EE transport security. There is no `buildType`-specific override that forces HTTPS in release, and Android's Network Security Config is not configured to block cleartext traffic. The fix is to: (a) separate `debug` and `release` `buildConfigField` values, with the release default being a real HTTPS URL, and (b) add a `res/xml/network_security_config.xml` that sets `cleartextTrafficPermitted="false"`.

***

### 🟠 HIGH — H1: StrongBox Fallback Silently Degrades to Unprotected Keystore

**File:** [`SharedPrefsE2eeStorage.kt`, lines 32–57](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/SharedPrefsE2eeStorage.kt)

The `buildMasterKey` function attempts `setIsStrongBoxBacked(true)` and catches *all* exceptions with a blank catch (`catch (_: Exception)`) silently falling back to `MasterKey.KeyScheme.AES256_GCM` without StrongBox . The protocol spec (§5.5) requires the root key to live in StrongBox. There is:

- **No telemetry or user-visible indication** that StrongBox is unavailable and the key is in the weaker TEE.
- **No `setUserAuthenticationRequired(true)`** on either code path — keys are accessible to any process running as the app UID without biometric or PIN confirmation.
- The silent catch conceals StrongBox failures (e.g. due to `StrongBoxUnavailableException` or quota exhaustion on some OEMs) entirely.

***

### 🟠 HIGH — H2: `awaitingFirstUpdateIds` — Unsynchronized Mutable State in Singleton

**File:** [`LocationRepository.kt`, lines 88–89 and 134–143](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationRepository.kt)

```kotlin
private val awaitingFirstUpdateIds = mutableSetOf<String>()
```

This `mutableSetOf` is a plain `HashSet`. It is read and written from:

- `LocationService.pollLoop()` → `doPoll()` → `onFriendLocationReceived()` — running on `Dispatchers.Main` via `withContext(Dispatchers.Main)`
- `LocationRepository.markAwaitingFirstUpdate()` — called from `LocationViewModel.confirmQrScan()` running on `viewModelScope` which is also `Dispatchers.Main`
- `LocationRepository.reset()` — called from tests but the same pattern applies in production

While `Dispatchers.Main` is single-threaded, `resetRapidPoll()` also calls `awaitingFirstUpdateIds.clear()` and is invoked from `LocationViewModel.cancelQrScan()` / `cancelPendingInit()` which run on the main thread — so in practice there is no concurrent thread access here *today*. However, `triggerRapidPoll()` calls `LocationService.clock()` (a `var` on the companion object) without a memory barrier, and the `pendingFriendSends` set in `LocationService`  is a `mutableSetOf<String>()` accessed from the `serviceScope` coroutine *and* from `onStartCommand` (which runs on the main thread) without a mutex. This is a genuine data race.

***

### 🟠 HIGH — H3: Cryptographic Failures Are Swallowed Into Generic Error State

**File:** [`LocationService.kt`, lines 120–125](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationService.kt) and [`LocationRepository.kt`, `onConnectionError`, lines 98–108](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationRepository.kt)

`doPoll()` and `sendLocationIfNeeded()` both catch the entire `Exception` hierarchy and route everything through `updateStatus(e)`. The `onConnectionError` reducer then trims `e.message` to at most 32 characters and maps it to generic strings ("timeout", "server error 500", etc.). There is no distinguishing between:

- A MAC verification failure (silent decrypt error from libsodium)
- An OPK depletion error (server 400 on key fetch)
- An unknown epoch error (ratchet desync)
- A transient network error

All of these silently become the same `ConnectionStatus.Error` in the UI. A ratchet desync that makes location undeliverable is indistinguishable from a Wi-Fi dropout. Users have no path to recovery (re-pairing, re-generating OPKs) because the error type is erased. The 32-char truncation in `onConnectionError` also doesn't redact sensitive fields — if a server response includes a token fragment in the error body, it leaks into `ConnectionStatus.Error.message` and could be logged.

***

### 🟡 MEDIUM — M1: Known Bug A (Partially Fixed) — `ACTION_FORCE_PUBLISH` Race With GPS Null

**File:** [`LocationService.kt`, lines 89–103](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationService.kt)

`android_bugs.md` correctly identifies this: when `ACTION_FORCE_PUBLISH` fires and `locationSource.lastLocation.value` is null (cold-start, no GPS fix yet), the `?.let` block silently skips the send . The fix described in the bugs file (`pendingFriendSends`) **is already partially implemented** — `pendingFriendSends.add(friendId)` is called in the `else` branch of `onStartCommand`, and the set is flushed in the `lastLocation` collector in `onCreate`. However, the flush path calls `launch { locationClient.sendLocationToFriend(...) }` inside a `for` loop but doesn't clear `pendingFriendSends` atomically before the launches complete (it calls `pendingFriendSends.clear()` before launching, which is correct) — but `pendingFriendSends` itself is a plain `mutableSetOf()` accessed from both the service's `onCreate` collector coroutine (on `serviceScope`) and `onStartCommand` (main thread), creating the same concurrent-mutation risk as H2. Severity: **Medium** for the race; the original null-skip bug is largely mitigated.

***

### 🟡 MEDIUM — M2: Known Bug B — Movement Throttle Blocks First Update After Pairing

**File:** [`LocationService.kt`, `sendLocationIfNeeded`, lines 150–175](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationService.kt)

The throttle logic as reviewed in the source reads:

```kotlin
val canSend = force || lastSentTime == 0L || !isHeartbeat ||
    (isHeartbeat && now - lastSentTime > 300_000L)
```

This differs from the pseudocode in `android_bugs.md` which shows a `60_000L` cooldown for non-heartbeat updates . The current code drops the 60s non-heartbeat cooldown — meaning movement updates are passed through immediately. **Bug B appears to be fixed in the current code.** The `sendLock` Mutex correctly guards `lastSentTime`, so no data race there.

***

### 🟡 MEDIUM — M3: Known Bug C — Double-Registration of `locationCallback` (Partially Mitigated)

**File:** [`LocationService.kt`, `ensureLocationRegistration()`, lines 128–141](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationService.kt)

`android_bugs.md` flags `onStartCommand` calling `fusedClient.requestLocationUpdates(...)` on every invocation. The fix proposed — an `isRegistered` flag — **is implemented**: `ensureLocationRegistration()` guards with `if (isRegistered) return`. Both `onStartCommand` and `onCreate` call `ensureLocationRegistration()`. **Bug C is fixed.** The flag is reset to `false` in `onDestroy`, which is correct.

***

### 🟡 MEDIUM — M4: `isSharingLocation` Default is `true` — Location Shared Before User Opts In

**File:** [`LocationRepository.kt`, line 79](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationRepository.kt)

```kotlin
private val _isSharingLocation = MutableStateFlow(true)
```

The singleton is initialized with sharing enabled. The `LocationViewModel.init` block asynchronously restores the true persisted preference via `locationSource.setSharingLocation(UserPrefs.isSharing(app))`. Between `LocationRepository` construction (at app start) and the coroutine completing, any code that reads `isSharingLocation.value` will see `true` — meaning the service could briefly attempt to send location before the user's preference is loaded. Since `manageForegroundService` is triggered by the `isSharingLocation` flow collector, this could also start the foreground service on first launch even if the user previously disabled sharing.

***

### 🟡 MEDIUM — M5: Session State Loaded Non-Atomically; Race Between VM Init and Service Poll

**File:** [`LocationViewModel.kt`, init block, lines 72–88](https://github.com/danmarg/where/blob/main/android/src/androidMain/kotlin/net/af0/where/LocationViewModel.kt)

The ViewModel's `init` block launches a coroutine that:

1. Loads friends from `e2eeStore.listFriends()`
2. Reconstructs initial locations
3. Sets sharing state
4. Sets paused friends

These are four separate `StateFlow` updates dispatched without a transaction. Meanwhile, `LocationService.pollLoop()` is running concurrently and reads `locationSource.isSharingLocation.value`, `locationSource.friends`, etc. A poll arriving between steps 1 and 4 will see a partially initialized state (friends loaded but sharing not yet applied). This can cause a location send to proceed with an incomplete friend list, or `isSharingLocation` defaulting to `true` (M4) to trigger a send before `UserPrefs.isSharing(app)` has been applied.

***

### 🟢 LOW — L1: `allowBackup=false` Is Set Correctly

**File:** [`AndroidManifest.xml`, line 13](https://github.com/danmarg/where/blob/main/android/src/androidMain/AndroidManifest.xml)

`android:allowBackup="false"` is present on the `<application>` element. This prevents adb backup and Google's Auto Backup from extracting the `EncryptedSharedPreferences` file. However, this only protects the `e2ee_prefs` file; the plaintext `where_prefs` (see C1) would also be protected by this flag once it exists, which is the correct behavior — but `where_prefs` needs to move to EncryptedSharedPreferences anyway for defense in depth on rooted devices.

***

### 🟢 LOW — L2: SDK Levels and Build Configuration

**File:** [`android/build.gradle.kts`](https://github.com/danmarg/where/blob/main/android/build.gradle.kts)

- `minSdk = 26` (Android 8.0): adequate; `EncryptedSharedPreferences` and the Android Keystore are available from API 23, and StrongBox from API 28.
- `targetSdk = 35` (Android 15): correct and up to date.
- `compileSdk = 35`: correct.
- JVM target 17: appropriate for KMP Android.
- No `debuggable = false` assertion on release (not required since Gradle sets it by default, but worth documenting).
- No `testCoverageEnabled` flag — not a security issue, but notable given the E2EE complexity.

***

### 🟢 LOW — L3: Maps API Key Embedded in Manifest Meta-Data

**File:** [`AndroidManifest.xml`, lines 18–21](https://github.com/danmarg/where/blob/main/android/src/androidMain/AndroidManifest.xml)

The `MAPS_API_KEY` is injected via `manifestPlaceholders` from `local.properties`. This is the standard Google Maps pattern and is fine for CI if the key is injected via environment. However, without R8 (C2), the compiled `AndroidManifest.xml` in the APK is a plain-text XML and the key will be readable with `aapt dump`. The Maps key should also be restricted in the Google Cloud Console to this app's package name + signing certificate fingerprint.

***

## Summary Table

| ID | Area | Severity | File(s) | Status |
| :-- | :-- | :-- | :-- | :-- |
| C1 | Plaintext UserPrefs (userId, display name, paused friends) | 🔴 Critical | `UserPrefs.kt` | Open |
| C2 | No ProGuard/R8 in release build | 🔴 Critical | `build.gradle.kts` | Open |
| C3 | Default server URL is HTTP (cleartext) | 🔴 Critical | `build.gradle.kts` | Open |
| H1 | StrongBox silent fallback; no user auth required on key | 🟠 High | `SharedPrefsE2eeStorage.kt` | Open |
| H2 | `pendingFriendSends` + `awaitingFirstUpdateIds` data races | 🟠 High | `LocationService.kt`, `LocationRepository.kt` | Open |
| H3 | Crypto errors swallowed into generic status; error message may leak token fragments | 🟠 High | `LocationService.kt`, `LocationRepository.kt` | Open |
| M1 | Bug A: `ACTION_FORCE_PUBLISH` null-GPS race (partially mitigated) | 🟡 Medium | `LocationService.kt` | Partial |
| M2 | Bug B: Non-heartbeat movement throttle | 🟡 Medium | `LocationService.kt` | **Fixed** |
| M3 | Bug C: `locationCallback` double-registration | 🟡 Medium | `LocationService.kt` | **Fixed** |
| M4 | `isSharingLocation` defaults `true` before prefs loaded | 🟡 Medium | `LocationRepository.kt` | Open |
| M5 | Session state loaded non-atomically; race with service poll | 🟡 Medium | `LocationViewModel.kt` | Open |
| L1 | `allowBackup=false` correctly set | 🟢 Low | `AndroidManifest.xml` | Compliant |
| L2 | SDK levels correct; no R8 config | 🟢 Low | `build.gradle.kts` | Open (R8 part) |
| L3 | Maps API key readable in release APK without R8 | 🟢 Low | `AndroidManifest.xml`, `build.gradle.kts` | Open |


***

## Recommended Fixes by Priority

**Immediate (before any production release):**

1. Enable R8/minification in `release` buildType — add `isMinifyEnabled = true` with keep rules for `e2ee.*`, `kotlinx.serialization`, and libsodium JNI (`com.goterl.lazysodium.*`).
2. Add a release `SERVER_HTTP_URL` that is HTTPS, and add a Network Security Config blocking cleartext. Add this to `buildTypes { release { ... } }` as a separate `buildConfigField`.
3. Migrate `UserPrefs` to `EncryptedSharedPreferences` using the same `MasterKey` already constructed in `SharedPrefsE2eeStorage`.

**Short-term:**
4. Add `setUserAuthenticationRequired(true)` (with a reasonable validity duration, e.g. 60s) to the `KeyGenParameterSpec` in `SharedPrefsE2eeStorage` so that key use requires a recent device unlock. Log a warning (without key material) when StrongBox is unavailable.
5. Replace `pendingFriendSends` `mutableSetOf()` in `LocationService` with a `@GuardedBy` Mutex-protected set or a `Channel`.
6. Initialize `_isSharingLocation` to `false` and make the ViewModel restore the actual value before starting the service.
7. Introduce typed crypto exceptions (`MacVerificationException`, `UnknownEpochException`, `OpkDepletedException`) in the shared E2EE module and surface them distinctly in the UI rather than routing all errors through the generic `ConnectionStatus.Error` path.

