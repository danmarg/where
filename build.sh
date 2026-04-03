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
USE_NIX=false
SERVER_URL="https://where-api.fly.dev"
BUILD_FLAVOR="debug"
ANDROID_FORMAT="aab"  # aab or apk
IOS_TEAM_ID="${IOS_TEAM_ID:-}"
DO_INSTALL=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --nix)
      USE_NIX=true
      shift
      ;;
    --server-url)
      SERVER_URL="$2"
      shift 2
      ;;
    --flavor)
      BUILD_FLAVOR="$2"
      shift 2
      ;;
    --apk)
      ANDROID_FORMAT="apk"
      shift
      ;;
    --team-id)
      IOS_TEAM_ID="$2"
      shift 2
      ;;
    --install)
      DO_INSTALL=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

run() {
  if $USE_NIX; then
    nix develop --command "$@"
  else
    "$@"
  fi
}

# Validate flavor
if [[ "$BUILD_FLAVOR" != "debug" && "$BUILD_FLAVOR" != "release" ]]; then
  echo "Invalid flavor: $BUILD_FLAVOR (must be 'debug' or 'release')"
  exit 1
fi

echo "Building with server URL: $SERVER_URL"
echo ""

# Build server
echo "=== Building server ==="
if ! run ./gradlew :server:build; then
  echo "Server build failed."
  exit 1
fi
echo "✓ Server built"
echo ""

# Configure local.properties with SERVER_HTTP_URL
if [ ! -f local.properties ]; then
  touch local.properties
fi
# Update or add SERVER_HTTP_URL
if grep -q "^SERVER_HTTP_URL=" local.properties; then
  sed -i '' "s|^SERVER_HTTP_URL=.*|SERVER_HTTP_URL=$SERVER_URL|" local.properties
else
  echo "SERVER_HTTP_URL=$SERVER_URL" >> local.properties
fi

# Build Android APK or AAB
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  if [[ "$ANDROID_FORMAT" == "apk" ]]; then
    GRADLE_TASK="assembleDebug"
  else
    GRADLE_TASK="bundleDebug"
  fi
else
  if [[ "$ANDROID_FORMAT" == "apk" ]]; then
    GRADLE_TASK="assembleRelease"
  else
    GRADLE_TASK="bundleRelease"
  fi
  # Prompt for signing credentials for release builds
  echo "=== Release Build Signing ==="
  read -sp "Enter keystore password: " KEYSTORE_PASSWORD
  echo ""
  export KEYSTORE_FILE=~/where-release-key.jks
  export KEYSTORE_PASSWORD
  export KEY_PASSWORD=$KEYSTORE_PASSWORD  # Same as keystore password
fi
echo "=== Building Android $BUILD_FLAVOR ${ANDROID_FORMAT} ==="
if ! run bash -c "KEYSTORE_FILE='$KEYSTORE_FILE' KEYSTORE_PASSWORD='$KEYSTORE_PASSWORD' KEY_PASSWORD='$KEY_PASSWORD' ./gradlew :android:$GRADLE_TASK"; then
  echo "Android build failed."
  exit 1
fi
echo "✓ Android ${ANDROID_FORMAT} built ($BUILD_FLAVOR)"
echo ""

# Build iOS for real device (iphoneos)
echo "=== Building iOS for real device ==="

# Generate Xcode project if not present
if [ ! -f ios/Where.xcodeproj/project.pbxproj ]; then
  echo "Generating Xcode project..."
  cd ios
  run xcodegen
  cd ..
fi

# Build for iphoneos (real device)
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  XCODE_CONFIGURATION="Debug"
else
  XCODE_CONFIGURATION="Release"
fi

# Set up signing parameters
if [ -z "$IOS_TEAM_ID" ]; then
  echo "=== Building iOS for real device ($XCODE_CONFIGURATION) - unsigned ==="
  SIGNING_FLAGS="CODE_SIGN_IDENTITY=\"\" CODE_SIGNING_REQUIRED=NO"
else
  echo "=== Building iOS for real device ($XCODE_CONFIGURATION) - signed with team $IOS_TEAM_ID ==="
  SIGNING_FLAGS="CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM=$IOS_TEAM_ID"
fi

if ! run bash -c "cd ios && WHERE_SERVER_HTTP_URL='$SERVER_URL' xcodebuild \
  -project Where.xcodeproj \
  -scheme Where \
  -configuration $XCODE_CONFIGURATION \
  -sdk iphoneos \
  -derivedDataPath build \
  $SIGNING_FLAGS \
  -allowProvisioningUpdates \
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

# Install to devices if requested
if $DO_INSTALL; then
  echo "=== Installing to devices ==="
  echo ""

  # Install Android APK via adb
  if [[ "$ANDROID_FORMAT" == "apk" ]]; then
    android_build_dir=$(run ./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")
    APK_PATH="$android_build_dir/outputs/apk/$BUILD_FLAVOR/android-$BUILD_FLAVOR.apk"
    if [ -f "$APK_PATH" ]; then
      echo "Installing Android APK..."
      if adb install "$APK_PATH"; then
        echo "✓ Android APK installed"
      else
        echo "⚠ adb install failed. Make sure a device is connected."
      fi
    else
      echo "⚠ APK not found at $APK_PATH"
    fi
    echo ""
  fi

  # Install iOS app via ios-deploy
  if [ ! -z "$IOS_TEAM_ID" ]; then
    APP_BUNDLE="ios/build/Build/Products/${XCODE_CONFIGURATION}-iphoneos/Where.app"
    if [ -d "$APP_BUNDLE" ]; then
      if command -v ios-deploy &> /dev/null; then
        echo "Installing iOS app..."
        if ios-deploy --bundle "$APP_BUNDLE" --justlaunch; then
          echo "✓ iOS app installed and launched"
        else
          echo "⚠ ios-deploy failed. Ensure device is connected and unlocked."
        fi
      else
        echo "⚠ ios-deploy not installed. Install with:"
        echo "  brew install ios-deploy"
      fi
    else
      echo "⚠ iOS app bundle not found at $APP_BUNDLE"
    fi
  else
    echo "⚠ iOS installation requires --team-id (app is unsigned)"
  fi
  echo ""
fi

# Summary
echo "=== Build complete ==="
echo ""
echo "Server: ./gradlew :server:run"
echo ""
android_build_dir=$(run ./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")
if [[ "$ANDROID_FORMAT" == "apk" ]]; then
  echo "Android APK location:"
  echo "  $android_build_dir/outputs/apk/$BUILD_FLAVOR/android-$BUILD_FLAVOR.apk"
  if ! $DO_INSTALL; then
    echo "  Install with: adb install <path>"
  fi
else
  echo "Android AAB location:"
  echo "  $android_build_dir/outputs/bundle/$BUILD_FLAVOR/android-$BUILD_FLAVOR.aab"
  echo "  Upload to Google Play Store or use bundletool to test"
fi
echo ""
echo "iOS app location (device):"
echo "  ios/build/Build/Products/$XCODE_CONFIGURATION-iphoneos/Where.app"
if [ -z "$IOS_TEAM_ID" ]; then
  echo "  (unsigned - use Xcode to sign and install to device)"
fi
echo ""
if ! $DO_INSTALL; then
  echo "To build and install: ./build.sh --install"
  echo ""
fi
echo "To run the server: ./gradlew :server:run"
