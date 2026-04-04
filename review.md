🐛 Bug: iOS isSending flag creates a race, not a guard

In LocationSyncService.sendLocation, isSending is set to true synchronously
before a Task {} is launched, and reset to false inside the Task after the
network call. Because the class is @MainActor, the Task body runs on the main
actor too — so isSending is technically accessed safely. However, the
forced-send path (effectiveForce) skips the isSending guard but does not reset
isSending = true before launching its Task, meaning a forced send that races
with an in-flight normal send will both run and both set isSending = false at
the end, leaving the state correct only if Tasks execute serially. If a
force-send fires and completes before the in-flight normal send, isSending
becomes false prematurely and the normal send's final isSending = false is a
harmless double-clear — but this is fragile and should be documented or
serialized more explicitly.

🐛 Bug: Android handleNewLocation reads SharedPreferences on every call from the
main looper

handleNewLocation is called from locationCallback.onLocationResult, which runs
on mainLooper. Inside it, getSharedPreferences(...) is called synchronously
before dispatching the coroutine. While a single prefs read is fast, this is a
discouraged pattern on the main thread for a service that may receive many
callbacks. The prefs read should be moved inside the serviceScope.launch {}
block.

🐛 Bug: Android pendingFriendId has a TOCTOU race

pendingFriendId is written on the main thread (in onStartCommand) and
read+cleared inside a serviceScope.launch coroutine on Dispatchers.IO. These
accesses are unsynchronized — if two ACTION_FORCE_PUBLISH intents arrive before
the first coroutine consumes pendingFriendId, the second will overwrite the
first and the first friend will never receive the forced location push. Since
LocationViewModel fires one intent per pairing, this is unlikely in practice,
but it's a real race. Use @Volatile or make pendingFriendId an AtomicReference.

🐛 Bug: iOS throttle asymmetry — foreground sends throttled at 60s, not 15s

Android's handleNewLocation uses a 15s non-heartbeat cooldown. iOS sendLocation
uses 1 * 60 (60 seconds) for non-heartbeat sends. These should match. Given the
FusedLocationProvider fires at 30s intervals minimum on Android, the real
effective rate ends up being fine, but the comment in LocationSyncStateTest.kt
explicitly calls out 15s as the intended value for the Bug B fix, making the 60s
iOS value inconsistent with documented intent.

⚠️ Concern: pollPendingInvite is silently skipped in background

pollAll(updateUi: false) now skips pollPendingInvite(). If the app is
backgrounded during a key exchange (e.g., user locks the phone right after
scanning a QR), the invite will never be processed until the next foreground
wake. This could leave pairing permanently stuck if the background task expires
before the app returns to foreground.

State Machine Simplicity
The overall state machine is now cleaner than before — removing
sendEncryptedLocation from the ViewModel's location-combine flow and
centralizing it in the service is a genuine simplification. The isExchanging
flag is now well-guarded with finally blocks.

However, the pendingForcedSendAfterPairing flag on iOS creates a second implicit
state that interacts with isSending, lastSentTime, and pendingForcedFriendId
(Android). Across both platforms you now have four overlapping throttle/pending
states:

State   Android iOS
Cold-start pending  pendingFriendId: String?    pendingForcedSendAfterPairing:
Bool
In-flight guard (none — coroutine launches freely)  isSending: Bool
Throttle timestamp  lastSentTime: Long  lastSentTime: Date
Heartbeat vs movement   isHeartbeat: Boolean param  isHeartbeat: Bool param
The iOS-only isSending guard has no Android equivalent, meaning the platforms
have meaningfully diverged in their throttle logic, which will make future bugs
harder to cross-reference.

Test Coverage
What's good

LocationServiceTest (Android/Robolectric) correctly tests Bug A (cold-start
queuing) and Bug C (deduplication via isRegistered).

LocationSyncStateTest in commonTest is a nice platform-agnostic simulation and
tests the correct invariants.

The e2e script now explicitly verifies throttle and force-override behavior
end-to-end.

What's missing or weak

iOS test is not a real test.
LocationSyncServiceTests.testPendingForcedSendAfterPairing_BugA calls
confirmQrScan which will immediately fail because the QR payload [0,1,2] is not
a valid key exchange payload — the network call will throw, defer { isExchanging
= false } runs, but pendingForcedSendAfterPairing may never be set because the
guard condition is inside a guard let initPayload block that will return early.
The test then has no assertions at all. It is essentially a no-op.

No test for the iOS throttle asymmetry. The shared
testMovementThrottle_BugB_Android test only validates the 15s Android value. A
corresponding iOS-side test that asserts the 60s cooldown (or corrects it to
15s) is absent.

No test for pendingFriendId race on Android. There's no test that sends two
ACTION_FORCE_PUBLISH intents back-to-back and verifies both friends receive a
location.

testDeduplication_BugC doesn't actually test behavior. It calls startCommand
twice with a plain intent (no ACTION_FORCE_PUBLISH) and then asserts
isRegistered == true — which was already true after onCreate. There's no
verification that fusedClient.requestLocationUpdates was called exactly once.
Consider using a Robolectric shadow to count invocations.

Minor Issues
formatSafetyNumber now returns uppercase hex (since toHex() likely produces
uppercase). Signal-style safety numbers are traditionally decimal groups — the
change to hex is intentional per issue #29, but worth confirming the companion
app / web UI renders the same format.

Android LocationService now instantiates E2eeStore and LocationClient directly
in onCreate. This means two independent E2eeStore instances exist (one in the
service, one in the ViewModel), both backed by SharedPrefsE2eeStorage. If the
ViewModel writes a ratchet state update while the service is mid-send, there
could be storage contention. This pre-exists in spirit but is newly aggravated
by the service now doing full sendLocation calls. Consider a process-level
singleton or a bound service pattern.

The @Config(application = Application::class) in LocationServiceTest means
BuildConfig.SERVER_HTTP_URL will be empty/null at test time. If LocationClient
doesn't tolerate a null/empty URL in its constructor, onCreate will crash and
tests will silently pass empty. Verify or mock BuildConfig.


