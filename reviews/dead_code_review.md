# Dead Code & Code Quality Review

**Reviewed:** `shared/`, `android/`, `ios/`

---

## Dead Code

### HIGH PRIORITY: `PollBatchResult.outgoing` Is Never Populated

**Files:** `E2eeStore.kt:457-468, 488, 568`; `LocationClient.kt:104-106`

```kotlin
data class PollBatchResult(
    val decryptedLocations: List<LocationPlaintext>,
    val outgoing: List<OutgoingMessage>,   // always empty
)

// In processBatch:
val outgoing = mutableListOf<OutgoingMessage>()
// ... nothing ever appended ...
return PollBatchResult(decryptedLocations, outgoing)

// In LocationClient.pollFriend — always a no-op:
for (out in result.outgoing) {
    mailboxClient.post(baseUrl, out.token, out.payload)
}
```

The `outgoing` mechanism was designed for `processBatch` to auto-generate keepalives, but that responsibility moved to `LocationClient.pollFriend` directly. The infrastructure (`OutgoingMessage`, `PollBatchResult.outgoing`, the iteration loop) is now dead.

**Fix:** Remove `OutgoingMessage` data class, `PollBatchResult.outgoing` field, and the dead `for (out in result.outgoing)` loop. Simplify `processBatch` to return `List<LocationPlaintext>` directly (or keep the result wrapper without `outgoing`).

---

### MEDIUM: `LocationPlaintext` Duplicates `MessagePlaintext.Location`

**File:** `Types.kt:179-198`; `Session.kt:526-546`

`LocationPlaintext` and `MessagePlaintext.Location` represent the same data:

| Field | `LocationPlaintext` | `MessagePlaintext.Location` |
|-------|--------------------|-----------------------------|
| lat | ✓ | ✓ |
| lng | ✓ | ✓ |
| acc | ✓ | ✓ |
| ts | ✓ | ✓ |
| precision | ✗ | ✓ |
| pn | ✗ | ✓ |

`processBatch` converts `MessagePlaintext.Location` → `LocationPlaintext`, silently dropping `precision` and `pn`. `precision` is part of the wire format and is meaningful to the UI (FINE vs COARSE display). Dropping it in the conversion means the precision mode is invisible to callers of `processBatch`.

**Fix:** Remove `LocationPlaintext`. Use `MessagePlaintext.Location` throughout. Callers already have all necessary fields. Eliminates one type, one conversion, and the silent precision/pn drops.

---

### LOW: `aliceEkPub` and `bobEkPub` in `SessionState` Duplicate `aliceFp`/`bobFp` Usage

**File:** `Types.kt:51-52`

`aliceEkPub` and `bobEkPub` are stored in `SessionState` solely to compute the safety number (`FriendEntry.safetyNumber`). They are never used for any cryptographic operation after session initialization. They add ~64 bytes to every persisted session and complicate serialization.

**Consider:** Store only the safety number bytes at initialization time, or keep the EK pubs but document that they are UI-only.

---

### LOW: `format()` Extension on `StringResource` Is a Simplified Stub

**File:** `Types.kt:155-171`

```kotlin
fun dev.icerock.moko.resources.StringResource.format(vararg args: Any?): String {
    val raw = StringDesc.Resource(this).toString()
    var result = raw
    args.forEachIndexed { i, arg ->
        val placeholder = "%${i + 1}\$s"
        val simplePlaceholder = "%s"
        val digitPlaceholder = "%d"
        result = result.replace(placeholder, arg.toString())
            .replace(simplePlaceholder, arg.toString())
            .replace(digitPlaceholder, arg.toString())
    }
    return result
}
```

This function is used in one place: `MR.strings.error_server.format(e.statusCode)` in `LocationRepository.kt`. The implementation tries all three placeholder patterns (`%1$s`, `%s`, `%d`) sequentially with `replace`, meaning if the string contains multiple placeholders only one arg value will be substituted — and `replace` replaces all occurrences, not just the first.

This is fragile and wrong for any format string with multiple args. Since it's only used for one error message, the correct fix is to either:
- Use `moko-resources`' built-in platform formatting, or
- Inline the single call as a string template: `"Server error: ${e.statusCode}"`

---

### LOW: `UserStore` Stores Non-Sensitive Data in Encrypted Storage

**Files:** `UserStore.kt`, `KeychainE2eeStorage.swift`, `SharedPrefsE2eeStorage.kt`

`UserStore` persists:
- `isSharingLocation` (Boolean)
- `displayName` (String)
- `pausedFriendIds` (Set<String>)
- `lastMapCamera` (lat, lng, zoom)

None of these require encryption. They share the same `E2eeStorage` as `E2eeStore`, meaning the Keychain / `EncryptedSharedPreferences` is accessed on every camera position change or sharing toggle. This is unnecessary overhead and — on Android — is likely contributing to the BootReceiver reliability issue, since `UserPrefs.isSharing()` tries to access the encrypted store before CE storage is unlocked.

**Fix:** Separate `UserStore` onto a plaintext storage backend (`UserDefaults` on iOS, regular `SharedPreferences` on Android). `E2eeStorage` should be used only for session key material.

---

## Code Quality

### `hmacSha256` Duplicated Across Both Platform Files

**Files:** `iosMain/.../CryptoPrimitivesImpl.kt:30-59`, `jvmAndAndroidMain/.../CryptoPrimitivesImpl.kt:27-55`

The HMAC-SHA-256 implementation is identical in both files — 30 lines of pure Kotlin that only depends on `sha256()`. Since `sha256` is already an `expect fun`, HMAC can be implemented once in `commonMain` on top of it.

**Fix:** Move `hmacSha256` to a new `commonMain` file (e.g., `Hmac.kt`). Keep only the actual platform-native operations (`sha256`, `x25519`, `aeadEncrypt`, `aeadDecrypt`, `randomBytes`, `zeroize`) as `expect/actual`.

---

### `initSession` Passes `sk` as Parameter Named `rootKey` to `deriveRoutingToken`

**File:** `KeyExchange.kt:224-235`

```kotlin
val sendToken = if (isAlice) {
    deriveRoutingToken(sk, aliceFp, bobFp)   // sk passed as 'rootKey'
}
```

`deriveRoutingToken`'s first parameter is named `rootKey` but here it receives `sk`. The spec is clear (`T_AB_0 = HKDF(ikm=SK, ...)`) so the code is correct, but the mismatch between the parameter name and the argument is a readability hazard. A future refactor might incorrectly substitute `initialRootKey` here.

**Fix:** Either rename the parameter to `ikm` / `keyMaterial`, or add an inline comment: `// ikm = SK at epoch 0, or ratcheted rootKey for subsequent epochs`.

---

### `processBatch` Exception Swallowing Has No Logging

**File:** `E2eeStore.kt:546-549`

```kotlin
} catch (e: Exception) {
    // Skip individually bad messages to prevent head-of-line blocking
}
```

Authentication failures, protocol errors, and replay attempts are all silently consumed. At minimum, these should be logged at DEBUG level with the exception type (not message, to avoid leaking ciphertext details).

---

### Redundant `println("[DEBUG] ...")` Calls in Production Code

**Files:** `LocationClient.kt:232`, `E2eeStore.kt:169`

```kotlin
println("[DEBUG] Fresh keepalive transition failed: ${e.message}")
println("[E2eeStore] Error loading state: ${e.message}")
```

Production code should use a platform logging abstraction (e.g., `expect fun log(tag: String, msg: String)` or just the platform's `Log.d`/`NSLog`), not `println`. These will appear in logcat/Console but with no tag, level, or filtering.

---

### `EncryptedOutboxMessage.v` Field Is Always 1 and Never Checked

**File:** `Types.kt:322-327`

```kotlin
@Serializable
data class EncryptedOutboxMessage(
    val v: Int = 1,
    val token: String,
    val payload: MailboxPayload,
)
```

`v` is serialized and persisted but never read. It was presumably added for forward compatibility, but without a deserialization check it provides no protection.

**Fix:** Either remove `v` if not yet needed, or add a check on load: `require(v == 1) { "unsupported outbox version $v" }`.
