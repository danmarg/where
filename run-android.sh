#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")"

# Load machine-specific environment if it exists
if [ -f .envrc ]; then
  source .envrc
fi

# Set TMPDIR early so nix and gradle can create temp files
export TMPDIR="${TMPDIR:-/tmp}"

# Path defaults — override via environment variables or local.properties.
ANDROID_SDK_BASE="${ANDROID_SDK_BASE:-$HOME/.android/sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
BUILD_DIR="${BUILD_DIR:-$(nix develop --command ./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")}"

# Also read from local.properties if the env vars are not set
if [ -f local.properties ]; then
  _PROP_SDK=$(grep "^ANDROID_SDK_BASE=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)
  _PROP_BUILD=$(grep "^BUILD_DIR=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)
  [ -n "$_PROP_SDK" ] && ANDROID_SDK_BASE="$_PROP_SDK"
  [ -n "$_PROP_BUILD" ] && BUILD_DIR="$_PROP_BUILD"
fi

export ANDROID_HOME="$ANDROID_SDK_BASE"
export ANDROID_SDK_ROOT="$ANDROID_SDK_BASE"
export ANDROID_AVD_HOME
export PATH="$ANDROID_SDK_BASE/emulator:$ANDROID_SDK_BASE/platform-tools:$ANDROID_SDK_BASE/cmdline-tools/latest/bin:$PATH"

# Read ANDROID_ADB_HOST from local.properties if set
ADB_HOST=$(grep "^ANDROID_ADB_HOST=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]' || true)

if [ -n "$ADB_HOST" ]; then
  ADB="nix develop --command adb -s $ADB_HOST:36869"
  nix develop --command adb connect "$ADB_HOST:36869" 2>/dev/null || true
else
  ADB="nix develop --command adb"
fi

# Check for any connected device (physical or emulator)
if $ADB devices | grep -q "device$"; then
  echo "Device connected."
else
  echo "No device found. Starting emulator..."
  # Let the emulator auto-detect hardware acceleration (KVM on Linux, HAXM on macOS).
  # Use -gpu host to avoid HV_UNSUPPORTED on some Apple Silicon setups.
  nix develop --command emulator -avd pixel9 -no-audio -no-boot-anim -gpu host &
  echo "Waiting for emulator to boot..."
  nix develop --command adb wait-for-device
  nix develop --command adb shell input keyevent 82  # unlock screen
fi

echo "Building APK..."
if ! nix develop --command ./gradlew :android:assembleDebug; then
  echo "Gradle build failed."
  exit 1
fi

APK_PATH="${BUILD_DIR}/android/outputs/apk/debug/android-debug.apk"
echo "Installing APK from $APK_PATH..."
$ADB install -r "$APK_PATH"

echo "Launching app..."
$ADB shell am start -n net.af0.where/.MainActivity
