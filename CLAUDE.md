# Where — Design Choices & Coding Standards

## Project overview
**Where** is a cross-platform real-time location sharing app (iOS, Android, Ktor server) built with Kotlin Multiplatform (KMP). It uses end-to-end encryption (E2EE) by default.

---

## Architecture

### Module layout
| Module | Role |
|---|---|
| `shared/` | KMP library — data models, E2EE crypto implementations (Double Ratchet), `LocationClient` |
| `android/` | Android app — Compose UI, FusedLocation foreground service, Google Maps |
| `server/` | Ktor server — Anonymous Mailbox API, Redis-backed persistent mailbox store |
| `ios/` | iOS app — SwiftUI + MapKit + CoreLocation, native HTTP polling |

### Data flow
```
[iOS / Android]
  GPS → LocationService / LocationManager
      → LocationClient.sendLocation()  (KMP on Android, Swift on iOS)
      → POST /inbox/{send_token}
      → recipient polls GET /inbox/{recv_token}
      → clients update map pins
```

### Real-time transport
- **Mailbox API** on `/inbox/{token}` (Ktor REST endpoints).
- Server routes opaque encrypted payloads by pairwise routing tokens.
- Clients poll for updates at a constant rate.

### User identity
- Identity is determined by the device key itself (ephemeral X25519 session keys).
- No stable random UUIDs or long-term identity keys.
- Fingerprints are derived from session-scoped ephemeral public keys.

---

## Key design decisions

### Shared vs. platform-specific networking
- **Android**: uses `LocationClient` from the shared KMP module (Ktor HTTP client over OkHttp).
- **iOS**: uses a native Swift implementation for HTTP polling/posting.
- **All protocol data models are in the shared KMP module**. iOS imports the `Shared` framework and uses these types directly.

### Maps
- **Android**: `maps-compose` (Google Maps Compose). Requires a `MAPS_API_KEY` in `local.properties`.
- **iOS**: `MapKit` via `UIViewRepresentable` — no API key needed.

### Battery efficiency
- **Android**: `FusedLocationProviderClient` with `PRIORITY_BALANCED_POWER_ACCURACY`, 30s interval, run inside a foreground service so the OS does not kill it.
- **iOS**: `distanceFilter = 50m` + `desiredAccuracy = kCLLocationAccuracyHundredMeters`; `startMonitoringSignificantLocationChanges()` when backgrounded.

### Server state
- Mailboxes are persisted in Redis. State survives restarts.
- Messages are retained for 7 days, aligning with the client re-pair timeout.

---

## Coding standards

### Kotlin (shared + server)
- **Kotlin 2.1 / JVM 17 (Android) / JVM 21 (server)**.
- `kotlinx.serialization` for all JSON. Sealed classes use `classDiscriminator = "type"` and `@SerialName` on each subclass.
- Coroutines: use `SupervisorJob()` so one failed child doesn't cancel siblings. Prefer `StateFlow` over `LiveData`.
- No mutable global state except the intentional `LocationRepository` singleton (bridge between service and ViewModel).

### Swift (iOS)
- **Swift 6 strict concurrency** — all `ObservableObject` classes marked `@MainActor`.
- Use `async/await` for async polling loops.
- Native `URLSession` for networking; no Ktor/coroutine bridging.
- Use KMP types directly — no duplicate Swift structs.

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
```

### Android (emulator)
- Server URL in `local.properties` (or default http://10.0.2.2:8080).
- Add your Maps API key: `echo "MAPS_API_KEY=your_key" >> local.properties`
- Build: `./gradlew :android:assembleDebug`

### iOS (simulator)
- Server URL in `ServerConfig.swift` (default http://localhost:8080).
- Generate Xcode project: `cd ios && xcodegen`. The project calls `embedAndSignAppleFrameworkForXcode` as a pre-build script automatically.
- To build the KMP framework manually: `./gradlew :shared:embedAndSignAppleFrameworkForXcode`

### Local build configuration

Machine-specific paths (build output dirs, SDK locations, cache dirs) must **never** be added to checked-in files (`gradle.properties`, `build.gradle.kts`, etc.). Use gitignored local overrides instead:

- **`local.gradle.kts`** (gitignored) — applied automatically by the root `build.gradle.kts` if present.
- **`.envrc`** (gitignored) — use for env vars like `GRADLE_USER_HOME`, `KONAN_DATA_DIR`, `TMPDIR`.
- **`local.properties`** (gitignored) — Android SDK path and other local Android properties.

The `/Volumes/Ext` external drive is used on the dev machine for all large caches and build outputs. These settings live in `local.gradle.kts` and `.envrc`, not in source control.

### Running tests

```bash
./gradlew :shared:jvmTest
```

The E2EE crypto library tests live in `shared/src/commonTest/kotlin/net/af0/where/e2ee/`
and run on the JVM target via `:shared:jvmTest`.

---

## Planned future work
- User-controlled sharing (groups, time-limited sharing)
- Push notifications when a friend's location changes significantly
