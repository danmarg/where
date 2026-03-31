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
BUILD_FLAVOR="debug"
while [[ $# -gt 0 ]]; do
  case $1 in
    --server-url)
      SERVER_URL="$2"
      shift 2
      ;;
    --flavor)
      BUILD_FLAVOR="$2"
      shift 2
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Validate flavor
if [[ "$BUILD_FLAVOR" != "debug" && "$BUILD_FLAVOR" != "release" ]]; then
  echo "Invalid flavor: $BUILD_FLAVOR (must be 'debug' or 'release')"
  exit 1
fi

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
if ! ./gradlew :server:build; then
  echo "Server build failed."
  exit 1
fi
echo "✓ Server built"
echo ""

# Build Android AAB (App Bundle)
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  GRADLE_TASK="bundleDebug"
else
  GRADLE_TASK="bundleRelease"
  # Prompt for signing credentials for release builds
  echo "=== Release Build Signing ==="
  read -sp "Enter keystore password: " KEYSTORE_PASSWORD
  echo ""
  export KEYSTORE_FILE=~/where-release-key.jks
  export KEYSTORE_PASSWORD
  export KEY_PASSWORD=$KEYSTORE_PASSWORD  # Same as keystore password
fi
echo "=== Building Android $BUILD_FLAVOR AAB ==="
if ! bash -c "KEYSTORE_FILE='$KEYSTORE_FILE' KEYSTORE_PASSWORD='$KEYSTORE_PASSWORD' KEY_PASSWORD='$KEY_PASSWORD' ./gradlew :android:$GRADLE_TASK"; then
  echo "Android build failed."
  exit 1
fi
echo "✓ Android AAB built ($BUILD_FLAVOR)"
echo ""

# Build iOS for real device (iphoneos)
echo "=== Building iOS for real device ==="

# Generate Xcode project if not present
if [ ! -f ios/Where.xcodeproj/project.pbxproj ]; then
  echo "Generating Xcode project..."
  cd ios
  xcodegen
  cd ..
fi

# Build for iphoneos (real device) with code signing disabled
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  XCODE_CONFIGURATION="Debug"
else
  XCODE_CONFIGURATION="Release"
fi
echo "=== Building iOS for real device ($XCODE_CONFIGURATION) ==="
if ! bash -c "cd ios && WHERE_SERVER_HTTP_URL='$SERVER_URL' xcodebuild \
  -project Where.xcodeproj \
  -scheme Where \
  -configuration $XCODE_CONFIGURATION \
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

echo "✓ iOS app built ($XCODE_CONFIGURATION)"
echo ""

# Summary
echo "=== Build complete ==="
echo ""
echo "Server: ./gradlew :server:run"
echo ""
echo "Android AAB location:"
android_build_dir=$(./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")
echo "  $android_build_dir/outputs/bundle/$BUILD_FLAVOR/android-$BUILD_FLAVOR.aab"
echo "  Upload to Google Play Store or use bundletool to test"
echo ""
echo "iOS app location (device):"
echo "  ios/build/Build/Products/$XCODE_CONFIGURATION-iphoneos/Where.app"
echo "  Use Xcode to sign and install to device"
echo ""
echo "To run the server: ./run-server.sh"
