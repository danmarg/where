#!/usr/bin/env bash
set -e
set -o pipefail
cd "$(dirname "$0")"

USE_NIX=false
for arg in "$@"; do
  [[ "$arg" == "--nix" ]] && USE_NIX=true
done

run() {
  if $USE_NIX; then
    nix develop --command "$@"
  else
    "$@"
  fi
}

# Load machine-specific environment if it exists
if [ -f .envrc ]; then
  source .envrc
fi

# Set TMPDIR early so nix and gradle can create temp files
export TMPDIR="${TMPDIR:-/tmp}"

# Path defaults — override via environment variables or local.properties.
ANDROID_SDK_BASE="${ANDROID_SDK_BASE:-$HOME/.android/sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
BUILD_DIR="${BUILD_DIR:-$(run ./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")}"

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

adb_cmd() { run adb "$@"; }

if [ -n "$ADB_HOST" ]; then
  adb_cmd() { run adb -s "$ADB_HOST:36869" "$@"; }
  run adb connect "$ADB_HOST:36869" 2>/dev/null || true
fi

# Check for any connected device (physical or emulator)
if adb_cmd devices | grep -q "device$"; then
  echo "Device connected."
else
  echo "No device found. Starting emulator..."
  # Let the emulator auto-detect hardware acceleration (KVM on Linux, HAXM on macOS).
  # Use -gpu host to avoid HV_UNSUPPORTED on some Apple Silicon setups.
  run emulator -avd pixel9 -no-audio -no-boot-anim -gpu host &
  echo "Waiting for emulator to boot..."
  run adb wait-for-device
  echo "Waiting for emulator to finish booting..."
  until run adb shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do sleep 2; done
  run adb shell input keyevent 82  # unlock screen
fi

echo "Building APK..."
if ! run ./gradlew :android:assembleDebug; then
  echo "Gradle build failed."
  exit 1
fi

APK_PATH="${BUILD_DIR}/outputs/apk/debug/android-debug.apk"
echo "Installing APK from $APK_PATH..."
adb_cmd install -r "$APK_PATH"

echo "Launching app..."
adb_cmd shell am start -n net.af0.where/.MainActivity
