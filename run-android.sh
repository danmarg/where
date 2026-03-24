#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

# Path defaults — override via environment variables or local.properties.
ANDROID_SDK_BASE="${ANDROID_SDK_BASE:-/Volumes/Ext/android-sdk}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-/Volumes/Ext/android-avd}"
BUILD_DIR="${BUILD_DIR:-$(./gradlew -q :android:printBuildDir 2>/dev/null || echo "android/build")}"

# Also read from local.properties if the env vars are not set
if [ -f local.properties ]; then
  _PROP_SDK=$(grep "^ANDROID_SDK_BASE=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')
  _PROP_BUILD=$(grep "^BUILD_DIR=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')
  [ -n "$_PROP_SDK" ] && ANDROID_SDK_BASE="$_PROP_SDK"
  [ -n "$_PROP_BUILD" ] && BUILD_DIR="$_PROP_BUILD"
fi

export ANDROID_HOME="$ANDROID_SDK_BASE"
export ANDROID_SDK_ROOT="$ANDROID_SDK_BASE"
export ANDROID_AVD_HOME
export PATH="$ANDROID_SDK_BASE/emulator:$ANDROID_SDK_BASE/platform-tools:$ANDROID_SDK_BASE/cmdline-tools/latest/bin:$PATH"

# Read ANDROID_ADB_HOST from local.properties if set
ADB_HOST=$(grep "^ANDROID_ADB_HOST=" local.properties 2>/dev/null | cut -d= -f2 | tr -d '[:space:]')

if [ -n "$ADB_HOST" ]; then
  ADB="adb -s $ADB_HOST:36869"
  adb connect "$ADB_HOST:36869" 2>/dev/null || true
else
  ADB="adb"
fi

# Check for any connected device (physical or emulator)
if $ADB devices | grep -q "device$"; then
  echo "Device connected."
else
  echo "No device found. Starting emulator..."
  # Let the emulator auto-detect hardware acceleration (KVM on Linux, HAXM on macOS).
  emulator -avd pixel9 -no-audio -no-boot-anim &
  echo "Waiting for emulator to boot..."
  adb wait-for-device
  adb shell input keyevent 82  # unlock screen
  ADB="adb"
fi

echo "Building APK..."
nix develop --command ./gradlew :android:assembleDebug

APK_PATH="${BUILD_DIR}/android/outputs/apk/debug/android-debug.apk"
echo "Installing APK from $APK_PATH..."
$ADB install -r "$APK_PATH"

echo "Launching app..."
$ADB shell am start -n net.af0.where/.MainActivity
