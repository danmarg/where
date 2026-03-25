# Where — Design Choices & Coding Standards

## Project overview
**Where** is a cross-platform real-time location sharing app (iOS, Android, Ktor server) built with Kotlin Multiplatform (KMP). The long-term goal is end-to-end encryption; v1 intentionally ships without it to validate the core data flow first.

---

## Architecture

### Module layout
| Module | Role |
|---|---|
| `shared/` | KMP library — data models, `LocationSyncClient` (WebSocket), platform `expect/actual` |
| `android/` | Android app — Compose UI, FusedLocation foreground service, Google Maps |
| `server/` | Ktor server — WebSocket hub, in-memory location store |
| `ios/` | iOS app — SwiftUI + MapKit + CoreLocation, native URLSession WebSocket |

### Data flow
```
[iOS / Android]
  GPS → LocationService / LocationManager
      → LocationSyncClient.sendLocation()  (KMP on Android, Swift on iOS)
      → WS /ws?userId=<uuid>
      → Ktor server broadcasts all locations
      → clients update map pins
```

### Real-time transport
- **WebSocket** on `/ws?userId=<uuid>` (Ktor `WebSockets` plugin, `ktor-server-websockets`).
- Server fan-outs the full user list after every location update — simple, correct, scales fine for small groups.
- Clients reconnect automatically with a 3-second backoff.

### User identity
- A random UUID is generated on first launch and persisted (`SharedPreferences` on Android, `UserDefaults` on iOS).
- No accounts, no auth in v1.

---

## Key design decisions

### Shared vs. platform-specific networking
- **Android**: uses `LocationSyncClient` from the shared KMP module (Ktor WebSocket client over OkHttp).
- **iOS**: uses a native Swift `LocationSyncService` backed by `URLSessionWebSocketTask`. Bridging Kotlin coroutines + Ktor to Swift 6 strict concurrency adds friction; native URLSession is simpler and idiomatic.
- **All protocol data models are in the shared KMP module** — `UserLocation`, `WsMessage`, and all subclasses. iOS imports the `Shared` framework and uses these types directly; there are no duplicate Swift structs mirroring the Kotlin models.
- **`LocationMessageCodec`** (in `shared/`) is a Swift-friendly singleton that handles all `kotlinx.serialization` encoding/decoding for iOS. iOS calls `LocationMessageCodec.shared.encodeLocationUpdate(...)` / `decodeUsers(text:)` instead of using Swift `Codable`.

### Maps
- **Android**: `maps-compose` (Google Maps Compose). Requires a `MAPS_API_KEY` in `local.properties`.
- **iOS**: `MapKit` via `UIViewRepresentable` — no API key needed.

### Battery efficiency
- **Android**: `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`, 30s interval, run inside a foreground service so the OS does not kill it.
- **iOS**: `distanceFilter = 50m` + `desiredAccuracy = kCLLocationAccuracyHundredMeters`; `startMonitoringSignificantLocationChanges()` when backgrounded.

### Server state
- In-memory `ConcurrentHashMap`s for locations and sessions. State is lost on restart — intentional for v1. Persistence (e.g. Redis or a DB) is deferred.

---

## Coding standards

### Kotlin (shared + server)
- **Kotlin 2.1 / JVM 17 (Android) / JVM 21 (server)**.
- `kotlinx.serialization` for all JSON. Sealed `WsMessage` uses `classDiscriminator = "type"` and `@SerialName` on each subclass.
- Coroutines: use `SupervisorJob()` so one failed child doesn't cancel siblings. Prefer `StateFlow` over `LiveData`.
- No mutable global state except the intentional `LocationRepository` singleton (bridge between service and ViewModel).
- New platform targets require an `actual fun` in `androidMain`, `iosMain`, and `jvmMain`.

### Swift (iOS)
- **Swift 6 strict concurrency** — all `ObservableObject` classes marked `@MainActor`.
- Use `async/await` for async WebSocket receive loops (`URLSessionWebSocketTask.receive()`).
- Native `URLSession` for WebSocket networking; no Ktor/coroutine bridging.
- Use KMP types (`UserLocation`, etc.) directly — no duplicate Swift structs. All protocol JSON encoding/decoding goes through `LocationMessageCodec.shared`.

### Android
- Jetpack Compose only — no XML layouts.
- ViewModels use `AndroidViewModel` when `Application` context is needed.
- `LocationRepository` is the single source of truth between the foreground `Service` and `ViewModel`.

### Dependency management
- All versions in `gradle/libs.versions.toml` (version catalog). Never hardcode versions inline.
- Add new dependencies to the catalog first, then reference via `libs.*` alias.

---

## Local development

### Server
```bash
./gradlew :server:run
curl localhost:8080/health   # → ok
curl localhost:8080/locations # → []
```

### Android (emulator)
- Server URL in `LocationViewModel` is `ws://10.0.2.2:8080/ws` (emulator loopback to host).
- Add your Maps API key: `echo "MAPS_API_KEY=your_key" >> local.properties`
- Build: `./gradlew :android:assembleDebug`

### iOS (simulator)
- Server URL in `LocationSyncService` is `ws://localhost:8080/ws`.
- Generate Xcode project: `cd ios && xcodegen` (or `xcoderun xcodegen` in nix shell). The project calls `embedAndSignAppleFrameworkForXcode` as a pre-build script automatically.
- To build the KMP framework manually: `./gradlew :shared:embedAndSignAppleFrameworkForXcode`

---

## Planned future work
- End-to-end encryption (E2EE) — protocol TBD
- Persistent server storage
- User-controlled sharing (groups, time-limited sharing)
- Push notifications when a friend's location changes significantly
