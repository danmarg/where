#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")"

# Load machine-specific environment if it exists
if [ -f .envrc ]; then
  source .envrc
fi

# Set TMPDIR early
export TMPDIR="${TMPDIR:-/tmp}"

# Parse arguments
SERVER_URL=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --server-url)
      SERVER_URL="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Default server URL from local.properties, or fallback
if [ -z "$SERVER_URL" ]; then
  if [ -f local.properties ]; then
    SERVER_URL=$(grep "^SERVER_HTTP_URL=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)
  fi
  # Fallback to the build.gradle default
  [ -z "$SERVER_URL" ] && SERVER_URL="http://where:8080"
fi

echo "Building with server URL: $SERVER_URL"
echo ""

# Build server
echo "=== Building server ==="
if ! nix develop --command ./gradlew :server:build; then
  echo "Server build failed."
  exit 1
fi
echo "✓ Server built"
echo ""

# Build Android debug APK
echo "=== Building Android debug APK ==="
if ! nix develop --command ./gradlew :android:assembleDebug; then
  echo "Android build failed."
  exit 1
fi
echo "✓ Android APK built"
echo ""

# Build iOS for real device (iphoneos)
echo "=== Building iOS for real device ==="

# Generate Xcode project if not present
if [ ! -f ios/Where.xcodeproj/project.pbxproj ]; then
  echo "Generating Xcode project..."
  cd ios
  nix develop --command xcodegen
  cd ..
fi

# Build for iphoneos (real device) with code signing disabled
if ! nix develop --command bash -c "cd ios && WHERE_SERVER_HTTP_URL='$SERVER_URL' xcodebuild \
  -project Where.xcodeproj \
  -scheme Where \
  -configuration Debug \
  -sdk iphoneos \
  -derivedDataPath build \
  CODE_SIGN_IDENTITY=\"\" \
  CODE_SIGNING_REQUIRED=NO \
  build 2>&1 | tee ../ios_build.log"; then
  echo "iOS build failed."
  exit 1
fi

if grep -q "BUILD FAILED" ios_build.log; then
  echo "Xcode build failed."
  exit 1
fi

echo "✓ iOS app built"
echo ""

# Summary
echo "=== Build complete ==="
echo ""
echo "Server: ./gradlew :server:run"
echo ""
echo "Android APK location:"
android_build_dir=$(nix develop --command ./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")
echo "  $android_build_dir/outputs/apk/debug/android-debug.apk"
echo "  Install: adb install -r <path>"
echo ""
echo "iOS app location (device):"
echo "  ios/build/Build/Products/Debug-iphoneos/Where.app"
echo "  Use Xcode to sign and install to device"
echo ""
echo "To run the server: ./run-server.sh"
