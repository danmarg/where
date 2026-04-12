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
SERVER_URL="https://where-api.af0.net"
BUILD_FLAVOR="debug"
ANDROID_FORMAT="aab"  # aab or apk
IOS_TEAM_ID="${IOS_TEAM_ID:-}"
DO_INSTALL=false
IOS_TARGET="device"  # device or simulator
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
    --simulator)
      IOS_TARGET="simulator"
      shift
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

run() {
  "$@"
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

# Build iOS
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  XCODE_CONFIGURATION="Debug"
else
  XCODE_CONFIGURATION="Release"
fi

if [[ "$IOS_TARGET" == "simulator" ]]; then
  IOS_SDK="iphonesimulator"
  echo "=== Building iOS for simulator ($XCODE_CONFIGURATION) ==="
else
  IOS_SDK="iphoneos"
  echo "=== Building iOS for real device ($XCODE_CONFIGURATION) ==="
fi

# Always regenerate the Xcode project from project.yml before building.
echo "Cleaning old iOS build..."
rm -rf ios/build
echo "Generating Xcode project..."
cd ios && run xcodegen && cd ..

# Write team ID to Local.xcconfig so it survives xcodegen regeneration.
# Local.xcconfig is gitignored and loaded by the project for all configurations.
if [ -n "$IOS_TEAM_ID" ]; then
  if grep -q "^DEVELOPMENT_TEAM" ios/Local.xcconfig; then
    sed -i '' "s/^DEVELOPMENT_TEAM.*/DEVELOPMENT_TEAM = $IOS_TEAM_ID/" ios/Local.xcconfig
  else
    echo "DEVELOPMENT_TEAM = $IOS_TEAM_ID" >> ios/Local.xcconfig
  fi
fi

# Build the KMP shared framework using the direct link task for the target
# architecture. This avoids embedAndSignAppleFrameworkForXcode, which requires
# Xcode env vars to be set at Gradle daemon startup and is unreliable outside Xcode.
echo "=== Building KMP shared framework for $IOS_SDK ==="
if [[ "$IOS_TARGET" == "simulator" ]]; then
  LINK_TASK=":shared:link${XCODE_CONFIGURATION}FrameworkIosSimulatorArm64"
  FRAMEWORK_SRC="shared/build/bin/iosSimulatorArm64/${BUILD_FLAVOR}Framework/Shared.framework"
else
  LINK_TASK=":shared:link${XCODE_CONFIGURATION}FrameworkIosArm64"
  FRAMEWORK_SRC="shared/build/bin/iosArm64/${BUILD_FLAVOR}Framework/Shared.framework"
fi

SDKROOT=$(xcrun --sdk $IOS_SDK --show-sdk-path)
if ! run ./gradlew "$LINK_TASK" -Dios.sdk.root="$SDKROOT"; then
  echo "KMP framework build failed."
  exit 1
fi

# Place the framework where Xcode's FRAMEWORK_SEARCH_PATHS expects it:
# shared/build/xcode-frameworks/<Configuration>/<sdk_name>/Shared.framework
# The project uses $(SDK_NAME) which resolves to the unversioned name, so we
# create a versioned dir and symlink the unversioned name to it.
SDK_VERSION=$(xcrun --sdk "$IOS_SDK" --show-sdk-version)
FRAMEWORK_DEST="shared/build/xcode-frameworks/$XCODE_CONFIGURATION/${IOS_SDK}${SDK_VERSION}"
mkdir -p "$FRAMEWORK_DEST"
rm -rf "$FRAMEWORK_DEST/Shared.framework"
cp -r "$FRAMEWORK_SRC" "$FRAMEWORK_DEST/"
ln -sfn "${IOS_SDK}${SDK_VERSION}" "shared/build/xcode-frameworks/$XCODE_CONFIGURATION/$IOS_SDK"

echo "✓ KMP shared framework built"
echo ""

# Ensure XCFramework is built
if [[ "$BUILD_FLAVOR" == "debug" ]]; then
  ./gradlew :shared:assembleSharedDebugXCFramework
else
  ./gradlew :shared:assembleSharedReleaseXCFramework
fi

# Build the iOS app with xcodebuild.
if [[ "$IOS_TARGET" == "simulator" ]]; then
  if ! run bash -c "cd ios && WHERE_SERVER_HTTP_URL='$SERVER_URL' xcodebuild \
    -project Where.xcodeproj \
    -scheme Where \
    -configuration $XCODE_CONFIGURATION \
    -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build \
    ARCHS=arm64 \
    CODE_SIGN_IDENTITY='' CODE_SIGNING_REQUIRED=NO \
    build 2>&1 | tee ../ios_build.log"; then
    echo "iOS build failed."
    exit 1
  fi
else
  if [ -z "$IOS_TEAM_ID" ]; then
    SIGNING_FLAGS="CODE_SIGN_IDENTITY=\"\" CODE_SIGNING_REQUIRED=NO"
  else
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
fi

if grep -q "BUILD FAILED" ios_build.log; then
  echo "Xcode build failed."
  exit 1
fi

echo "✓ iOS app built ($XCODE_CONFIGURATION, $IOS_TARGET)"
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

  # Install iOS app
  if [[ "$IOS_TARGET" == "simulator" ]]; then
    APP_BUNDLE="ios/build/Build/Products/${XCODE_CONFIGURATION}-iphonesimulator/Where.app"
    if [ -d "$APP_BUNDLE" ]; then
      echo "Installing to simulator..."
      # Boot the default simulator if nothing is booted
      BOOTED=$(xcrun simctl list devices booted | grep -c "Booted" || true)
      if [[ "$BOOTED" -eq 0 ]]; then
        SIM_ID=$(xcrun simctl list devices available | grep "iPhone" | tail -1 | sed 's/.*(\([A-F0-9-]*\)).*/\1/')
        xcrun simctl boot "$SIM_ID"
        open -a Simulator
      fi
      xcrun simctl install booted "$APP_BUNDLE"
      xcrun simctl launch booted net.af0.Where
      echo "✓ iOS app installed and launched on simulator"
    else
      echo "⚠ iOS app bundle not found at $APP_BUNDLE"
    fi
  else
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
if [[ "$IOS_TARGET" == "simulator" ]]; then
  echo "iOS app location (simulator):"
  echo "  ios/build/Build/Products/$XCODE_CONFIGURATION-iphonesimulator/Where.app"
else
  echo "iOS app location (device):"
  echo "  ios/build/Build/Products/$XCODE_CONFIGURATION-iphoneos/Where.app"
  if [ -z "$IOS_TEAM_ID" ]; then
    echo "  (unsigned - pass --team-id to sign and install)"
  fi
fi
echo ""
if ! $DO_INSTALL; then
  echo "To build and install: ./build.sh --install"
  echo ""
fi
echo "To run the server: ./gradlew :server:run"
