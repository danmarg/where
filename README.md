# Where

A cross-platform, end-to-end encrypted real-time location sharing app for iOS and Android, built with Kotlin Multiplatform.

![Where icon](assets/icon.png)

---

## Privacy model

Where is designed so the server learns as little as possible:

- **No accounts.** Identity is a device-scoped ephemeral X25519 key pair — no usernames, email addresses, or persistent IDs.
- **End-to-end encrypted by default.** Location payloads are encrypted with a Double Ratchet protocol before they leave the device. The server routes opaque ciphertext and cannot read coordinates.
- **No social graph.** Routing uses pairwise anonymous tokens; the server cannot reconstruct who is sharing with whom.
- **Forward secrecy.** Automated keepalives advance the ratchet even when only one party is sharing, so past sessions cannot be decrypted if a key is later compromised.

## Architecture

```
[iOS / Android]
  GPS → LocationService
      → encrypt (Double Ratchet)
      → POST /inbox/{send_token}

[Server]
  routes opaque ciphertext, no decryption

[Recipient]
  GET /inbox/{recv_token}
      → decrypt
      → update map pin
```

| Module | Description |
|--------|-------------|
| `shared/` | KMP library — data models, Double Ratchet E2EE, `LocationClient` |
| `android/` | Android app — Jetpack Compose UI, FusedLocation foreground service, Google Maps |
| `ios/` | iOS app — SwiftUI, MapKit, CoreLocation |
| `server/` | Ktor server — Anonymous Mailbox API, Redis-backed message store |
| `cli/` | Command-line tool for scripting and testing |

## Getting started

### Server

Requires Redis.

```bash
./gradlew :server:run
curl localhost:8080/health   # → ok
```

### Android

1. Add your keys to `local.properties`:
   ```properties
   MAPS_API_KEY=your_google_maps_key
   SERVER_HTTP_URL=http://10.0.2.2:8080   # emulator; use LAN IP for a real device
   ```
2. Build and install:
   ```bash
   ./gradlew :android:assembleDebug
   ```

### iOS

1. Generate the Xcode project:
   ```bash
   cd ios && xcodegen generate
   ```
2. Open `ios/Where.xcodeproj` in Xcode and run on simulator or device.
   - The KMP shared framework is built automatically as a pre-build step.
   - For a real device, update the server URL in `ios/Sources/Where/ServerConfig.swift`.

### CLI

Useful for scripting, integration testing, and pairing two terminals to verify the protocol end-to-end.

```bash
./gradlew :cli:installDist

# Terminal A — create an invite
./cli/build/install/cli/bin/cli init
./cli/build/install/cli/bin/cli invite          # prints a where.af0.net/invite#… URL

# Terminal B — accept and start syncing
./cli/build/install/cli/bin/cli join <url> bob
./cli/build/install/cli/bin/cli sync
```

See [MANUAL_TESTING.md](MANUAL_TESTING.md) for a full walkthrough.

## Development

### Running tests

```bash
./gradlew :shared:jvmTest
```

The E2EE crypto tests live in `shared/src/commonTest/kotlin/net/af0/where/e2ee/`.

### Local configuration

Machine-specific paths go in gitignored local files — never in checked-in config:

| File | Purpose |
|------|---------|
| `local.properties` | Android SDK path, Maps API key, server URL |
| `local.gradle.kts` | Build output dirs, cache dirs, SDK locations |
| `.envrc` | Environment variables (`GRADLE_USER_HOME`, `KONAN_DATA_DIR`, etc.) |

## Protocol documentation

- [E2EE location sync design](docs/e2ee-location-sync.md) — threat model, key exchange, Double Ratchet adaptation, wire format
- [State machine](docs/state_machine.md) — pairing and session lifecycle

## License

[MIT](LICENSE.md)
