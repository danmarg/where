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
ANDROID_SDK_BASE="${ANDROID_SDK_BASE:-$HOME/Library/Android/sdk}"
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

# Dynamically fetch the 'pixel9' AVD
AVD_NAME=$(run emulator -list-avds | grep "pixel9" | head -1)

if [ -z "$AVD_NAME" ]; then
echo "Error: 'pixel9' AVD not found."
echo "Available AVDs:"
run emulator -list-avds
exit 1
fi

# Explicitly check for a booted emulator.
EMU_SERIAL=$(run adb devices | grep "^emulator-" | cut -f1 | head -1)

if [ -z "$EMU_SERIAL" ]; then
echo "No emulator running. Starting ($AVD_NAME)..."
# -no-snapshot-load ensures a fresh boot which often resolves windowing/state issues.
run emulator -avd "$AVD_NAME" -no-audio -no-boot-anim -gpu host -no-snapshot-load > /dev/null 2>&1 &

echo "Waiting for emulator to appear in adb devices..."
# Give it a moment to actually start the process
sleep 2
until run adb devices | grep -q "^emulator-"; do 
if ! pgrep -x "emulator" > /dev/null; then
echo "Emulator process died unexpectedly. Check logs."
exit 1
fi
sleep 1
done

EMU_SERIAL=$(run adb devices | grep "^emulator-" | cut -f1 | head -1)
echo "Emulator found: $EMU_SERIAL. Waiting for boot to complete..."

run adb -s "$EMU_SERIAL" wait-for-device
until run adb -s "$EMU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; do
sleep 2
done
run adb -s "$EMU_SERIAL" shell input keyevent 82  # unlock screen
fi

echo "Using device: $EMU_SERIAL"

echo "Building Android app..."
if ! run ./gradlew :android:assembleDebug; then
echo "Gradle build failed."
exit 1
fi

APK_PATH="${BUILD_DIR}/outputs/apk/debug/android-debug.apk"
echo "Installing APK from $APK_PATH..."
run adb -s "$EMU_SERIAL" install -r "$APK_PATH"

echo "Launching app..."
run adb -s "$EMU_SERIAL" shell am start -n net.af0.where/.MainActivity
