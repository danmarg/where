<img src="https://r2cdn.perplexity.ai/pplx-full-logo-primary-dark%402x.png" style="height:64px;margin-right:32px"/>

# You are a senior iOS engineer reviewing the Swift/SwiftUI implementation of a location-sharing app. Fetch and read the full contents of the ios/ directory at:

[https://github.com/danmarg/where/tree/main/ios](https://github.com/danmarg/where/tree/main/ios)

Also read the protocol spec section on iOS key storage (§5.5 of https://github.com/danmarg/where/blob/main/docs/e2ee-location-sync.md) and the build script at:
[https://github.com/danmarg/where/blob/main/build.sh](https://github.com/danmarg/where/blob/main/build.sh)

Review the following areas, citing specific files and line numbers:

Keychain integration. The spec requires all session keychain items to use kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly to exclude them from iCloud Backup. Verify that every Keychain write uses this attribute. Are there any session state items persisted to UserDefaults or other unprotected storage instead?

Memory handling for key material. The spec requires Data buffers holding key material to be zeroed before dealloc. Identify all locations where key bytes (EK.priv, OPK.priv, root key, chain key, message key) are held in Swift Data or [UInt8] and whether they are explicitly zeroed after use. Swift ARC does not guarantee immediate dealloc — assess the risk.

Background location. How does the iOS app handle location updates when backgrounded (CLLocationManager background modes, allowsBackgroundLocationUpdates, significant-location-change mode)? Is the polling loop for receiving friends' locations reliable in background?

KMP integration. How does the Swift code interface with the KMP shared module for crypto operations? Are there any type marshalling issues (e.g., ByteArray to Data conversions) that could silently truncate or misalign byte buffers passed to crypto functions?

Session lifecycle and state machine. How does the iOS app manage the E2EE session state machine transitions (pairing → active → epoch rotation → re-keying)? Is there a SwiftUI state management issue where session state could be read before it's fully initialized?

QR code / invite link security. How is the QR code or invite link generated and displayed? Is the ephemeral public key EK_A.pub generated fresh each time the "Add Friend" view is presented, or is it cached from a previous session? Does the app prevent screenshots of the QR in environments where that is a risk?

Entitlements and capabilities. Review the .entitlements file and Info.plist for: location usage descriptions, background modes, Keychain sharing groups (is the Keychain access group app-scoped or shared?), and any over-broad entitlements.
Produce a prioritized finding list with severity labels.

Here is the full review. The downloadable report above contains all findings with exact line-level analysis, code excerpts, and recommendations.

***

## Prioritized Findings

| \# | Severity | Area | Finding |
| :-- | :-- | :-- | :-- |
| F-01 | **CRITICAL** | Memory | Zero key material never called |
| F-02 | **HIGH** | Storage | `where_user_id` in unprotected `UserDefaults` |
| F-03 | **MEDIUM** | Storage | `isSharingLocation`, `displayName`, `pausedFriendIds` in `UserDefaults` |
| F-04 | **HIGH** | QR/Invite | Stale ephemeral key reuse across invite presentations |
| F-05 | **HIGH** | KMP Bridge | No key-size validation before `kotlinByteArray` conversion |
| F-06 | **MEDIUM** | Background | Timer-based poll suspended when device is stationary |
| F-07 | **LOW** | QR/Invite | No screenshot detection on `InviteSheet` |
| F-08 | **LOW** | Entitlements | No explicit Keychain access group in `Where.entitlements` |
| F-09 | **MEDIUM** | Stability | `try!` and `result.first!` in crypto code paths |
| F-10 | **LOW** | State machine | Fragile ordering between `inviteState` and `pendingInitPayload` writes |
| F-11 | **LOW** | Build | Keystore password exported as env var; key/store password shared |


***

## F-01 · CRITICAL — Key Material Never Zeroed

`KotlinByteArrayUtils.swift` creates a Swift `Data` copy of every KMP `KotlinByteArray` (EK.priv, root key, chain key, message key) but never zeroes it.  Swift ARC does not guarantee prompt deallocation, and `Data` values may linger in heap memory for seconds after their last reference is dropped — particularly inside `Task` closures awaiting at suspension points. The spec (§5.5) explicitly requires zeroing before dealloc. The fix is a `memset_s`-based `Data.zeroize()` extension called in `defer` blocks immediately after each key buffer is consumed.

***

## F-02 · HIGH — `where_user_id` Bypasses Keychain

`UserIdentity.swift` writes the permanent device routing identifier to `UserDefaults.standard`, not the Keychain.  This means the value is included in iCloud Backup and iTunes encrypted backups, directly violating the spec's §5.5 requirement that all session-state items use `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.  `KeychainE2eeStorage.putString(key:value:)` already correctly applies this attribute — the fix is simply routing `where_user_id` through it.

***

## F-04 · HIGH — Stale Ephemeral QR Key Reused Across Sessions

`InviteSheet` receives a `qrPayload` binding from `ContentView`. If the user opens the invite sheet, dismisses it without completing the exchange, and re-opens it, the `inviteState` conditional in the dismiss handler can skip `clearInvite()` — meaning `EK_A.pub` from the first session is served to a second scanner.  The spec (§4.1) requires a fresh ephemeral key per invite presentation. The `cachedImage` in `QrCodeView` compounds this: the QR image is only regenerated when `content` changes, so an identical payload silently displays the old QR.

***

## F-05 · HIGH — KMP Bridge: No Key Length Validation

`kotlinByteArray(from:)` passes any `Data` to the KMP X25519 layer without asserting the expected 32-byte length.  A server-provided oversized buffer will either trap on the `Int32(data.count)` conversion or crash inside the Kotlin native runtime, enabling a denial-of-service attack from a malicious server. For cryptographic key material specifically, a `guard data.count == 32` precondition must be added before crossing the bridge.

***

## F-06 · MEDIUM — Background Poll: Stationary Device Receives No Friend Updates

`Info.plist` declares only `UIBackgroundModes: location`. The inbound poll timer runs on `RunLoop.main`, which is suspended when the app backgrounds.  CoreLocation only wakes the app when the device moves — so a stationary user will not see friend location updates until they move or foreground the app, regardless of the configured 5-minute heartbeat. Registering a `BGAppRefreshTask` would fix this.

***

## Keychain Audit: What's Correct

`KeychainE2eeStorage` correctly applies `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` on both the `SecItemAdd` and `SecItemUpdate` paths — every Keychain write through this class satisfies the spec.  The only gap is that session-adjacent data (`userId`, `displayName`, `pausedFriendIds`, `isSharingLocation`) bypasses this class entirely and goes to `UserDefaults`.

