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

adb_cmd() { run adb "$@"; }

# Start first emulator if needed
if ! adb_cmd devices | grep -q "emulator-5554"; then
  echo "Starting first emulator (port 5554)..."
  run emulator -avd pixel9 -port 5554 -no-audio -no-boot-anim -gpu host &
fi

# Start second emulator if needed
if ! adb_cmd devices | grep -q "emulator-5556"; then
  echo "Starting second emulator (port 5556)..."
  # Use -read-only if starting the same AVD twice
  run emulator -avd pixel9 -port 5556 -no-audio -no-boot-anim -gpu host -read-only &
fi

echo "Waiting for both emulators to boot..."
run adb -s emulator-5554 wait-for-device
run adb -s emulator-5556 wait-for-device

until run adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do sleep 2; done
until run adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do sleep 2; done

run adb -s emulator-5554 shell input keyevent 82  # unlock screen
run adb -s emulator-5556 shell input keyevent 82  # unlock screen

echo "Building APK..."
if ! run ./gradlew :android:assembleDebug; then
  echo "Gradle build failed."
  exit 1
fi

APK_PATH="${BUILD_DIR}/outputs/apk/debug/android-debug.apk"
echo "Installing APK on both devices..."
run adb -s emulator-5554 install -r "$APK_PATH"
run adb -s emulator-5556 install -r "$APK_PATH"

echo "Launching on both devices..."
run adb -s emulator-5554 shell am start -n net.af0.where/.MainActivity
run adb -s emulator-5556 shell am start -n net.af0.where/.MainActivity
