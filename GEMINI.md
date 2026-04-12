# Where — Project Context & Guidelines

This document provides essential context and instructions for the **Where** project, a cross-platform real-time location sharing application.

## Project Overview
**Where** is built using Kotlin Multiplatform (KMP). It enables real-time location sharing between iOS and Android devices via a central Ktor server. The project utilizes end-to-end encryption (E2EE) by default for all location data.

### Tech Stack
- **Languages:** Kotlin 2.1 (Shared, Android, Server, CLI), Swift 6 (iOS).
- **UI Frameworks:** Jetpack Compose (Android), SwiftUI (iOS).
- **Backend:** Ktor (Netty) with a REST-based Mailbox API.
- **Data Serialization:** `kotlinx.serialization` (JSON).
- **Dependency Management:** Gradle Version Catalog (`libs.versions.toml`).
- **Build System:** Gradle (Kotlin DSL).
- **Maps:** Google Maps (Android), MapKit (iOS).

## Module Architecture
| Module | Role |
|---|---|
| `shared/` | Core KMP library: data models, E2EE crypto implementations (Double Ratchet), and the `E2eeMailboxClient`. |
| `android/` | Android application: Compose UI, foreground location service. |
| `ios/` | iOS application: SwiftUI, native Swift implementations for polling and UI. |
| `server/` | Ktor server: An anonymous, in-memory "Mailbox" service. No persistence, no accounts. |
| `cli/` | Kotlin JVM CLI: Utility tool for management and testing. |

## Development Workflows

### Environment Setup
- Machine-specific overrides should be placed in `local.gradle.kts` or `local.properties` (both gitignored).

### Building and Running
The following shell scripts are provided for convenience:
- **Server:** `./run-server.sh` or `./gradlew :server:run`
- **CLI:** `./run-cli.sh` or `./gradlew :cli:run`
- **Android:** `./run-android.sh` or `./gradlew :android:assembleDebug`
- **iOS:** 
    1. `cd ios && xcodegen` (Generates `.xcodeproj`)
    2. `./run-ios.sh` or open `Where.xcodeproj` in Xcode.

### Testing
- **Shared Tests:** `./gradlew :shared:jvmTest` (Note: requires a Java Runtime in the environment).
- **E2EE Tests:** Located in `shared/src/commonTest/kotlin/net/af0/where/e2ee/`.
- **E2E Integration:** `./e2e-test.sh`

## Coding Standards & Conventions

### Kotlin (Shared, Android, Server)
- **Toolchain:** JVM 17 for Android, JVM 21 for Server/CLI.
- **Concurrency:** Coroutines and `StateFlow`. Prefer `SupervisorJob` for multi-child scopes.
- **Serialization:** Use `@SerialName` on all sealed class subclasses for stable JSON discriminators.
- **Dependencies:** Always use the Version Catalog (`libs.*`).

### Swift (iOS)
- **Strict Concurrency:** Adhere to Swift 6 concurrency rules. `@MainActor` for UI-bound classes.
- **Networking:** Native Swift implementation for HTTP polling/posting.
- **KMP Interop:** Uses shared models and E2EE logic from the KMP `shared` module.

### Android
- **Compose Only:** No XML layouts.
- **State Management:** `LocationRepository` acts as the singleton source of truth between services and ViewModels.

## Key Design Decisions
- **Transport (Mailbox API):** Communication is handled via a stateless HTTP Mailbox API (`POST /inbox/{token}` to send, `GET /inbox/{token}` to poll).
- **Directional Routing:** To prevent clients from polling their own messages, each session uses separate `sendToken` and `recvToken` derived using an `isAliceToBob` flag.
- **Identity:** Identity is determined by the device key itself (ephemeral X25519 session keys). There are no stable random UUIDs or long-term identity keys. Fingerprints are derived from session-scoped ephemeral public keys.
- **E2EE:** Uses a Double Ratchet-inspired protocol with X25519 ephemeral keys, HKDF-SHA-256 for ratcheting, and AES-256-GCM for encryption. Refer to `docs/e2ee-location-sync.md` for the protocol spec.

## Important Files
- `CLAUDE.md`: Engineering standards and local configuration notes. 
- `gradle/libs.versions.toml`: Central dependency management.
- `docs/e2ee-location-sync.md`: Full cryptographic protocol specification.
- `docs/IMPLEMENTATION-CHECKLIST.md`: Implementation status and roadmap for E2EE features.
