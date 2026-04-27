# Manual Testing Guide

This guide describes how to manually test the Where system (CLI, Android, and iOS).

## 1. CLI Tool

The CLI tool allows you to simulate a user from your terminal. It supports creating invites, joining friends, and sending/polling location updates.

### Build and Run
```bash
# Build the CLI
./gradlew :cli:installDist

# Run the CLI (default host is http://localhost:8080)
./cli/build/install/cli/bin/cli help

# Specify a custom host
./cli/build/install/cli/bin/cli --host http://192.168.1.50:8080 help
```

### Basic Workflow
1. **Initialize:** `cli init` (creates `alice.priv` and `alice.pub` by default).
2. **Invite:** `cli invite` (outputs a `https://where.af0.net/invite#...` URL).
3. **Join:** From another terminal/device, `cli join <url> <name>`.
4. **Sync:** `cli sync` (starts a loop that polls and sends random location updates).

---

## 2. Android App

### Server Configuration
Android pulls the server URL from `local.properties`. 
1. Create or edit `local.properties` in the root directory.
2. Add your server's address:
   ```properties
   # For Emulator (10.0.2.2 points to host machine)
   SERVER_HTTP_URL=http://10.0.2.2:8080

   # For Real Device (use your machine's LAN IP)
   SERVER_HTTP_URL=http://192.168.1.50:8080
   ```

### Build and Push to Device
1. **Enable Wireless Debugging** on your Android device.
2. **Connect via ADB:**
   ```bash
   adb connect <device-ip>:5555
   ```
3. **Run the app:**
   ```bash
   # Specify the host in local.properties as ANDROID_ADB_HOST for the script to find it
   echo "ANDROID_ADB_HOST=<device-ip>" >> local.properties
   ./scripts/run-android.sh
   ```

---

## 3. iOS App

### Server Configuration
iOS uses `ServerConfig.swift` for configuration.
1. Open `ios/Sources/Where/ServerConfig.swift`.
2. For **Simulator**, the defaults work (`localhost`).
3. For **Real Device**, update the `#else` block with your server's LAN IP or production URL:
   ```swift
   #else
   static let httpBaseUrl = "http://192.168.1.50:8080"
   #endif
   ```

### Build and Copy to Device
1. **Connect your iPhone** via USB.
2. **Open the project in Xcode:**
   ```bash
   (cd ios && xcodegen generate)
   open ios/Where.xcodeproj
   ```
3. **Select your device** and click **Run** (Play button) in Xcode.
4. If you prefer the command line (requires `ios-deploy`):
   ```bash
   ./scripts/run-ios.sh  # Note: This script currently targets the simulator.
   ```
   *Note: For real devices, it is recommended to use Xcode to handle signing certificates and provisioning profiles.*

---

## 4. Troubleshooting

### Simulator Fallbacks
- **iOS/Android:** Since cameras don't work in the simulator, clicking "Scan" will open a manual input dialog. You can paste a `https://where.af0.net/invite#...` URL here to simulate a scan.
